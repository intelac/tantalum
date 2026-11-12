package tantalum.local.tool;

import tantalum.value.json.Json;

/**
 * 这台电脑上的浏览器。{@code browser_*} 工具查完参数后把调用交到这里；真的实现在连接程序里（Playwright），
 * 这个模块不依赖 Playwright，服务器只用工具的定义。
 */
@FunctionalInterface
public interface Browser {

    String NOT_ALLOWED_TEXT = "This computer does not allow the browser.";

    /** 没有 {@code --allow-browser}，或者这次调用根本没给浏览器（测试）。 */
    Browser NOT_ALLOWED = (session, tool, arguments) -> LocalResult.error(NOT_ALLOWED_TEXT);

    /**
     * 执行一个浏览器工具。{@code session} 决定用哪个“当前标签页”；参数已经查过格式。要响应中断；错误也作为结果返回，
     * 抛异常等同于错误结果。
     */
    LocalResult call(String session, String tool, Json arguments) throws Exception;
}
