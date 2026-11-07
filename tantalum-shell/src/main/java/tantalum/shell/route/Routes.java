package tantalum.shell.route;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.model.ModelClient;
import tantalum.value.model.ModelChoice;

/**
 * 全部模型路线。每条路线一个客户端和一张模型列表：启动时每条路线一个虚拟线程并行读，总共最多等
 * {@code wait}，没读完的先是“暂时连不上”，读完再换上；之后每 10 分钟后台再读，失败时保留上次的（照 DSH {@code .agents/notes/implemented/architecture/2026-10-22-deepseek-official-model-listing.md:13-15}）。取列表只读内存。
 * 存密钥的环境变量没设的路线不建，日志写变量名不写值；默认路线的密钥变量没设时 {@link #create} 抛出，启动失败（列表读不到不算）。
 * 回放不经过这里：每次调用用哪个模型在 journal 里。
 */
public final class Routes implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Routes.class);
    public static final Duration REFRESH = Duration.ofMinutes(10);
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    /** 一条路线的客户端和列表，由 {@code boot} 按种类建：Claude 的两者共用一个 SDK 客户端。{@code apiKey} 可以为 null。 */
    @FunctionalInterface
    public interface Builder {
        Built build(RouteSettings route, String apiKey);
    }

    public record Built(ModelClient client, ModelList models) {}

    private final Map<String, Route> routes;
    private final String defaultRoute;
    private final boolean windowFromModel;
    private final Clock clock;
    private final Duration refreshEvery;
    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("model-routes").factory());

    private Routes(Map<String, Route> routes, String defaultRoute, boolean windowFromModel, Clock clock, Duration refreshEvery) {
        this.routes = routes;
        this.defaultRoute = defaultRoute;
        this.windowFromModel = windowFromModel;
        this.clock = clock;
        this.refreshEvery = refreshEvery;
    }

    /**
     * 建全部路线，还不读列表（见 {@link #start}）。{@code injected} 里的路线用给的客户端、按配置给列表，不要密钥；
     * {@code env} 按变量名取环境变量。{@code windowFromModel} 为 false 时窗口只用配置（测试要小窗口时）。
     */
    public static Routes create(List<RouteSettings> settings, String defaultRoute, Builder builder, RouteClients injected,
                                Function<String, String> env, boolean windowFromModel, Clock clock) {
        return create(settings, defaultRoute, builder, injected, env, windowFromModel, clock, REFRESH);
    }

    /**
     * 缺密钥时说怎么设。Windows 上没有 {@code ~/.config/tantalum/env} 的习惯：设成用户环境变量，计划任务启动的服务也读得到。
     */
    static String keyHint(String env, boolean windows) {
        return windows
                ? "for example in PowerShell: [Environment]::SetEnvironmentVariable(\"" + env + "\", \"...\", \"User\"), then open a new PowerShell"
                : "for example by loading ~/.config/tantalum/env";
    }

    static Routes create(List<RouteSettings> settings, String defaultRoute, Builder builder, RouteClients injected,
                         Function<String, String> env, boolean windowFromModel, Clock clock, Duration refreshEvery) {
        Map<String, Route> routes = new LinkedHashMap<>();
        for (RouteSettings route : settings) {
            ModelClient given = injected == null ? null : injected.clients().get(route.name());
            if (given != null) {
                routes.put(route.name(), new Route(route, given, new ConfigModels(route)));
                continue;
            }
            String key = null;
            if (route.apiKeyEnv() != null) {
                String value = env.apply(route.apiKeyEnv());
                if (value == null || value.isBlank()) {
                    if (route.name().equals(defaultRoute)) {
                        throw new IllegalStateException(route.apiKeyEnv() + " is not set, so the default route " + route.name()
                                + " cannot start. Set it, " + keyHint(route.apiKeyEnv(), WINDOWS) + ", or make another route "
                                + "the default with --tantalum.provider=<route>.");
                    }
                    LOG.warn("route {} is not enabled: the environment variable {} is not set", route.name(), route.apiKeyEnv());
                    continue;
                }
                key = value;
            }
            Built built = builder.build(route, key);
            routes.put(route.name(), new Route(route, built.client(), built.models()));
        }
        if (!routes.containsKey(defaultRoute)) {
            throw new IllegalStateException("the default route " + defaultRoute + " is not configured");
        }
        return new Routes(routes, defaultRoute, windowFromModel, clock, refreshEvery);
    }

    /** 并行读每条路线的列表，总共最多等 {@code wait}；然后开始定时刷新。每条路线写一行日志。 */
    public Routes start(Duration wait) {
        List<Thread> readers = new ArrayList<>();
        for (Route route : routes.values()) {
            readers.add(Thread.ofVirtual().name("model-list-" + route.name()).start(() -> read(route)));
        }
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            for (Thread reader : readers) {
                long left = deadline - System.nanoTime();
                if (left > 0) {
                    reader.join(Duration.ofNanos(left));
                }
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
        for (Route route : routes.values()) {
            Listing listing = route.listing();
            LOG.info("route {} ({}{}): {}, default model {}", route.name(), route.settings().kind().name().toLowerCase(),
                    route.settings().cloud() ? ", cloud" : ", local", listing.status() == Listing.Status.UNAVAILABLE
                            ? "no models yet (" + listing.error() + ")" : listing.models().size() + " models", route.settings().defaultModel());
        }
        long every = refreshEvery.toMillis();
        refresher.scheduleWithFixedDelay(this::refreshAll, every, every, TimeUnit.MILLISECONDS);
        return this;
    }

    private void refreshAll() {
        routes.values().forEach(route -> Thread.ofVirtual().name("model-list-" + route.name()).start(() -> read(route)));
    }

    /** 模型报上下文超长时：这条路线的窗口可能变了，马上再读一次。正在读时不再排。 */
    public void refreshSoon(String route) {
        Route found = routes.get(route);
        if (found != null && !refresher.isShutdown()) {
            Thread.ofVirtual().name("model-list-" + route).start(() -> read(found));
        }
    }

    private void read(Route route) {
        if (!route.startReading()) {
            return;
        }
        try {
            List<ModelInfo> models = route.models().read();
            if (!windowFromModel) {
                models = models.stream().map(model -> configWindow(route.settings(), model)).toList();
            }
            Listing before = route.listing();
            route.listing(Listing.ok(models, clock.instant()));
            if (before.status() != Listing.Status.OK || !before.models().equals(models)) {
                LOG.info("route {}: {} models", route.name(), models.size());
            }
        } catch (ModelList.Unreadable failure) {
            fail(route, failure.getMessage());
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } catch (Exception unexpected) {
            // 别的异常的说明可能带地址或响应体，只写类名
            fail(route, unexpected.getClass().getSimpleName());
        } finally {
            route.doneReading();
        }
    }

    private void fail(Route route, String reason) {
        Listing next = route.listing().failed(reason);
        route.listing(next);
        LOG.warn("route {}: cannot read the model list ({}); {}", route.name(), reason,
                next.status() == Listing.Status.STALE ? "keeping the last " + next.models().size() + " models" : "no models yet");
    }

    private static ModelInfo configWindow(RouteSettings route, ModelInfo model) {
        return new ModelInfo(model.route(), model.id(), model.name(), route.configuredWindow(model.id()), ModelInfo.Source.CONFIG,
                model.maxTokens(), model.vision(), model.levels(), model.toolChanges());
    }

    /** 全部建了的路线，Claude 在前，其余按名字。 */
    public List<Route> all() {
        return List.copyOf(routes.values());
    }

    public Optional<Route> route(String name) {
        return Optional.ofNullable(routes.get(name));
    }

    public String defaultRoute() {
        return defaultRoute;
    }

    /** 列表里有的模型；路线不在、列表里没有时为空。 */
    public Optional<ModelInfo> model(ModelChoice choice) {
        return route(choice.route()).flatMap(route -> route.listing().find(choice.model()));
    }

    /**
     * 这个模型能做什么：列表里有就用列表的，列表里没有（还没读到、刚下线）就按配置给，压缩和调用照样有窗口可用。
     * 路线不在时为空。
     */
    public Optional<ModelInfo> describe(ModelChoice choice) {
        return route(choice.route()).map(route -> route.listing().find(choice.model())
                .orElseGet(() -> ConfigModels.info(route.settings(), choice.model())));
    }

    /** 管理员的默认：默认路线、它的默认模型和这个模型的默认档位。 */
    public ModelChoice defaultChoice() {
        Route route = routes.get(defaultRoute);
        ModelChoice bare = new ModelChoice(defaultRoute, route.settings().defaultModel(), null);
        return new ModelChoice(defaultRoute, bare.model(), describe(bare).map(info -> info.levels().defaultLevel()).orElse(null));
    }

    @Override
    public void close() {
        refresher.shutdownNow();
    }
}
