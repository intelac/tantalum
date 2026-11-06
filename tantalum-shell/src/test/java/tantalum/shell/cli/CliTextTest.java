package tantalum.shell.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.shell.store.RecordCheck;
import tantalum.value.text.BundleChecks;
import tantalum.value.text.Language;

/** console-i18n I13、I14、需求 5.4–5.6：命令行子命令的三种语言。 */
class CliTextTest {

    @Test
    void everyKeyHasThreeLanguagesWithTheSamePlaceholders() {
        BundleChecks.assertComplete(CliText.BUNDLE, CliText.Key.class);
    }

    @Test
    void langIsTakenOutOfTheArgumentsAndWinsOverTheEnvironment() {
        CliText.Parsed parsed = CliText.parse(new String[] {"add", "--lang", "ja", "alice"}, Map.of("LANG", "zh_CN.UTF-8"), Locale.US).orElseThrow();
        assertArrayEquals(new String[] {"add", "alice"}, parsed.args());
        assertEquals(Language.JA, parsed.text().language());
        assertEquals(Language.ZH, CliText.parse(new String[] {"add", "alice"}, Map.of("LANG", "zh_CN.UTF-8"), Locale.US).orElseThrow().text().language());
        assertTrue(CliText.parse(new String[] {"--lang", "fr"}, Map.of(), Locale.US).isEmpty(), "a bad --lang is refused");
    }

    /** check 的结果行三种语言各一遍；不一致的说明本身是给人查的固定写法，原样。 */
    @Test
    void checkPrintsInTheChosenLanguage() {
        RecordCheck.Report report = new RecordCheck.Report(3, List.of("yi/s1: seq 4 status"));
        assertEquals(List.of("DIFFERENT yi/s1: seq 4 status", "1 of 3 sessions differ from their recorded results"), printed(report, Language.EN));
        assertEquals(List.of("不一致 yi/s1: seq 4 status", "3 个会话里有 1 个和记录的结果不一致"), printed(report, Language.ZH));
        assertEquals(List.of("不一致 yi/s1: seq 4 status", "3 件のセッションのうち 1 件が記録と一致しません"), printed(report, Language.JA));
        assertEquals(List.of("全部 2 个会话重放的结果和记录的一致"), printed(new RecordCheck.Report(2, List.of()), Language.ZH));
    }

    /** 需求 5.6：服务器日志不翻译，一律英文：日志语句里没有中日文字符。 */
    @Test
    void logsStayEnglish() throws Exception {
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : java.nio.file.Files.readAllLines(file)) {
                    assertTrue(!line.matches(".*\\bLOG\\.(info|warn|error|debug)\\(\".*[\\u3040-\\u30ff\\u3400-\\u9fff].*"), file + ": " + line.strip());
                }
            }
        }
    }

    private static List<String> printed(RecordCheck.Report report, Language language) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RecordCheck.print(report, new PrintStream(out, true, StandardCharsets.UTF_8), CliText.of(language));
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }
}
