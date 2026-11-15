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
        return complete(new Completion(null, plugin, "other", Priority.BACKGROUND, system, user, pick, maxTokens));
    }

    /**
     * 排队（{@link PluginModelGate}：每条路线的名额是它配的并发数，本机模型一般是 1；前台的先）、调用、记进用量账
     * （{@link tantalum.shell.store.PluginUsage}，不算每日额度）。
     */
    @Override
    public Optional<String> complete(Completion request) {
        Engine bound = engine;
        if (bound == null) {
            return Optional.empty();
        }
        Routes routes = bound.routes();
        ModelPick pick = request.pick() == null ? ModelPick.DEFAULT : request.pick();
        String routeName = pick.route() != null ? pick.route() : routes.route("local").isPresent() ? "local" : routes.defaultRoute();
        Optional<Route> route = routes.route(routeName);
        if (route.isEmpty()) {
            LOG.warn("plugin {} asked for route {}, which does not exist", request.plugin(), routeName);
            return Optional.empty();
        }
        ModelChoice choice = new ModelChoice(routeName, pick.model() != null ? pick.model() : route.get().settings().defaultModel(), null);
        String who = request.user() == null ? "-" : request.user().value();
        long started = System.nanoTime();
        PluginModelGate.Permit permit;
        try {
            permit = gate(bound).acquire(routeName, request.priority(), PluginModelGate.waitFor(request.priority()));
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (permit == null) {
            LOG.warn("plugin {} gave up waiting for route {} ({})", request.plugin(), routeName, request.purpose());
            record(bound, new tantalum.shell.store.PluginUsage.Call(java.time.Instant.now(), who, request.plugin(), request.purpose(), routeName,
                    choice.model(), 0, 0, (System.nanoTime() - started) / 1_000_000, "busy"));
            return Optional.empty();
        }
        ModelOutcome outcome;
        try (permit) {
            Prompt prompt = new Prompt(request.system(), List.of(), List.of(Message.user(request.text())),
                    new Prompt.Output(false, request.maxTokens()), choice);
            outcome = route.get().client().call(prompt, new ChunkListener() {
                @Override
                public void onText(String text) {
                }

                @Override
                public void onReasoning(String text) {
                }
            });
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        if (!(outcome instanceof ModelOutcome.Replied replied)) {
            record(bound, new tantalum.shell.store.PluginUsage.Call(java.time.Instant.now(), who, request.plugin(), request.purpose(), routeName,
                    choice.model(), 0, 0, ms, "failed"));
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : replied.content()) {
            if (block instanceof TextBlock part) {
                text.append(part.text());
            }
        }
        long input = replied.usage() == null ? 0 : replied.usage().promptTotal();
        long output = replied.usage() == null ? 0 : replied.usage().output();
        record(bound, new tantalum.shell.store.PluginUsage.Call(java.time.Instant.now(), who, request.plugin(), request.purpose(), routeName,
                choice.model(), input, output, ms, "ok"));
        LOG.info("plugin {} model call ({}): {}/{} tokens via {}/{} in {} ms", request.plugin(), request.purpose(), input, output,
                choice.route(), choice.model(), ms);
        return Optional.of(text.toString());
    }

    private volatile PluginModelGate gate;
    private volatile tantalum.shell.store.PluginUsage usage;

    private PluginModelGate gate(Engine bound) {
        PluginModelGate current = gate;
        if (current == null) {
            synchronized (this) {
                if (gate == null) {
                    gate = new PluginModelGate(name -> bound.routes().route(name).map(route -> route.settings().concurrency()).orElse(1));
                }
                current = gate;
            }
        }
        return current;
    }

    /** 记一次用量；账打不开或写不进只记日志。 */
    private void record(Engine bound, tantalum.shell.store.PluginUsage.Call call) {
        try {
            usage(bound).record(call);
        } catch (java.io.IOException | java.sql.SQLException failure) {
            LOG.warn("cannot record plugin model usage: {}", failure.toString());
        }
    }

    /** 插件用量账（第一次用时打开）。 */
    public synchronized tantalum.shell.store.PluginUsage usage(Engine bound) throws java.io.IOException, java.sql.SQLException {
        if (usage == null) {
            usage = tantalum.shell.store.PluginUsage.open(bound.dataDir());
        }
        return usage;
    }

    /** 服务关闭时关掉用量账。 */
    public synchronized void close() {
        if (usage != null) {
            try {
                usage.close();
            } catch (java.sql.SQLException ignored) {
                // 关不掉不影响退出
            }
            usage = null;
        }
    }

    @Override
    public List<tantalum.shell.plugin.RunSegment> runView(Principal user, SessionId session, tantalum.value.id.RunId run) {
        Engine bound = engine;
        if (bound == null) {
            return List.of();
        }
        return tantalum.shell.trace.RunText.segments(bound.queries().runs().records(user, session, run), bound::toolPlugin);
    }

    @Override
    public List<FinishedRef> finishedRuns(Principal user, java.time.Instant since, int limit) {
        Engine bound = engine;
        if (bound == null || limit <= 0) {
            return List.of();
        }
        List<FinishedRef> found = new java.util.ArrayList<>();
        String after = null;
        do {
            tantalum.shell.store.RunTable.Page page = bound.queries().runs().list(user, new tantalum.shell.store.RunTable.Filter(since, null,
                    java.util.Set.of("COMPLETED"), null, null, null, null, tantalum.shell.store.RunTable.Sort.STARTED, after,
                    Math.min(100, limit - found.size())));
            for (tantalum.shell.store.RunTable.Listed listed : page.runs()) {
                tantalum.shell.trace.RunRow row = listed.row();
                found.add(new FinishedRef(row.session(), row.run(), row.turn(), row.start(), row.end(),
                        row.endReason() == null ? null : row.endReason().name(),
                        row.trigger() == tantalum.shell.trace.TraceRecord.RunHead.Trigger.MANUAL_COMPACTION));
            }
            after = page.next();
        } while (after != null && found.size() < limit);
        return found;
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
