package tantalum.shell.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import tantalum.shell.model.OpenAiThinking;
import tantalum.shell.net.LogCapture;
import tantalum.value.model.ModelChoice;

/** model-picker 任务 6（需求 1.2、1.3、2.1、2.4，NFR Reliability）：全部路线的列表、刷新和密钥变量。 */
class RoutesTest {

    private static final Levels TOGGLE = new Levels(List.of("off", "on"), "on");

    /** 每条路线的列表怎么读，按路线名。 */
    private static Routes.Builder lists(Map<String, ModelList> byRoute) {
        return (route, key) -> new Routes.Built((prompt, chunks) -> null, byRoute.get(route.name()));
    }

    private static ModelList models(String route, String... ids) {
        return () -> List.of(ids).stream().map(id -> RouteFixtures.model(route, id, TOGGLE, false, true)).toList();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(20);
        }
    }

    @Test
    void routesAreReadInParallelAndASlowOneDoesNotHoldTheStart() throws Exception {
        ModelList slow = () -> {
            Thread.sleep(1500);
            return models("slow", "big").read();
        };
        try (Routes routes = Routes.create(List.of(RouteFixtures.openAi("fast", null, OpenAiThinking.CHAT_TEMPLATE, 0),
                RouteFixtures.openAi("slow", null, OpenAiThinking.NONE, 0)), "fast",
                lists(Map.of("fast", models("fast", "qwen"), "slow", slow)), null, name -> null, true, Clock.systemUTC())) {
            long started = System.nanoTime();
            routes.start(Duration.ofMillis(400));
            long waited = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(waited < 1200, "start waited " + waited + " ms");
            assertEquals(Listing.Status.OK, routes.route("fast").orElseThrow().listing().status());
            assertEquals(Listing.Status.UNAVAILABLE, routes.route("slow").orElseThrow().listing().status());
            await(() -> routes.route("slow").orElseThrow().listing().status() == Listing.Status.OK);
            assertEquals("big", routes.route("slow").orElseThrow().listing().models().getFirst().id());
        }
    }

    @Test
    void aFailedRefreshKeepsTheLastListAndARouteNeverReadIsUnavailable() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        ModelList flaky = () -> {
            if (reads.incrementAndGet() > 1) {
                throw new ModelList.Unreadable("超时");
            }
            return models("local", "qwen").read();
        };
        ModelList down = () -> {
            throw new ModelList.Unreadable("连不上");
        };
        try (Routes routes = Routes.create(List.of(RouteFixtures.openAi("local", null, OpenAiThinking.CHAT_TEMPLATE, 0),
                RouteFixtures.openAi("down", null, OpenAiThinking.NONE, 0)), "local", lists(Map.of("local", flaky, "down", down)),
                null, name -> null, true, Clock.systemUTC(), Duration.ofMillis(100))) {
            routes.start(Duration.ofSeconds(2));
            await(() -> routes.route("local").orElseThrow().listing().status() == Listing.Status.STALE);
            Listing stale = routes.route("local").orElseThrow().listing();
            assertEquals("超时", stale.error());
            assertEquals(List.of("qwen"), stale.models().stream().map(ModelInfo::id).toList(), "the last list is kept");
            assertTrue(routes.model(new ModelChoice("local", "qwen", "on")).isPresent());
            Listing never = routes.route("down").orElseThrow().listing();
            assertEquals(Listing.Status.UNAVAILABLE, never.status());
            assertEquals("连不上", never.error());
            assertEquals(List.of(), never.models());
        }
    }

    @Test
    void aRouteWhoseKeyVariableIsUnsetIsNotCreatedAndTheValueIsNeverLogged() throws Exception {
        Map<String, String> env = Map.of("LOCAL_KEY", "secret-value-1234");
        try (LogCapture logs = new LogCapture();
             Routes routes = Routes.create(List.of(RouteFixtures.openAi("local", "LOCAL_KEY", OpenAiThinking.NONE, 0),
                     RouteFixtures.openAi("kimi", "KIMI_API_KEY", OpenAiThinking.NONE, 0)), "local",
                     lists(Map.of("local", models("local", "qwen"), "kimi", models("kimi", "k2"))), null, env::get, true,
                     Clock.systemUTC()).start(Duration.ofSeconds(2))) {
            assertTrue(routes.route("kimi").isEmpty());
            assertTrue(logs.all().contains("route kimi is not enabled: the environment variable KIMI_API_KEY is not set"), logs.all());
            assertFalse(logs.all().contains("secret-value-1234"), logs.all());
            assertFalse(logs.all().contains("secret-path"), "no address in the logs: " + logs.all());
        }
    }

    @Test
    void theDefaultRouteNeedsItsKeyButNotItsList() throws Exception {
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> Routes.create(
                List.of(RouteFixtures.openAi("kimi", "KIMI_API_KEY", OpenAiThinking.NONE, 0)), "kimi",
                lists(Map.of()), null, name -> null, true, Clock.systemUTC()));
        assertTrue(missing.getMessage().startsWith("KIMI_API_KEY is not set"), missing.getMessage());

        ModelList down = () -> {
            throw new ModelList.Unreadable("401");
        };
        try (Routes routes = Routes.create(List.of(RouteFixtures.openAi("kimi", "KIMI_API_KEY", OpenAiThinking.NONE, 8_000)), "kimi",
                lists(Map.of("kimi", down)), null, name -> "key", true, Clock.systemUTC()).start(Duration.ofSeconds(2))) {
            assertEquals(Listing.Status.UNAVAILABLE, routes.route("kimi").orElseThrow().listing().status(), "starts anyway");
            assertEquals(new ModelChoice("kimi", "qwen", null), routes.defaultChoice());
            assertEquals(8_000, routes.describe(routes.defaultChoice()).orElseThrow().window(), "the configured window until a list arrives");
        }
    }

    @Test
    void injectedClientsNeedNoKeyAndListFromTheConfiguration() throws Exception {
        try (Routes routes = Routes.create(List.of(RouteFixtures.claude(List.of()), RouteFixtures.openAi("local", "LOCAL_KEY",
                OpenAiThinking.CHAT_TEMPLATE, 8_000)), "claude", (route, key) -> {
                    throw new AssertionError("no real client for " + route.name());
                }, new RouteClients(Map.<String, tantalum.shell.model.ModelClient>of("claude", (prompt, chunks) -> null, "local", (prompt, chunks) -> null)), name -> null, true,
                Clock.systemUTC()).start(Duration.ofSeconds(2))) {
            assertEquals(new ModelChoice("claude", "claude-sonnet-5-5", "medium"), routes.defaultChoice());
            ModelInfo local = routes.model(new ModelChoice("local", "qwen", "on")).orElseThrow();
            assertEquals(8_000, local.window());
            assertEquals(ModelInfo.Source.CONFIG, local.windowSource());
            assertEquals(TOGGLE, local.levels());
        }
    }

    /**
     * 模型报告的窗口盖过配置；关掉 window-from-model 时只用配置，来源写“来自配置”（测试要小窗口，compaction-threshold T1；
     * 原来在 SettingsApiTest，窗口改成每个模型一个后搬到这里）。
     */
    @Test
    void aReportedWindowWinsUnlessReadingIsTurnedOff() throws Exception {
        ModelList reporting = () -> List.of(new ModelInfo("local", "qwen", "qwen", 1_000_000, ModelInfo.Source.MODEL, 16_384, false,
                TOGGLE, true));
        for (boolean fromModel : new boolean[] {true, false}) {
            try (Routes routes = Routes.create(List.of(RouteFixtures.openAi("local", null, OpenAiThinking.CHAT_TEMPLATE, 200_000)), "local",
                    lists(Map.of("local", reporting)), null, name -> null, fromModel, Clock.systemUTC()).start(Duration.ofSeconds(2))) {
                ModelInfo qwen = routes.model(new ModelChoice("local", "qwen", "on")).orElseThrow();
                assertEquals(fromModel ? 1_000_000 : 200_000, qwen.window());
                assertEquals(fromModel ? ModelInfo.Source.MODEL : ModelInfo.Source.CONFIG, qwen.windowSource());
            }
        }
    }
}
