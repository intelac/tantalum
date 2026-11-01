package tantalum.shell.boot;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 全部配置，默认值在 {@code application.properties}。 */
@ConfigurationProperties("tantalum")
public record TantalumProperties(Path dataDir, Path workspace, String workspaceMode, String systemPrompt, Duration toolTimeout,
                                 String provider, Loop loop, Claude claude, OpenAi openai, Memory memory, Knowledge knowledge) {

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

    private static void requirePositive(String name, int value) {
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

    /** Claude 设置。API Key 只从环境变量读取，不在这里。 */
    public record Claude(String model, long maxTokens, String effort, boolean fallbacks, Duration firstChunkTimeout,
                         Duration betweenChunkTimeout, int concurrency) {}

    /** OpenAI 兼容服务的设置。 */
    public record OpenAi(String baseUrl, String model, int maxTokens, Duration firstChunkTimeout,
                         Duration betweenChunkTimeout, int concurrency) {}
}
