package tantalum.local.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 隔离执行命令的地方。保证不了隔离就不让跑：{@link #check()} 不可用时
 * {@code bash} 不出现，{@link #run} 也拒绝执行，不会退回到不隔离直接跑（照 DSH {@code SANDBOX_UNAVAILABLE}，
 * {@code sandbox/sandbox/src/index.ts:132-152}）。
 */
public interface Sandbox extends AutoCloseable {

    /** 能不能用；不能用时 {@code why} 是原因的代码和参数。 */
    record Status(boolean available, Unavailable why) {

        public static Status ok() {
            return new Status(true, null);
        }

        public static Status unavailable(Unavailable why) {
            return new Status(false, Objects.requireNonNull(why, "why"));
        }

        /** 原因的英文，给模型和老版本服务器；能用时为 null。 */
        public String reason() {
            return why == null ? null : why.english();
        }
    }

    /**
     * 一次命令的结果。{@code output} 是标准输出和错误输出合在一起的末尾一段（见 {@link Tail}）。超时时 {@code exitCode} 没有意义。
     */
    record Run(int exitCode, Tail.Text output, boolean timedOut) {}

    /** 检查能不能用。结果可以缓存，启动时调一次。 */
    Status check();

    /**
     * 在沙箱里用 bash 跑 {@code command}，工作目录 {@code workdir}（调用方已确认在允许的目录里）。超时由沙箱结束整个进程树；
     * 线程被打断时结束整个进程树并抛 {@link InterruptedException}。不可用时抛 {@link IllegalStateException}。
     */
    Run run(String command, Path workdir, Duration timeout) throws IOException, InterruptedException;

    /** 连接程序退出时调用：删掉沙箱用的临时文件。 */
    @Override
    default void close() {}

    /** 不能用的沙箱：没有 {@code --allow-shell}、找不到 MXC 等。 */
    static Sandbox unavailable(Unavailable why) {
        Status status = Status.unavailable(why);
        String reason = why.english();
        return new Sandbox() {
            @Override
            public Status check() {
                return status;
            }

            @Override
            public Run run(String command, Path workdir, Duration timeout) {
                throw new IllegalStateException("The command sandbox is not available: " + reason);
            }
        };
    }
}
