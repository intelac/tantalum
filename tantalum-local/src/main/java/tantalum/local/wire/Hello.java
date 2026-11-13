package tantalum.local.wire;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 电脑→服务器，连上后的第一条：这台电脑是什么、允许了什么。允许的范围只由连接程序的启动参数决定，服务器据此决定给模型哪些工具，
 * 但每个调用到了电脑上还会再查一遍。协议里没有反方向修改这些的消息。
 *
 * @param protocol 协议版本，见 {@link Wire#PROTOCOL}
 * @param connector 连接程序的版本
 * @param roots 允许的目录，第一个是主目录
 * @param write 有没有 {@code --allow-write}
 * @param shell 命令行能不能用，不能用时写明原因
 * @param network 命令能不能联网。协议 6 起总是 true（没有沙箱了，拦不住）；协议 5 及以前 {@code --no-network} 时为 false
 * @param git 这台电脑上能不能跑 {@code git}。Git 工具不看目录是不是仓库：每次调用按它的路径找仓库，不在仓库里就报“不是 Git 仓库”
 *            （协议 2 起；协议 1 按主目录是不是仓库决定有没有 Git 工具，见 {@link Wire#PROTOCOL}）
 * @param browser 这台电脑上的浏览器能不能用（协议 5 起）；老的连接程序不送，为 null，不写出
 * @param jarSha256 连接程序 jar 的 SHA-256（十六进制小写，协议 6 起）；从源码运行时为 null。服务器只拿它提示“不是这里提供的版本”
 * @param mcp 这台电脑上的本机 MCP 服务器（协议 6 起），刚启动时多半还在 {@code starting}，好了以后经 {@link McpTools} 补报
 */
public record Hello(int protocol, String connector, Device device, List<Root> roots, boolean write, Shell shell, boolean network,
                    boolean git, @JsonInclude(JsonInclude.Include.NON_NULL) Browser browser,
                    @JsonInclude(JsonInclude.Include.NON_NULL) String jarSha256,
                    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<McpServerState> mcp) implements Message {

    /** 协议 5 及以前：没有 jar 的哈希和本机 MCP。 */
    public Hello(int protocol, String connector, Device device, List<Root> roots, boolean write, Shell shell, boolean network,
                 boolean git, Browser browser) {
        this(protocol, connector, device, roots, write, shell, network, git, browser, null, List.of());
    }

    /** 没有浏览器这一项的连接程序（协议 4 及以前）。 */
    public Hello(int protocol, String connector, Device device, List<Root> roots, boolean write, Shell shell, boolean network,
                 boolean git) {
        this(protocol, connector, device, roots, write, shell, network, git, null);
    }

    public Hello {
        Objects.requireNonNull(connector, "connector");
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(shell, "shell");
        roots = roots == null ? List.of() : List.copyOf(roots);
        mcp = mcp == null ? List.of() : List.copyOf(mcp);
    }

    /** 同一份，换掉本机 MCP 服务器的清单（服务器收到 {@link McpTools} 时用）。 */
    public Hello withMcp(List<McpServerState> servers) {
        return new Hello(protocol, connector, device, roots, write, shell, network, git, browser, jarSha256, servers);
    }

    /** 电脑名（默认主机名）、系统、版本、芯片架构。 */
    public record Device(String name, String os, String osVersion, String arch) {}

    /** 一个允许的目录（真实路径）。 */
    public record Root(String path) {

        public Root {
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * {@code reason} 只在不能用时有，是英文诊断，如 “--allow-shell was not given”。{@code code}、{@code params} 是同一个原因的代码和参数
     * （如 {@code sandbox/start-failed}、{@code {"detail": …}}），控制台按看的人的语言说；老版本连接程序不送，为 null。
     * 新字段是可选的，协议版本不变；没有时不写出。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Shell(boolean available, String reason, String code, Map<String, String> params, String kind, String path) {

        public Shell(boolean available, String reason) {
            this(available, reason, null, null, null, null);
        }

        public Shell(boolean available, String reason, String code, Map<String, String> params) {
            this(available, reason, code, params, null, null);
        }

        /** {@code kind} 是 {@code bash} 或 {@code powershell}（协议 6 起），{@code path} 是找到的程序；老的连接程序不送，为 null。 */
        public static Shell of(tantalum.local.shell.Shell shell) {
            tantalum.local.shell.Shell.Status status = shell.check();
            String kind = shell.kind().wire();
            return status.available()
                    ? new Shell(true, null, null, null, kind, shell.program() == null ? null : shell.program().toString())
                    : new Shell(false, status.reason(), status.why().code().wire(), status.why().params(), kind, null);
        }
    }

    /**
     * 浏览器的情况。可用时 {@code name} 是找到的浏览器（“Google Chrome”“Microsoft Edge”，或 {@code --browser-path} 的文件名），
     * {@code headless} 说明有没有窗口；不可用时 {@code code} 写明原因，控制台按看的人的语言说。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Browser(boolean available, String code, Map<String, String> params, String name, boolean headless) {

        /** 没有 {@code --allow-browser}。 */
        public static final String NOT_ALLOWED = "browser/not-allowed";
        /** 没有找到 Chrome 或 Edge。 */
        public static final String NOT_FOUND = "browser/not-found";
        /** 没有找到 Node.js（{@code npx}）：浏览器由本机的 Playwright MCP 驱动，要它（协议 6 起）。 */
        public static final String NODE_MISSING = "browser/node-missing";

        public static Browser available(String name, boolean headless) {
            return new Browser(true, null, null, name, headless);
        }

        public static Browser unavailable(String code) {
            return new Browser(false, code, null, null, false);
        }
    }
}
