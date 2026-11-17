package tantalum.connect.browser;

import static tantalum.connect.browser.PlaywrightBrowser.*;
import tantalum.connect.browser.PlaywrightBrowser.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class PlaywrightBrowserFixture {

    private PlaywrightBrowserFixture() {}

    public static PlaywrightBrowser of(Tools mcp) {
        return new PlaywrightBrowser(mcp, null);
    }
}
