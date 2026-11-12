package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** agent-eval 需求 1.2：命令输出交给模型前的清理（照 pi 的 sanitizeBinaryOutput 和 stripAnsi）。 */
class BashCleanTest {

    @Test
    void colorsAndCursorMovesAreRemoved() {
        assertEquals("red plain", Bash.clean("\u001B[31mred\u001B[0m plain"));
        assertEquals("bold", Bash.clean("\u001B[1;4mbold\u001B[22;24m"));
        assertEquals("title gone", Bash.clean("\u001B]0;window title\u0007title gone"));
        assertEquals("cursor", Bash.clean("\u001B[2K\u001B[1Gcursor"));
    }

    @Test
    void controlCharactersGoTabsAndNewlinesStay() {
        assertEquals("abc\tx\ny", Bash.clean("a\u0000b\u0001c\tx\ny\u007F"));
        assertEquals("caf�", Bash.clean("caf�"), "a replaced invalid byte stays visible");
    }

    @Test
    void aProgressBarKeepsWhatTheTerminalWouldShow() {
        assertEquals("100%", Bash.clean("10%\r50%\r100%"));
        assertEquals("start\n100%\ndone", Bash.clean("start\n10%\r100%\ndone"));
        assertEquals("line", Bash.clean("line\r"), "a trailing carriage return keeps the line");
        assertEquals("a\nb", Bash.clean("a\r\nb"), "CRLF is a newline");
    }

    @Test
    void ordinaryTextIsUntouched() {
        String text = "名字 带空格.txt\n  indented\n\nlast";
        assertEquals(text, Bash.clean(text));
    }
}
