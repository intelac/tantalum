package tantalum.shell.net;

import java.net.URI;
import tantalum.local.browser.PageUrl;

/**
 * 联网工具能打开的网址。规则在 {@link PageUrl}（和用户电脑上的浏览器共用一份）；{@code web_fetch} 的每一跳都过这里。
 * 错误消息直接交给模型。
 */
public final class WebUrl {

    public static final int MAX_LENGTH = PageUrl.MAX_LENGTH;

    private WebUrl() {}

    public static URI check(String url) {
        return PageUrl.check(url);
    }
}
