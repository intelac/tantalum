package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.boot.DataDirCheck.Finding;
import tantalum.shell.boot.DataDirCheck.Problem;
import tantalum.shell.boot.DataDirCheck.Result;
import tantalum.shell.cli.CliText;
import tantalum.value.text.Language;

/** windows-server 需求 2.2–2.4：网络共享拒绝启动，用户目录外和不支持 ACL 的卷 WARN；macOS、Linux 上什么都不做。 */
class DataDirCheckTest {

    private static final String HOME = "C:\\Users\\yi";

    @Test
    void offWindowsNothingIsChecked() {
        assertEquals(new Result(Optional.empty(), List.of()), DataDirCheck.check("\\\\nas\\share\\tantalum", HOME, "yi", false, true, false, false));
    }

    @Test
    void aNetworkShareIsRefusedWithTheReasonAndWhatToDo() {
        Result result = DataDirCheck.check("\\\\nas\\share\\tantalum", HOME, "yi", true, true, false, false);
        assertEquals(List.of(), result.warnings());
        assertEquals("The data directory \\\\nas\\share\\tantalum is on a network share. SQLite cannot keep the index consistent there;"
                + " use a local disk.", result.refusal().orElseThrow().english());
    }

    @Test
    void outsideTheProfileWarnsWithAnIcaclsCommandForTheRealUser() {
        Result result = DataDirCheck.check("D:\\tantalum", HOME, "yi", true, false, false, true);
        assertTrue(result.refusal().isEmpty());
        Finding warning = result.warnings().getFirst();
        assertEquals(Problem.OUTSIDE_PROFILE, warning.problem());
        assertEquals("The data directory D:\\tantalum is outside your user profile (C:\\Users\\yi), so other users of this computer may be"
                + " able to read it. To limit it to you, SYSTEM and Administrators, run:\n"
                + "  icacls \"D:\\tantalum\" /inheritance:r /grant:r \"yi:(OI)(CI)F\" \"*S-1-5-18:(OI)(CI)F\" \"*S-1-5-32-544:(OI)(CI)F\"",
                warning.english());
    }

    @Test
    void aVolumeWithoutAclsWarns() {
        Result result = DataDirCheck.check("E:\\tantalum", HOME, "yi", true, false, false, false);
        assertEquals(List.of(Problem.OUTSIDE_PROFILE, Problem.NO_ACL), result.warnings().stream().map(Finding::problem).toList());
        assertEquals("The data directory E:\\tantalum is on a volume without file permissions (FAT32 or exFAT), so anyone using this"
                + " computer can read it. Use an NTFS volume.", result.warnings().get(1).english());
    }

    @Test
    void insideTheProfileOnAnAclVolumeThereIsNothingToSay() {
        assertEquals(new Result(Optional.empty(), List.of()),
                DataDirCheck.check(HOME + "\\.local\\share\\tantalum", HOME, "yi", true, false, true, true));
    }

    /** 需求 2.5：子命令打印同样的话，按 {@code --lang} 的语言；{@code icacls} 命令原样；拒绝时返回 false，调用方退出码 1。 */
    @Test
    void commandsSayItInTheirLanguage() {
        Result outside = DataDirCheck.check("D:\\tantalum", HOME, "yi", true, false, false, true);
        String icacls = "  " + DataDirCheck.icacls("D:\\tantalum", "yi");
        assertEquals(List.of("数据目录 D:\\tantalum 不在你的用户目录（C:\\Users\\yi）下，这台电脑上的其他用户可能读得到。只给你、SYSTEM 和管理员，运行：",
                icacls), printed(outside, Language.ZH, true));
        assertEquals(icacls, printed(outside, Language.JA, true).get(1));
        assertEquals(List.of(outside.warnings().getFirst().english().split("\n")), printed(outside, Language.EN, true));

        Result share = DataDirCheck.check("\\\\nas\\share\\tantalum", HOME, "yi", true, true, false, false);
        assertEquals(List.of("データディレクトリ \\\\nas\\share\\tantalum はネットワーク共有上にあります。SQLite はそこではインデックスの整合性を保てません。"
                + "ローカルディスクを使ってください。"), printed(share, Language.JA, false));
    }

    private static List<String> printed(Result result, Language language, boolean proceeds) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(proceeds, DataDirCheck.print(result, new PrintStream(out, true, StandardCharsets.UTF_8), CliText.of(language)));
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    /** 真实路径（需求 2.2、2.4、5）：runner 的临时目录是 8.3 短名 {@code RUNNER~1}；UNC 只解析，不访问。 */
    @Nested
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "8.3 短名、盘符和 UNC 路径只在 Windows 上有")
    class OnWindows {

        @TempDir
        Path temp;

        @Test
        void theTemporaryDirectoryUnderTheShortProfileNameCountsAsInside() {
            assertEquals(new Result(Optional.empty(), List.of()), DataDirCheck.check(temp.resolve("not-yet").resolve("tantalum")));
        }

        @Test
        void aDirectoryAtTheRootOfTheSystemDriveWarns() throws Exception {
            Path outside = Path.of(System.getenv("SystemDrive") + "\\", "tantalum-test-" + UUID.randomUUID());
            Files.createDirectory(outside);
            try {
                Result result = DataDirCheck.check(outside);
                assertTrue(result.refusal().isEmpty());
                assertEquals(List.of(Problem.OUTSIDE_PROFILE), result.warnings().stream().map(Finding::problem).toList());
                assertTrue(result.warnings().getFirst().english().contains(System.getProperty("user.name") + ":(OI)(CI)F"));
            } finally {
                Files.delete(outside);
            }
        }

        @Test
        void aUncPathIsRefusedWithoutBeingOpened() {
            Result result = DataDirCheck.check(Path.of("\\\\localhost\\C$\\tantalum"));
            assertEquals(Problem.NETWORK_SHARE, result.refusal().orElseThrow().problem());
        }
    }
}
