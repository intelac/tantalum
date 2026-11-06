package tantalum.shell.browser;

import com.microsoft.playwright.PlaywrightException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.shell.access.WebAccess;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/**
 * 一个浏览器工具（设计“{@code BrowserTool}”）：名字、说明、参数加一个 {@link Action}。执行时向 {@link Browsers} 要这个用户的浏览器，
 * 持锁执行。浏览器有状态，同一轮里的几个调用按顺序执行（{@code isConcurrencySafe} 用默认的 {@code false}）。
 * <p>名额：这里的 64 只是全体用户合起来的上限；每人串行、开几个浏览器，由 {@link Browsers} 管（同 {@code LocalTool} 的做法）。
 * 错误只给 Playwright 消息的第一行，不给调用日志；快照和截图不进日志。
 */
final class BrowserTool implements Tool {

    /** 插件页“浏览器”子开关和并发名额用的服务端名。 */
    static final String SERVER = WebAccess.BROWSER;
    static final int SERVER_CONCURRENCY = 64;
    private static final Pattern TIMEOUT = Pattern.compile("Timeout (\\d+)ms exceeded");

    /** 一次调用：这个用户的浏览器（已加锁）、会话、参数。 */
    record Call(UserBrowser browser, SessionId session, Principal user, Json arguments, Browsers browsers) {}

    @FunctionalInterface
    interface Action {
        ToolOutput run(Call call) throws Exception;
    }

    /** 拿浏览器之前的检查（网址规则、地址），不合格时抛 {@link IllegalArgumentException}，不为它起浏览器。 */
    @FunctionalInterface
    interface Check {
        void check(Json arguments) throws IllegalArgumentException;
    }

    private final String name;
    private final String description;
    private final Json schema;
    private final Browsers browsers;
    private final Check check;
    private final Action action;

    BrowserTool(String name, String description, Json schema, Browsers browsers, Check check, Action action) {
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.browsers = browsers;
        this.check = check;
        this.action = action;
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public Json schema() { return schema; }
    @Override public String server() { return SERVER; }
    @Override public int serverConcurrency() { return SERVER_CONCURRENCY; }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) throws Exception {
        try {
            check.check(arguments);
            return browsers.use(context.principal(), context.session(), (browser, session) -> {
                ToolOutput output = action.run(new Call(browser, session, context.principal(), arguments, browsers));
                browser.trimTabs(session);
                return output;
            });
        } catch (IllegalArgumentException invalid) {
            return ToolOutput.error(invalid.getMessage());
        } catch (Browsers.Unavailable unavailable) {
            return ToolOutput.error(unavailable.getMessage());
        } catch (PlaywrightException failure) {
            return ToolOutput.error(describe(failure));
        }
    }

    /** Playwright 的错误换成给模型的话（设计“{@code BrowserTool}”的错误写法），其余只给第一行。 */
    static String describe(PlaywrightException failure) {
        String line = UserBrowser.firstLine(failure.getMessage());
        Matcher timeout = TIMEOUT.matcher(line);
        if (timeout.find()) {
            long seconds = Math.max(1, Long.parseLong(timeout.group(1)) / 1000);
            return "Timed out after " + seconds + " s waiting for the page.";
        }
        if (line.contains("ERR_TUNNEL_CONNECTION_FAILED")) {
            return "The page or one of its redirects was blocked by this server's network rules, or the site could not be reached.";
        }
        if (line.contains("Download is starting")) {
            return "This URL starts a file download; downloads are not allowed.";
        }
        return line.isEmpty() ? "The browser reported an error without a message." : line;
    }
}
