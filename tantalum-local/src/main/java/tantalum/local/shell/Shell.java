package tantalum.local.shell;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 在用户电脑上跑命令的地方：macOS、Linux 上是 bash，Windows 上是 PowerShell（{@link ShellKind}）。没有操作系统沙箱：命令以运行
 * 连接程序的账号身份执行，能碰这个账号能碰的一切；把关的是服务器上的命令审批（connector-jar 需求 4.6、5）。
 * 没有 {@code --allow-shell}、或者电脑上找不到 shell 时不可用：{@code bash}/{@code powershell} 不出现，{@link #run} 也拒绝执行。
 */
public interface Shell extends AutoCloseable {

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
     * 一次命令的结果。{@code output} 是标准输出和错误输出合在一起，太长时留头留尾（见 {@link Capture}）。超时时 {@code exitCode} 没有意义。
     */
    record Run(int exitCode, Tail.Text output, boolean timedOut) {}

    /** 检查能不能用。结果可以缓存，启动时调一次。 */
    Status check();

    /** 是哪种 shell；不可用时也有（按系统定）。 */
    ShellKind kind();

    /** 找到的 shell 程序；不可用时为 null。 */
    default Path program() {
        return null;
    }

    /**
     * 跑 {@code command}，工作目录 {@code workdir}（调用方已确认在允许的目录里）。到 {@code timeout} 结束整个进程树；
     * 线程被打断时结束整个进程树并抛 {@link InterruptedException}。不可用时抛 {@link IllegalStateException}。
     */
    Run run(String command, Path workdir, Duration timeout) throws IOException, InterruptedException;

    /** 连接程序退出时调用。 */
    @Override
    default void close() {}

    /** 不能用的：没有 {@code --allow-shell}、找不到 shell。 */
    static Shell unavailable(Unavailable why) {
        return unavailable(ShellKind.current(), why);
    }

    static Shell unavailable(ShellKind kind, Unavailable why) {
        Status status = Status.unavailable(why);
        String reason = why.english();
        return new Shell() {
            @Override
            public Status check() {
                return status;
            }

            @Override
            public ShellKind kind() {
                return kind;
            }

            @Override
            public Run run(String command, Path workdir, Duration timeout) {
                throw new IllegalStateException("Commands are not available: " + reason);
            }
        };
    }
}
