package tantalum.connect.browser;

import java.util.List;
import java.util.Objects;

/** 怎么启动 Playwright MCP：程序（电脑上的 {@code npx}）和参数（{@link BrowserSettings#command}）。 */
public record ServerCommand(String command, List<String> args) {

    public ServerCommand {
        Objects.requireNonNull(command, "command");
        args = List.copyOf(args);
    }
}
