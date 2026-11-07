package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tantalum.kernel.Limits;
import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.boot.TantalumProperties.Loop;
import tantalum.shell.loop.Evictor;
import tantalum.shell.memory.MemoryLimits;
import tantalum.shell.route.RouteSettings;
import tantalum.shell.settings.CompactionPolicy;

class TantalumPropertiesTest {

    /** 发布的 application.properties，再叠加运维只改的几项。 */
    private static TantalumProperties bind(String... overrides) throws Exception {
        Properties properties = new Properties();
        try (InputStream defaults = TantalumProperties.class.getResourceAsStream("/application.properties")) {
            properties.load(defaults);
        }
        for (int i = 0; i < overrides.length; i += 2) {
            properties.setProperty(overrides[i], overrides[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(properties)).bind("tantalum", TantalumProperties.class).get();
    }

    /** 配置的窗口进各自路线的配置，读不到窗口时压缩和配置接口都用它（model-picker M4）。 */
    @Test
    void eachRouteCarriesItsConfiguredWindow() throws Exception {
        List<RouteSettings> routes = bind("tantalum.provider", "local", "tantalum.openai-routes.local.base-url", "http://127.0.0.1:8090/v1",
                "tantalum.openai-routes.local.model", "qwen", "tantalum.openai-routes.local.context-window", "64000",
                "tantalum.claude.context-window", "150000").routeSettings();
        assertEquals(List.of("claude", "local"), routes.stream().map(RouteSettings::name).toList());
        assertEquals(150000, routes.get(0).contextWindow());
        assertEquals(64000, routes.get(1).contextWindow());
    }

    @Test
    void changingOneResidencySettingKeepsTheOtherDefaults() throws Exception {
        assertEquals(Evictor.Settings.defaults(), bind().residency());
        Evictor.Settings residency = bind("tantalum.residency.idle", "2s").residency();
        assertEquals(new Evictor.Settings(Duration.ofSeconds(2), 1000, Duration.ofSeconds(1), Duration.ofMinutes(1)), residency);
        assertEquals(Duration.ofSeconds(5), bind("tantalum.residency.load-retry", "5s").residency().loadRetry());
    }

    @Test
    void defaultsSatisfyTheFormulas() {
        Loop loop = Loop.defaults();
        assertEquals(64, loop.maxActiveRuns());
        assertEquals(10, loop.maxParallelTools());
        assertEquals(1024, loop.outputRingSize());
        assertEquals(1024, loop.inputRingSize());
        assertEquals(640, loop.internalReserve());
        assertEquals(256, loop.maxUserPermits(), "a quarter of the input ring by default (ops-hardening-2 D4)");
    }

    /** ops-hardening-2 需求 4.2：每个用户的名额上限在 max-user-in-flight 和 input-ring-size 之间。 */
    @Test
    void userPermitsAreBoundedByUserInFlightAndTheRing() {
        assertEquals(8, new Loop(64, 10, 8, 256, 1024, 1024, 8).maxUserPermits());
        IllegalArgumentException low = assertThrows(IllegalArgumentException.class, () -> new Loop(64, 10, 8, 256, 1024, 1024, 4));
        assertTrue(low.getMessage().contains("max-user-permits") && low.getMessage().contains("max-user-in-flight"), low.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Loop(64, 10, 8, 256, 1024, 1024, 2048));
    }

    @Test
    void outputRingMustHoldEveryCommand() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Loop(64, 10, 8, 256, 1024, 512, 0));
        assertTrue(failure.getMessage().contains("outputRingSize"));
    }

