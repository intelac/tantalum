package tantalum.connect.browser;

import java.nio.file.Path;
import java.util.List;
import tantalum.connect.mcp.McpConfig;

/**
 * 启动时定下的浏览器（connector-jar 需求 7.1）：找到的 Chrome 或 Edge、有没有窗口、资料目录、电脑上的 {@code npx}。
 * 由它生成本机 MCP 里内置的 {@code playwright} 一项。
 */
public record BrowserSettings(Path executable, String name, boolean headless, Path profile, Path npx) {

    /** Playwright 的浏览器名：Edge 是 {@code msedge}，其余按 Chrome。 */
    public String channel() {
        return name.contains("Edge") ? "msedge" : "chrome";
    }

    /** 内置的 {@code playwright} 一项；临时文件（Playwright MCP 写快照、截图的地方）放在连接程序自己的目录里。 */
    public McpConfig.Entry entry(Path output) {
        List<String> args = PlaywrightBrowser.args(channel(), executable, profile, output, headless);
        return new McpConfig.Entry(PlaywrightBrowser.SERVER, npx.toString(), args, java.util.Map.of(), null, false);
    }
}
