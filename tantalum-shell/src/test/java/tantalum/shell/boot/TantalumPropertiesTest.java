package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Duration;
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

    /** 配置接口显示的窗口和压缩用的窗口是同一个值，换供应商也一样。 */
    @Test
    void contextWindowHasOneSourceForBothProviders() throws Exception {
        TantalumProperties openai = bind("tantalum.provider", "openai", "tantalum.openai.context-window", "64000");
        assertEquals(64000, openai.contextWindow());
        assertEquals(64000, openai.compactionSettings().window());
        TantalumProperties claude = bind("tantalum.claude.context-window", "150000");
        assertEquals(150000, claude.contextWindow());
        assertEquals(150000, claude.compactionSettings().window());
        assertNull(bind("tantalum.provider", "openai", "tantalum.openai.context-window", "0").compactionSettings());
    }

    @Test
    void changingOneResidencySettingKeepsTheOtherDefaults() throws Exception {
        assertEquals(Evictor.Settings.defaults(), bind().residency());
        Evictor.Settings residency = bind("tantalum.residency.idle", "2s").residency();
        assertEquals(new Evictor.Settings(Duration.ofSeconds(2), 1000, Duration.ofSeconds(1)), residency);
    }

    @Test
    void defaultsSatisfyTheFormulas() {
        Loop loop = Loop.defaults();
        assertEquals(64, loop.maxActiveRuns());
        assertEquals(10, loop.maxParallelTools());
        assertEquals(1024, loop.outputRingSize());
        assertEquals(1024, loop.inputRingSize());
        assertEquals(640, loop.internalReserve());
    }

    @Test
    void outputRingMustHoldEveryCommand() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Loop(64, 10, 8, 256, 1024, 512));
        assertTrue(failure.getMessage().contains("outputRingSize"));
    }

    @Test
    void inputRingMustHoldInternalResultsAndExternalLimit() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Loop(64, 10, 8, 256, 512, 1024));
        assertTrue(failure.getMessage().contains("inputRingSize"));
    }

    @Test
    void ringSizesMustBePowersOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 1, 1, 1, 1000, 1024));
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 1, 1, 1, 1024, 1000));
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
        assertThrows(IllegalArgumentException.class, () -> new TantalumProperties.Hooks(0));
    }

    /** 发布的配置和代码里的默认值是同一组数（A4、A5、配额 5 GiB）；每项都能单独改。 */
    @Test
    void attachmentLimitsDefaultToDesignAndCanBeChanged() throws Exception {
        assertEquals(AttachmentLimits.defaults(), bind().attachmentLimits());
        assertEquals(5L * 1024 * 1024 * 1024, bind().attachmentLimits().quotaBytes());
        assertEquals(1024, bind("tantalum.attachments.quota-bytes", "1024").attachmentLimits().quotaBytes());
        assertThrows(BindException.class, () -> bind("tantalum.attachments.max-file-bytes", "0"));
    }

    private static TantalumProperties properties(String provider, long claudeWindow, long openAiWindow, TantalumProperties.Compaction compaction) {
        return new TantalumProperties(null, null, "per-user", "p", java.time.Duration.ofSeconds(60), provider, Loop.defaults(),
                new TantalumProperties.Claude("claude-sonnet-5-5", 64000, "medium", true, java.time.Duration.ofSeconds(1),
                        java.time.Duration.ofSeconds(1), 4, claudeWindow, 1.5),
                new TantalumProperties.OpenAi("http://127.0.0.1:8090/v1", "qwen", 16384, java.time.Duration.ofSeconds(1),
                        java.time.Duration.ofSeconds(1), 1, openAiWindow, false, 2.0),
                MemoryLimits.defaults(), TantalumProperties.Knowledge.none(), null, compaction, TantalumProperties.Index.defaults(),
                Evictor.Settings.defaults(), Duration.ofSeconds(30), new TantalumProperties.Hooks(8), null, null);
    }

    /** 阈值 = 窗口 − max(预留, 该供应商的输出上限)：Claude 默认 200000 − 64000 = 136000，qwen 131072 − 16384 = 114688。 */
    @Test
    void compactionSettingsFollowTheProvider() {
        tantalum.value.state.CompactionSettings claude = properties("claude", 200_000, 131_072, TantalumProperties.Compaction.defaults()).compactionSettings();
        assertEquals(136_000, claude.threshold());
        assertEquals(64_000, claude.reserve(), "Claude's max-tokens is larger than reserve-tokens");
        assertEquals(20_000, claude.keepRecent());
        assertEquals(32_768, claude.maxKeptResultBytes());
        assertEquals(4_096, claude.maxSummarizedResultBytes());
        assertEquals(8_192, claude.summaryMaxTokens());

        tantalum.value.state.CompactionSettings openai = properties("openai", 200_000, 131_072, TantalumProperties.Compaction.defaults()).compactionSettings();
        assertEquals(114_688, openai.threshold());
        assertEquals(16_384, openai.reserve());

        assertNull(properties("openai", 200_000, 0, TantalumProperties.Compaction.defaults()).compactionSettings(), "unknown window: no compaction");
        assertEquals(136_000, properties("claude", 200_000, 0, null).compactionSettings().threshold(), "missing section means defaults");
        assertThrows(IllegalArgumentException.class, () -> properties("openai", 200_000, 16_000, TantalumProperties.Compaction.defaults()).compactionSettings(),
                "a window no larger than the reserve is a configuration error");
    }

    /** compaction-threshold T7：估算系数按供应商，非正数启动失败；预留同压缩设置。 */
    @Test
    void compactionLimitsCarryTheProvidersCoefficient() throws Exception {
        checkLimits(bind().compactionLimits(), 64_000, 1.5);
        checkLimits(bind("tantalum.provider", "openai").compactionLimits(), 16_384, 1.75);
        assertEquals(1.25, bind("tantalum.claude.bytes-per-token", "1.25").compactionLimits().bytesPerToken());
        assertThrows(BindException.class, () -> bind("tantalum.claude.bytes-per-token", "0"));
        assertThrows(BindException.class, () -> bind("tantalum.openai.bytes-per-token", "-1"));
        assertTrue(bind().windowFromModel());
        assertFalse(bind("tantalum.compaction.window-from-model", "false").windowFromModel());
    }

    private static void checkLimits(CompactionPolicy.Limits limits, long reserve, double bytesPerToken) {
        assertEquals(reserve, limits.reserve());
        assertEquals(20_000, limits.keepRecent());
        assertEquals(32_768, limits.maxKeptResultBytes());
        assertEquals(4_096, limits.maxSummarizedResultBytes());
        assertEquals(8_192, limits.summaryMaxTokens());
        assertEquals(bytesPerToken, limits.bytesPerToken());
    }

    @Test
    void limitsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new Loop(0, 1, 1, 1, 1024, 1024));
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 0, 1, 1, 1024, 1024));
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 1, 0, 1, 1024, 1024));
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 1, 1, 0, 1024, 1024));
    }
}
