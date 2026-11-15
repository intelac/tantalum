package tantalum.shell.boot;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.plugin.PluginServices;
import tantalum.shell.route.Route;
import tantalum.shell.route.Routes;
import tantalum.shell.tool.Workspaces;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Prompt;
import tantalum.value.model.TextBlock;

/**
 * 平台给插件的服务（memory-plugin 需求 1.3–1.6）。插件构造时就拿到这个对象，平台启动好以后 {@link #bind} 接上引擎；
 * 接上之前调用返回空。
 */
public final class PlatformServices implements PluginServices {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformServices.class);
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private volatile Engine engine;

    void bind(Engine engine) {
        this.engine = engine;
    }

    @Override
    public Path dataDir(String plugin, String name, Principal user) {
        Engine bound = engine;
        if (bound == null) {
            throw new IllegalStateException("plugin services used before the engine started");
        }
        return dataDir(bound.stores().workspaces(), bound.dataDir(), name, user);
    }

    @Override
    public Path stateDir(String plugin, Principal user) {
        Engine bound = engine;
        if (bound == null) {
            throw new IllegalStateException("plugin services used before the engine started");
        }
        return stateDir(bound.dataDir(), plugin, user);
    }

    /** 插件私有状态目录的规矩：{@code <data>/plugins/<插件>/<用户>}。名字不合规矩时抛 {@link IllegalArgumentException}。 */
    public static Path stateDir(Path data, String plugin, Principal user) {
        if (!NAME.matcher(plugin).matches()) {
            throw new IllegalArgumentException("plugin name must be lowercase letters, digits, . _ - : " + plugin);
        }
        if (!Principal.isUsable(user.value())) {
            throw new IllegalArgumentException("user name cannot be used as a directory: " + user.value());
        }
        return data.resolve("plugins").resolve(plugin).resolve(user.value());
    }

    /**
     * 插件数据目录的规矩，和以前的记忆目录一样：共用工作区时放数据目录（共用目录会被所有人的 grep 搜到），每人模式放在这个人的工作区里。
     * 不建目录：插件第一次写时按自己的权限建。名字不合规矩时抛 {@link IllegalArgumentException}。
     */
    public static Path dataDir(Workspaces workspaces, Path data, String name, Principal user) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("plugin data directory name must be lowercase letters, digits, . _ - : " + name);
        }
        if (!Principal.isUsable(user.value())) {
            throw new IllegalArgumentException("user name cannot be used as a directory: " + user.value());
        }
        return workspaces.isShared() ? data.resolve(name).resolve(user.value()) : workspaces.of(user).root().resolve(name);
    }

    @Override
    public Optional<String> complete(String plugin, String system, String user, ModelPick pick, int maxTokens) {
        Engine bound = engine;
        if (bound == null) {
            return Optional.empty();
        }
        Routes routes = bound.routes();
        String routeName = pick.route() != null ? pick.route() : routes.route("local").isPresent() ? "local" : routes.defaultRoute();
        Optional<Route> route = routes.route(routeName);
        if (route.isEmpty()) {
            LOG.warn("plugin {} asked for route {}, which does not exist", plugin, routeName);
            return Optional.empty();
        }
        ModelChoice choice = new ModelChoice(routeName, pick.model() != null ? pick.model() : route.get().settings().defaultModel(), null);
        Prompt prompt = new Prompt(system, List.of(), List.of(Message.user(user)), new Prompt.Output(false, maxTokens), choice);
        ModelOutcome outcome = route.get().client().call(prompt, new ChunkListener() {
            @Override
            public void onText(String text) {
            }

            @Override
            public void onReasoning(String text) {
            }
        });
        if (!(outcome instanceof ModelOutcome.Replied replied)) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : replied.content()) {
            if (block instanceof TextBlock part) {
                text.append(part.text());
            }
        }
        LOG.info("plugin {} model call: {}/{} tokens via {}/{}", plugin, replied.usage() == null ? "?" : replied.usage().promptTotal(),
                replied.usage() == null ? "?" : replied.usage().output(), choice.route(), choice.model());
        return Optional.of(text.toString());
    }

    @Override
    public Json.Obj params(String plugin, Principal user) {
        Engine bound = engine;
        return bound == null ? new Json.Obj(Map.of()) : bound.plugins().params(user, plugin);
    }

    @Override
    public boolean enabled(String plugin, Principal user) {
        Engine bound = engine;
        return bound != null && bound.plugins().pluginEnabled(user, plugin);
    }

    @Override
    public List<ConversationHit> searchConversations(Principal user, String query, SessionId excluding, int limit) {
        Engine bound = engine;
        if (bound == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            return bound.stores().storage().index.search(user, query, limit + 1).stream()
                    .filter(hit -> !hit.session().equals(excluding))
                    .limit(limit)
                    .map(hit -> new ConversationHit(hit.session(), hit.role(), hit.text(), null))
                    .toList();
        } catch (SQLException failure) {
            LOG.warn("conversation search failed: {}", failure.toString());
            return List.of();
        }
    }
}
