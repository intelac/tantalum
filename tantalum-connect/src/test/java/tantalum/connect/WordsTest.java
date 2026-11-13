package tantalum.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.shell.Unavailable;
import tantalum.value.text.BundleChecks;
import tantalum.value.text.Language;

/** console-i18n I12、I14、需求 5.1–5.3、5.5：连接程序终端里的三种语言。 */
class WordsTest {

    @TempDir
    Path temp;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    private String run(Map<String, String> env, String... args) {
        bytes.reset();
        Main.run(args, env, question -> null, out);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void everyKeyHasThreeLanguagesWithTheSamePlaceholders() {
        BundleChecks.assertComplete(Words.BUNDLE, Words.Key.class);
    }

    /** 需求 5.2：给了 --lang 就用它；没给时看 LANG 这类环境变量。 */
    @Test
    void helpFollowsLangThenTheEnvironment() {
        String japanese = run(Map.of(), "--lang", "ja", "--help");
        assertTrue(japanese.startsWith("使い方：tantalum-connect"), japanese);
        assertTrue(japanese.contains("--lang"), "help lists --lang");
        assertEquals(japanese, run(Map.of("LANG", "ja_JP.UTF-8"), "--help"));
        assertTrue(run(Map.of("LANG", "ja_JP.UTF-8"), "--help", "--lang", "en").startsWith("Usage: tantalum-connect"));
        assertTrue(run(Map.of("LC_ALL", "zh_CN.UTF-8", "LANG", "ja_JP.UTF-8"), "--help").startsWith("用法：tantalum-connect"));
    }

    /** 需求 5.3：--lang 的值不对时用英文说可选的值，退出码 1。 */
    @Test
    void anUnknownLangIsRefusedInEnglish() {
        bytes.reset();
        assertEquals(Main.REFUSED, Main.run(new String[] {"--lang", "fr", "--help"}, Map.of("LANG", "ja_JP.UTF-8"), question -> null, out));
        assertEquals("--lang must be zh, en or ja", bytes.toString(StandardCharsets.UTF_8).strip());
        bytes.reset();
        assertEquals(Main.REFUSED, Main.run(new String[] {"--help", "--lang"}, Map.of(), question -> null, out));
    }

    /** 需求 5.1：没有令牌时的报错，三种语言各一遍；令牌名照原样。 */
    @Test
    void theMissingTokenMessageInEachLanguage() throws Exception {
        Path work = Files.createDirectory(temp.toRealPath().resolve("work"));
        String dir = work.toString();
        assertEquals("没有令牌。请把网页上生成的令牌放进环境变量 TANTALUM_TOKEN 再启动。", run(Map.of(), "--allow", dir, "--lang", "zh").strip());
        assertEquals("No token. Put the token from the web page in the TANTALUM_TOKEN environment variable and start again.",
                run(Map.of(), "--allow", dir, "--lang", "en").strip());
        assertEquals("トークンがありません。Web ページで発行したトークンを環境変数 TANTALUM_TOKEN に入れてから起動してください。",
                run(Map.of(), "--allow", dir, "--lang", "ja").strip());
    }

    /** 命令用不了的原因按终端的语言说；给模型的英文不受影响。 */
    @Test
    void shellReasonsInTheTerminalLanguage() {
        Unavailable missing = Unavailable.of(Unavailable.Code.NOT_FOUND, "shell", "bash");
        assertEquals("このコンピューターに bash が見つかりません", new Words(Language.JA).shell(missing));
        assertEquals("这台电脑上找不到 bash", new Words(Language.ZH).shell(missing));
        assertEquals("cannot find bash on this computer", missing.english());
        for (Unavailable.Code code : Unavailable.Code.values()) {
            String said = new Words(Language.JA).shell(Unavailable.of(code, "shell", "bash"));
            assertFalse(said.contains("{"), said);
        }
    }
}