    @Test
    void inputRingMustHoldInternalResultsAndExternalLimit() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Loop(64, 10, 8, 256, 512, 1024, 0));
        assertTrue(failure.getMessage().contains("inputRingSize"));
    }

    @Test
    void ringSizesMustBePowersOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 1, 1, 1, 1000, 1024, 0));
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 1, 1, 1, 1024, 1000, 0));
    }

    @Test
    void memoryLimitsHaveDefaultsAndMustBePositive() {
        MemoryLimits memory = MemoryLimits.defaults();
        assertEquals(200, memory.maxEntries());
        assertEquals(16 * 1024, memory.maxIndexBytes());
        assertEquals(8 * 1024, memory.maxEntryBytes());
        assertThrows(IllegalArgumentException.class, () -> new MemoryLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MemoryLimits(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new MemoryLimits(1, 1, -1));
    }

    @Test
    void blankKnowledgeDirMeansNoSharedLayer() {
        assertTrue(TantalumProperties.Knowledge.none().path().isEmpty());
        assertTrue(new TantalumProperties.Knowledge("  ", 10).path().isEmpty());
        assertTrue(new TantalumProperties.Knowledge(null, 10).path().isEmpty());
        assertEquals(java.nio.file.Path.of("/srv/knowledge"), new TantalumProperties.Knowledge("/srv/knowledge", 10).path().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new TantalumProperties.Knowledge("/x", 0));
    }

    @Test
    void compactionHasDefaultsAndMustBePositive() {
        TantalumProperties.Compaction compaction = TantalumProperties.Compaction.defaults();
        assertEquals(16384, compaction.reserveTokens());
        assertEquals(20000, compaction.keepRecentTokens());
        assertEquals(32768, compaction.maxKeptToolResultBytes());
        assertEquals(4096, compaction.maxSummarizedToolResultBytes());
        assertEquals(8192, compaction.summaryMaxTokens());
        String message = assertThrows(IllegalArgumentException.class,
                () -> new TantalumProperties.Compaction(0, 1, 1, 1, 1, true)).getMessage();
        assertTrue(message.contains("reserveTokens"), message);
        assertThrows(IllegalArgumentException.class, () -> new TantalumProperties.Compaction(1, -1, 1, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new TantalumProperties.Compaction(1, 1, 0, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new TantalumProperties.Compaction(1, 1, 1, 0, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new TantalumProperties.Compaction(1, 1, 1, 1, 0, true));
    }

    /** 工作轮数默认 8，和内核的 {@code Limits.DEFAULT} 一致；小于 1 时绑定失败，启动随之失败。 */
    @Test
    void maxWorkRoundsDefaultsToEightAndMustBePositive() throws Exception {
        assertEquals(8, bind().hooks().maxWorkRounds());
        assertEquals(Limits.DEFAULT.maxWorkRounds(), bind().hooks().maxWorkRounds());
        assertEquals(3, bind("tantalum.hooks.max-work-rounds", "3").hooks().maxWorkRounds());
        BindException failure = assertThrows(BindException.class, () -> bind("tantalum.hooks.max-work-rounds", "0"));
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause.getMessage().contains("maxWorkRounds"), cause.getMessage());
        assertThrows(BindException.class, () -> bind("tantalum.hooks.max-work-rounds", "-1"));
        assertThrows(IllegalArgumentException.class, () -> new TantalumProperties.Hooks(0, List.of()));
    }

    /** 发布的配置和代码里的默认值是同一组数（A4、A5、配额 5 GiB）；每项都能单独改。 */
    @Test
    void attachmentLimitsDefaultToDesignAndCanBeChanged() throws Exception {
        assertEquals(AttachmentLimits.defaults(), bind().attachmentLimits());
        assertEquals(5L * 1024 * 1024 * 1024, bind().attachmentLimits().quotaBytes());
        assertEquals(1024, bind("tantalum.attachments.quota-bytes", "1024").attachmentLimits().quotaBytes());
        assertThrows(BindException.class, () -> bind("tantalum.attachments.max-file-bytes", "0"));
    }

    /** web-tools：发布的配置和代码里的默认值一致；浏览器上限小于 1 时启动失败。 */
    @Test
    void webDefaultsMatchTheDesignAndMaxOpenBelowOneFails() throws Exception {
        assertEquals(TantalumProperties.Web.defaults(), bind().webSettings());
        assertFalse(bind().webSettings().blockPrivateNetwork());
        assertEquals(Duration.ofMinutes(10), bind().webSettings().browser().idleClose());
        assertTrue(bind("tantalum.web.block-private-network", "true").webSettings().blockPrivateNetwork());
        assertFalse(bind("tantalum.web.browser.enabled", "false").webSettings().browser().enabled());
        BindException failure = assertThrows(BindException.class, () -> bind("tantalum.web.browser.max-open", "0"));
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause.getMessage().contains("max-open"), cause.getMessage());
        assertEquals(TantalumProperties.Web.defaults(), properties("claude", 0, 0, null).webSettings(), "no web section: defaults");
    }

    private static TantalumProperties properties(String provider, long claudeWindow, long openAiWindow, TantalumProperties.Compaction compaction) {
        return new TantalumProperties(null, null, "per-user", "p", java.time.Duration.ofSeconds(60), provider, Loop.defaults(),
                new TantalumProperties.Claude("claude-sonnet-5-5", 64000, "medium", true, java.time.Duration.ofSeconds(1),
                        java.time.Duration.ofSeconds(1), 4, claudeWindow, 1.5, null, null, null),
                java.util.Map.of("local", new TantalumProperties.OpenAiRoute(null, "http://127.0.0.1:8090/v1", null, "qwen", null, null,
                        16384, java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), 1, openAiWindow, 2.0, false, null,
                        null, null, null)),
                MemoryLimits.defaults(), TantalumProperties.Knowledge.none(), null, compaction, TantalumProperties.Index.defaults(),
                Evictor.Settings.defaults(), Duration.ofSeconds(30), new TantalumProperties.Hooks(8, List.of()), null, null);
    }

    /** 压缩参数和模型无关；预留按模型另算（CompactionPolicyTest）。路线配置的窗口不大于预留时启动失败（context-compaction 需求 9）。 */
    @Test
    void compactionLimitsAndTheConfiguredWindowCheck() throws Exception {
        CompactionPolicy.Limits limits = bind().compactionLimits();
        assertEquals(new CompactionPolicy.Limits(16_384, 20_000, 32_768, 4_096, 8_192), limits);
        assertEquals(200_000, properties("claude", 200_000, 131_072, TantalumProperties.Compaction.defaults()).routeSettings().getFirst().contextWindow());
        assertEquals(List.of(), properties("local", 200_000, 0, TantalumProperties.Compaction.defaults()).routeSettings().get(1).allow());
        assertThrows(IllegalArgumentException.class, () -> properties("local", 200_000, 16_000, TantalumProperties.Compaction.defaults()).routeSettings(),
                "a window no larger than the reserve is a configuration error");
        assertThrows(IllegalArgumentException.class, () -> properties("claude", 64_000, 0, null).routeSettings(),
                "Claude's max-tokens 64000 is the reserve");
    }

    /** compaction-threshold T7：估算系数按路线，非正数启动失败。 */
    @Test
    void theCoefficientIsPerRoute() throws Exception {
        assertEquals(1.5, bind().routeSettings().getFirst().bytesPerToken());
        assertEquals(1.75, bind("tantalum.openai-routes.local.base-url", "http://127.0.0.1:8090/v1", "tantalum.openai-routes.local.model", "qwen")
                .routeSettings().get(1).bytesPerToken());
        assertEquals(1.25, bind("tantalum.claude.bytes-per-token", "1.25").routeSettings().getFirst().bytesPerToken());
        assertThrows(BindException.class, () -> bind("tantalum.claude.bytes-per-token", "0"));
        assertThrows(BindException.class, () -> bind("tantalum.provider", "local", "tantalum.openai-routes.local.base-url", "http://127.0.0.1:8090/v1", "tantalum.openai-routes.local.model", "qwen", "tantalum.openai-routes.local.bytes-per-token", "-1"));
        assertTrue(bind().windowFromModel());
        assertFalse(bind("tantalum.compaction.window-from-model", "false").windowFromModel());
    }

    @Test
    void limitsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new Loop(0, 1, 1, 1, 1024, 1024, 0));
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 0, 1, 1, 1024, 1024, 0));
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 1, 0, 1, 1024, 1024, 0));
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 1, 1, 0, 1024, 1024, 0));
    }
}
