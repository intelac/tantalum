package tantalum.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import tantalum.connect.Link.Ended;
import tantalum.local.wire.Bye;
import tantalum.local.wire.Wire;

/** local-workspace 需求 1.3、1.4、1.6：哪些结束不再重连，终端里怎么说。真连接见 {@code LinkIT}。 */
class LinkTest {

    @Test
    void theServerEndsTheLinkForTheseReasons() {
        assertEquals("令牌无效。请在网页的插件页重新生成一个令牌。", Link.fatal(new Ended(Wire.CLOSE_BAD_TOKEN, "invalid token", null)));
        assertEquals("令牌已到期。请在网页的插件页重新生成一个令牌。", Link.fatal(new Ended(Wire.CLOSE_EXPIRED, "expired", null)));
        assertEquals("已在网页上撤销，连接已断开。", Link.fatal(new Ended(Wire.CLOSE_EXPIRED, "revoked", null)));
        assertEquals("已在网页上撤销，连接已断开。", Link.fatal(new Ended(Wire.CLOSE_EXPIRED, "", Bye.Reason.REVOKED)));
        assertEquals("已被另一台电脑的连接替换，这里断开。", Link.fatal(new Ended(Wire.CLOSE_REPLACED, "replaced", Bye.Reason.REPLACED)));
        assertEquals("服务器版本不同，请下载新的连接程序。", Link.fatal(new Ended(Wire.CLOSE_PROTOCOL, "protocol 1 required", null)));
    }

    /** 收到了 {@code bye} 但关闭码丢了（网络先断了）：照 {@code bye} 说的退出，不拿着失效的令牌一直重连。 */
    @Test
    void aByeWithoutItsCloseCodeStillEndsTheLink() {
        assertEquals("令牌已到期。请在网页的插件页重新生成一个令牌。", Link.fatal(new Ended(1006, "", Bye.Reason.EXPIRED)));
        assertEquals("已在网页上撤销，连接已断开。", Link.fatal(new Ended(1006, "", Bye.Reason.REVOKED)));
        assertEquals("已被另一台电脑的连接替换，这里断开。", Link.fatal(new Ended(1006, "", Bye.Reason.REPLACED)));
    }

    /** 需求 1.5：网络断了、服务器关机、心跳超时都重连。 */
    @Test
    void everythingElseReconnects() {
        assertNull(Link.fatal(new Ended(1006, "IOException", null)));
        assertNull(Link.fatal(new Ended(1001, "shutdown", Bye.Reason.SHUTDOWN)));
        assertNull(Link.fatal(new Ended(1001, "no pong", null)));
        assertNull(Link.fatal(new Ended(1008, "no hello", null)));
    }
}
