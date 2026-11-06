package tantalum.shell.browser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;
import tantalum.shell.cli.CliText;

/**
 * 命令行 {@code browser install [--with-deps]}（web-tools 设计“{@code BrowserDriver}”）：不启动 Spring，解压驱动，装 Chromium headless shell。
 * {@code --with-deps} 在 Linux 上同时装浏览器要的系统库（要 root）。
 */
public final class BrowserCommand {

    private BrowserCommand() {}

    /** 返回进程退出码：参数不对是 2。 */
    public static int run(String[] args, Path dataDir, PrintStream out, CliText text) throws IOException, InterruptedException {
        Optional<Boolean> withDeps = parse(args);
        if (withDeps.isEmpty()) {
            out.println(text.say(CliText.Key.BROWSER_USAGE));
            return 2;
        }
        return new BrowserDriver(dataDir).install(withDeps.get(), out, text);
    }

    /** {@code install} 加可选的 {@code --with-deps}；别的都不认，返回空。 */
    static Optional<Boolean> parse(String[] args) {
        if (args.length == 1 && args[0].equals("install")) {
            return Optional.of(false);
        }
        if (args.length == 2 && args[0].equals("install") && args[1].equals("--with-deps")) {
            return Optional.of(true);
        }
        return Optional.empty();
    }
}
