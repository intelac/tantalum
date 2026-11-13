package tantalum.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** jar 的入口按 Java 版本放行（connector-jar 需求 1.3）。真的旧 Java 这台机器和 CI 上都没有，只测版本的读法。 */
class LauncherTest {

    @Test
    void readsBothOldAndNewVersionStrings() {
        assertEquals(8, Launcher.feature("1.8"));
        assertEquals(11, Launcher.feature("11"));
        assertEquals(25, Launcher.feature("25"));
        assertEquals(26, Launcher.feature("26.0.1"));
        assertEquals(0, Launcher.feature("weird"));
    }
}
