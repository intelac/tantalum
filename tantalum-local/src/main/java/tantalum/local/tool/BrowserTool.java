package tantalum.local.tool;

import java.time.Duration;
import tantalum.value.json.Json;

/**
 * 一个浏览器工具的定义：名字、说明、参数、参数检查。执行交给这台电脑上的 {@link Browser}；参数不合格时直接回错误，
 * 不为它启动浏览器。浏览器有状态，同一轮里的几个调用按顺序执行（{@code isConcurrencySafe} 用默认的 false）。
 */
public final class BrowserTool implements LocalTool {

    /** 交给浏览器之前的检查，不合格时抛 {@link IllegalArgumentException}，消息直接给模型。 */
    @FunctionalInterface
    interface Check {
        void check(Json arguments);
    }

    static final Duration MIN_TIMEOUT = Duration.ofSeconds(100);

    private final String name;
    private final String description;
    private final Json schema;
    private final Check check;

    BrowserTool(String name, String description, Json schema, Check check) {
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.check = check;
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public Json schema() { return schema; }
    @Override public Kind kind() { return Kind.BROWSER; }

    /**
     * 浏览器的一步可能很慢：第一次调用要先起浏览器（最多 30 秒），打开网页最多等 30 秒，{@code browser_wait_for} 最多能等 60 秒。
     * 服务器的标准超时比这短时用 {@link #MIN_TIMEOUT}，免得浏览器还在正常等，服务器先放弃了。
     */
    @Override
    public Duration timeout(Json arguments, Duration standard) {
        return standard.compareTo(MIN_TIMEOUT) < 0 ? MIN_TIMEOUT : standard;
    }

    @Override
    public LocalResult run(LocalContext context, Json arguments) throws Exception {
        try {
            check.check(arguments);
        } catch (IllegalArgumentException invalid) {
            return LocalResult.error(invalid.getMessage());
        }
        return context.browser().call(context.session(), name, arguments);
    }
}
