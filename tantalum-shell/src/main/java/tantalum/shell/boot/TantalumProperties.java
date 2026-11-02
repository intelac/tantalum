package tantalum.shell.boot;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import tantalum.value.state.CompactionSettings;

/** 全部配置，默认值在 {@code application.properties}。 */
@ConfigurationProperties("tantalum")
public record TantalumProperties(Path dataDir, Path workspace, String workspaceMode, String systemPrompt, Duration toolTimeout,
                                 String provider, Loop loop, Claude claude, OpenAi openai, Memory memory, Knowledge knowledge, Mcp mcp,
                                 Compaction compaction, Index index, Residency residency, Duration shutdownWait) {

    /** 关闭时等在途的模型和工具调用最多多久，到时中断它们（H8）。未配置时 30 秒。 */
    public Duration shutdownWaitOrDefault() {
        return shutdownWait == null ? Duration.ofSeconds(30) : shutdownWait;
    }

    /** 常驻会话。{@code idle} 为 null 时不按时间回收。 */
    public record Residency(Duration idle, int maxResident, int snapshotEvents, Duration sweepInterval) {

        public Residency {
            requirePositive("maxResident", maxResident);
            requirePositive("snapshotEvents", snapshotEvents);
        }

        /** 空闲 30 分钟或超过 1000 个常驻时回收（H3），运行结束距上次快照超过 200 个事件时写快照。 */
        public static Residency defaults() {
            return new Residency(Duration.ofMinutes(30), 1000, 200, Duration.ofSeconds(1));
        }
    }


    /** 索引库的节流：攒够事件数或到时间就写。运行结束总是写。 */
    public record Index(int flushEvents, Duration flushInterval) {

        public Index {
            requirePositive("flushEvents", flushEvents);
            requirePositive("flushInterval", flushInterval.toMillis());
        }

        public static Index defaults() {
            return new Index(200, Duration.ofSeconds(5));
        }
    }

    /** 上下文压缩。窗口在各供应商下配置；这里是预留、保留量、工具结果上限和摘要长度。 */
    public record Compaction(long reserveTokens, long keepRecentTokens, int maxKeptToolResultBytes,
                             int maxSummarizedToolResultBytes, long summaryMaxTokens) {

        public Compaction {
            requirePositive("reserveTokens", reserveTokens);
            requirePositive("keepRecentTokens", keepRecentTokens);
            requirePositive("maxKeptToolResultBytes", maxKeptToolResultBytes);
            requirePositive("maxSummarizedToolResultBytes", maxSummarizedToolResultBytes);
            requirePositive("summaryMaxTokens", summaryMaxTokens);
        }

        public static Compaction defaults() {
            return new Compaction(16384, 20000, 32 * 1024, 4 * 1024, 8192);
        }
    }

    /** MCP 服务器，只由管理员配置。没配时可以整个为 null。校验在 {@code McpSettings}。 */
    public record Mcp(java.util.Map<String, McpServer> servers, Duration startupWait) {}

    /** 一个远程 MCP 服务器。凭据只从 {@code tokenEnv} 指向的环境变量读取；数字和时长为空时用默认值。 */
    public record McpServer(String url, String auth, String tokenEnv, java.util.List<String> allow, java.util.List<String> deny,
                            Integer concurrency, Duration timeout, Integer maxResultBytes, Integer maxTools) {}

    /** 每用户长期记忆的上限：条目数、索引总字节、单条正文字节。超限时写入被拒，不自动整理。 */
    public record Memory(int maxEntries, int maxIndexBytes, int maxEntryBytes) {

        public Memory {
            requirePositive("maxEntries", maxEntries);
            requirePositive("maxIndexBytes", maxIndexBytes);
            requirePositive("maxEntryBytes", maxEntryBytes);
        }

        public static Memory defaults() {
            return new Memory(200, 16 * 1024, 8 * 1024);
        }
    }

    /** 共享知识层：管理员维护的只读目录，里面的 Markdown 进所有用户的 system。{@code dir} 为空表示没有这一层。 */
    public record Knowledge(String dir, int maxBytes) {

        public Knowledge {
            requirePositive("maxBytes", maxBytes);
        }

        public static Knowledge none() {
            return new Knowledge("", 64 * 1024);
        }

        public java.util.Optional<Path> path() {
            return dir == null || dir.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(Path.of(dir));
        }
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }

    /** 环的容量。约束见 design 的“容量”一节，不满足时启动失败。 */
    public record Loop(int maxActiveRuns, int maxParallelTools, int maxUserInFlight, int maxExternalInFlight,
                       int inputRingSize, int outputRingSize) {

        public Loop {
            requirePositive("maxActiveRuns", maxActiveRuns);
            requirePositive("maxParallelTools", maxParallelTools);
            requirePositive("maxUserInFlight", maxUserInFlight);
            requirePositive("maxExternalInFlight", maxExternalInFlight);
            requirePowerOfTwo("inputRingSize", inputRingSize);
            requirePowerOfTwo("outputRingSize", outputRingSize);
            long commands = (long) maxActiveRuns * (maxParallelTools + 1);
            if (outputRingSize < commands) {
                throw new IllegalArgumentException("outputRingSize " + outputRingSize + " must be at least " + commands);
            }
            long input = (long) maxActiveRuns * maxParallelTools + maxExternalInFlight;
            if (inputRingSize < input) {
                throw new IllegalArgumentException("inputRingSize " + inputRingSize + " must be at least " + input);
            }
        }

        public static Loop defaults() {
            return new Loop(64, 10, 8, 256, 1024, 1024);
        }

        /** 留给活跃运行结果的 Input Ring 容量。 */
        public int internalReserve() {
            return maxActiveRuns * maxParallelTools;
        }

        private static void requirePowerOfTwo(String name, int value) {
            if (Integer.bitCount(value) != 1) {
                throw new IllegalArgumentException(name + " must be a power of two but was " + value);
            }
        }
    }

    /** Claude 设置。API Key 只从环境变量读取，不在这里。{@code contextWindow} 为 0 表示未知：不压缩，控制台不显示占比。 */
    public record Claude(String model, long maxTokens, String effort, boolean fallbacks, Duration firstChunkTimeout,
                         Duration betweenChunkTimeout, int concurrency, long contextWindow) {}

    /** OpenAI 兼容服务的设置。 */
    public record OpenAi(String baseUrl, String model, int maxTokens, Duration firstChunkTimeout,
                         Duration betweenChunkTimeout, int concurrency, long contextWindow) {}

    /** 当前供应商的压缩设置快照；窗口未配置时为 null，表示不压缩。预留取配置和该供应商输出上限中的大者。 */
    public CompactionSettings compactionSettings() {
        boolean claude = "claude".equals(provider);
        long window = claude ? claude().contextWindow() : openai().contextWindow();
        long maxTokens = claude ? claude().maxTokens() : openai().maxTokens();
        if (window <= 0) {
            return null;
        }
        Compaction c = compaction == null ? Compaction.defaults() : compaction;
        return new CompactionSettings(window, Math.max(c.reserveTokens(), maxTokens), c.keepRecentTokens(),
                c.maxKeptToolResultBytes(), c.maxSummarizedToolResultBytes(), c.summaryMaxTokens());
    }
}
