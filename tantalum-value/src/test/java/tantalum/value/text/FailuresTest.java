package tantalum.value.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** review-fixes 需求 6.1：异常文字只有类名，消息里的路径和密钥不会跟着进 journal。 */
class FailuresTest {

    @Test
    void onlyTheClassNameIsKept() {
        RuntimeException failure = new IllegalStateException("token tc_secret at /Users/yi/.ssh/id_rsa");
        assertEquals("IllegalStateException", Failures.describe(failure));
        assertEquals("hook failed: IllegalStateException", Failures.describe(failure, "hook failed"));
        assertFalse(Failures.describe(failure, "x").contains("tc_secret"));
    }
}
