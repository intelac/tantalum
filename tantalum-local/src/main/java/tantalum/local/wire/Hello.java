package tantalum.local.wire;

import java.util.List;
import java.util.Objects;

/**
 * 电脑→服务器，连上后的第一条：这台电脑是什么、允许了什么。允许的范围只由连接程序的启动参数决定，服务器据此决定给模型哪些工具，
 * 但每个调用到了电脑上还会再查一遍（L4）。协议里没有反方向修改这些的消息。
 *
 * @param protocol 协议版本，见 {@link Wire#PROTOCOL}
 * @param connector 连接程序的版本
 * @param roots 允许的目录，第一个是主目录
 * @param write 有没有 {@code --allow-write}
 * @param shell 命令行能不能用，不能用时写明原因
 * @param network 命令能不能联网（{@code --no-network} 时为 false）
 * @param git 这台电脑上能不能跑 {@code git}。Git 工具不看目录是不是仓库：每次调用按它的路径找仓库，不在仓库里就报“不是 Git 仓库”
 *            （协议 2 起；协议 1 按主目录是不是仓库决定有没有 Git 工具，见 {@link Wire#PROTOCOL}）
 */
public record Hello(int protocol, String connector, Device device, List<Root> roots, boolean write, Shell shell, boolean network,
                    boolean git) implements Message {

    public Hello {
        Objects.requireNonNull(connector, "connector");
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(shell, "shell");
        roots = roots == null ? List.of() : List.copyOf(roots);
    }

    /** 电脑名（默认主机名）、系统、版本、芯片架构。 */
    public record Device(String name, String os, String osVersion, String arch) {}

    /** 一个允许的目录（真实路径）。 */
    public record Root(String path) {

        public Root {
            Objects.requireNonNull(path, "path");
        }
    }

    /** {@code reason} 只在不能用时有，如 “--allow-shell was not given”。 */
    public record Shell(boolean available, String reason) {}
}
