package tantalum.local.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 隔离执行命令的地方（local-workspace 设计“Sandbox、Mxc”，L13）。保证不了隔离就不让跑：{@link #check()} 不可用时
 * {@code bash} 不出现，{@link #run} 也拒绝执行，不会退回到不隔离直接跑（照 DSH {@code SANDBOX_UNAVAILABLE}，
 * {@code sandbox/sandbox/src/index.ts:132-152}）。
 */
public interface Sandbox extends AutoCloseable {

    /** 能不能用；不能用时 {@code reason} 写明原因，给用户看。 */
    record Status(boolean available, String reason) {

        public static Status ok() {
            return new Status(true, null);
        }

        public static Status unavailable(String reason) {
            return new Status(false, Objects.requireNonNull(reason, "reason"));
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
    static Sandbox unavailable(String reason) {
        Status status = Status.unavailable(reason);
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
