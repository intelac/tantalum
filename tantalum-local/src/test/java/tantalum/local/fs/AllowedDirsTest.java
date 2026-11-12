package tantalum.local.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** 前半段从服务器上的 {@code WorkspaceTest} 搬来，断言不变（只有报错的措辞换成“allowed directories”）；后半段是多个目录（L12、需求 2.2）。 */
class AllowedDirsTest {

    /** 只在 Windows 上跳过的原因。 */
    static final String MAC_CONNECTOR = "连接程序目前只在 macOS 上运行：Windows 上显示的路径用 \\ 分隔，怎么显示归 Windows 连接程序 spec";

    @TempDir
    Path temp;

    private Path root;
    private Path other;
    private AllowedDirs dirs;

    @BeforeEach
    void setUp() throws Exception {
        temp = temp.toRealPath();
        root = Files.createDirectory(temp.resolve("root"));
        other = Files.createDirectory(temp.resolve("other"));
        Files.writeString(root.resolve("inside.txt"), "ok");
        Files.writeString(other.resolve("lib.txt"), "lib");
        Files.writeString(temp.resolve("secret.txt"), "secret");
        dirs = new AllowedDirs(List.of(root));
    }

    // ---- 一个目录：同原来的 Workspace ----

    @Test
    void resolvesAPathInside() {
        assertEquals(root.resolve("inside.txt"), dirs.resolve("inside.txt"));
        assertEquals(root, dirs.resolve("."));
    }

    @Test
    void rejectsDotDot() {
        assertThrows(IllegalArgumentException.class, () -> dirs.resolve("../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> dirs.resolve("a/../../secret.txt"));
    }

    @Test
    void rejectsAbsolutePathOutside() {
        assertThrows(IllegalArgumentException.class, () -> dirs.resolve(temp.resolve("secret.txt").toString()));
    }

    @Test
    void rejectsSymlinkLeadingOutside() throws Exception {
        Files.createSymbolicLink(root.resolve("link.txt"), temp.resolve("secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> dirs.resolve("link.txt"));
    }

    @Test
    void rejectsSymlinkedDirectoryLeadingOutside() throws Exception {
        Files.createSymbolicLink(root.resolve("dir"), temp);
        assertThrows(IllegalArgumentException.class, () -> dirs.resolve("dir/secret.txt"));
    }

    @Test
    void rejectsMissingFile() {
        assertThrows(IllegalArgumentException.class, () -> dirs.resolve("missing.txt"));
    }

    /** 目录外存在的文件和不存在的文件必须报同一句话，否则服务器能借此探测电脑上的文件布局。 */
    @Test
    void outsideAndMissingPathsAreIndistinguishable() {
        String existing = assertThrows(IllegalArgumentException.class, () -> dirs.resolve("../secret.txt")).getMessage();
        String missing = assertThrows(IllegalArgumentException.class, () -> dirs.resolve("../nope.txt")).getMessage();
        String inside = assertThrows(IllegalArgumentException.class, () -> dirs.resolve("nope.txt")).getMessage();
        assertEquals(existing.replace("secret", "X"), missing.replace("nope", "X"));
        assertEquals(missing.replace("../", ""), inside);
        assertTrue(inside.endsWith(AllowedDirs.RELATIVE_HINT), inside);
        assertFalse(existing.contains(temp.toString()));
    }

    @Test
    void writeResolvesNewFilesAndNewDirectories() {
        assertEquals(root.resolve("new.txt"), dirs.resolveForWrite("new.txt"));
        assertEquals(root.resolve("a/b/c.txt"), dirs.resolveForWrite("a/b/c.txt"));
        assertEquals(root.resolve("inside.txt"), dirs.resolveForWrite("./inside.txt"));
    }

    @Test
    void writeRejectsEscapesAndTheRootItself() {
        assertThrows(IllegalArgumentException.class, () -> dirs.resolveForWrite("../new.txt"));
        assertThrows(IllegalArgumentException.class, () -> dirs.resolveForWrite("a/../../new.txt"));
        assertThrows(IllegalArgumentException.class, () -> dirs.resolveForWrite(temp.resolve("new.txt").toString()));
        assertThrows(IllegalArgumentException.class, () -> dirs.resolveForWrite("."));
    }

    @Test
    void writeRejectsSymlinkedDirectoriesLeadingOutside() throws Exception {
        Files.createSymbolicLink(root.resolve("out"), temp);
        assertThrows(IllegalArgumentException.class, () -> dirs.resolveForWrite("out/new.txt"));
        assertThrows(IllegalArgumentException.class, () -> dirs.resolveForWrite("out/deeper/new.txt"));
        Files.createSymbolicLink(root.resolve("dangling"), temp.resolve("gone"));
        assertThrows(IllegalArgumentException.class, () -> dirs.resolveForWrite("dangling"));
    }

    @Test
    void writeFollowsSymlinksThatStayInside() throws Exception {
        Files.createDirectory(root.resolve("real"));
        Files.createSymbolicLink(root.resolve("alias"), root.resolve("real"));
        assertEquals(root.resolve("real/new.txt"), dirs.resolveForWrite("alias/new.txt"));
    }

    @Test
    void writeRejectsAFileUsedAsADirectory() {
        assertThrows(IllegalArgumentException.class, () -> dirs.resolveForWrite("inside.txt/new.txt"));
    }

    // ---- 多个目录 ----

    @Test
    void theFirstDirectoryIsTheMainOneAndRelativePathsResolveAgainstIt() throws Exception {
        AllowedDirs two = new AllowedDirs(List.of(root, other));
        assertEquals(root, two.main());
        assertEquals(List.of(root, other), two.all());
        assertEquals(root.resolve("inside.txt"), two.resolve("inside.txt"));
        // 第二个目录里的文件不能只写文件名
        assertThrows(IllegalArgumentException.class, () -> two.resolve("lib.txt"));
    }

    @Test
    void absolutePathsInAnyAllowedDirectoryResolve() throws Exception {
        AllowedDirs two = new AllowedDirs(List.of(root, other));
        assertEquals(other.resolve("lib.txt"), two.resolve(other.resolve("lib.txt").toString()));
        assertEquals(other.resolve("new/x.txt"), two.resolveForWrite(other.resolve("new/x.txt").toString()));
        // 落在另一个允许的目录里就算在范围内，不管是怎么写到那儿的
        assertEquals(other.resolve("lib.txt"), two.resolve("../other/lib.txt"));
        assertThrows(IllegalArgumentException.class, () -> two.resolve(temp.resolve("secret.txt").toString()));
    }

    @Test
    void outsideAndMissingAreStillIndistinguishableWithSeveralDirectories() throws Exception {
        AllowedDirs two = new AllowedDirs(List.of(root, other));
        String outside = assertThrows(IllegalArgumentException.class, () -> two.resolve(temp + "/secret.txt")).getMessage();
        String missing = assertThrows(IllegalArgumentException.class, () -> two.resolve(other + "/nope.txt")).getMessage();
        assertEquals(AllowedDirs.NOT_FOUND + temp + "/secret.txt", outside);
        assertEquals(AllowedDirs.NOT_FOUND + other + "/nope.txt", missing);
    }

    @Test
    void symlinksMayCrossBetweenAllowedDirectoriesButNotOutOfThem() throws Exception {
        AllowedDirs two = new AllowedDirs(List.of(root, other));
        Files.createSymbolicLink(root.resolve("to-other"), other);
        Files.createSymbolicLink(other.resolve("escape"), temp);
        assertEquals(other.resolve("lib.txt"), two.resolve("to-other/lib.txt"));
        assertEquals(other.resolve("made.txt"), two.resolveForWrite("to-other/made.txt"));
        assertThrows(IllegalArgumentException.class, () -> two.resolve(other + "/escape/secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> two.resolveForWrite(other + "/escape/new.txt"));
    }

    @Test
    void noAllowedDirectoryMayBeWrittenAsAFile() throws Exception {
        AllowedDirs two = new AllowedDirs(List.of(root, other));
        assertThrows(IllegalArgumentException.class, () -> two.resolveForWrite(other.toString()));
        assertThrows(IllegalArgumentException.class, () -> two.resolveForWrite(root.toString()));
    }

    /** 一个允许的目录在另一个里面：两种写法都到同一个真实路径，显示时按主目录写相对路径。 */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = MAC_CONNECTOR)
    void nestedDirectoriesResolveToTheSameFile() throws Exception {
        Path sub = Files.createDirectory(root.resolve("sub"));
        Files.writeString(sub.resolve("x.txt"), "x");
        AllowedDirs nested = new AllowedDirs(List.of(root, sub));
        assertEquals(sub.resolve("x.txt"), nested.resolve("sub/x.txt"));
        assertEquals(sub.resolve("x.txt"), nested.resolve(sub.resolve("x.txt").toString()));
        assertEquals("sub/x.txt", nested.display(sub.resolve("x.txt")));
        assertThrows(IllegalArgumentException.class, () -> nested.resolveForWrite("sub"));

        AllowedDirs inner = new AllowedDirs(List.of(sub, root));
        assertEquals(root.resolve("inside.txt"), inner.resolve("../inside.txt"));
        assertEquals(root.resolve("inside.txt").toString(), inner.display(root.resolve("inside.txt")));
    }

    @Test
    void displayIsRelativeInTheMainDirectoryAndAbsoluteElsewhere() throws Exception {
        AllowedDirs two = new AllowedDirs(List.of(root, other));
        assertEquals("inside.txt", two.display(root.resolve("inside.txt")));
        assertEquals(".", two.display(root));
        assertEquals(other.resolve("lib.txt").toString(), two.display(other.resolve("lib.txt")));
    }

    /** 保存的是真实路径，给的是指向目录的符号链接也一样；重复的只算一个。 */
    @Test
    void directoriesAreStoredAsRealPathsWithoutDuplicates() throws Exception {
        Path link = Files.createSymbolicLink(temp.resolve("root-link"), root);
        AllowedDirs viaLink = new AllowedDirs(List.of(link, root, other));
        assertEquals(List.of(root, other), viaLink.all());
        assertEquals(root.resolve("inside.txt"), viaLink.resolve("inside.txt"));
    }

    /** 需求 2.1：一个都没有、不存在、不是目录，都不能用。 */
    @Test
    void missingOrInvalidDirectoriesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new AllowedDirs(List.of()));
        assertThrows(java.io.IOException.class, () -> new AllowedDirs(List.of(temp.resolve("gone"))));
        assertThrows(IllegalArgumentException.class, () -> new AllowedDirs(List.of(temp.resolve("secret.txt"))));
    }

    // ---- 会话目录（workspaces 任务 13，W5、需求 2.3、2.4） ----

    @Test
    void aSessionDirectoryBecomesTheStartButNotTheLimit() throws Exception {
        Path app = Files.createDirectory(root.resolve("app"));
        Files.writeString(app.resolve("Main.java"), "class Main {}");
        AllowedDirs both = new AllowedDirs(List.of(root, other));
        AllowedDirs session = both.from(app.toString());
        assertEquals(app, session.main());
        assertEquals(app.resolve("Main.java"), session.resolve("Main.java"));
        assertEquals(root.resolve("inside.txt"), session.resolve("../inside.txt"), "the rest of the allowed directory is still allowed");
        assertEquals(other.resolve("lib.txt"), session.resolve(other.resolve("lib.txt").toString()));
        assertEquals(List.of(root, other), session.all());
        assertEquals("Main.java", session.display(app.resolve("Main.java")));
        assertThrows(IllegalArgumentException.class, () -> session.resolve("../../secret.txt"));
        assertEquals(root, both.main(), "the original is unchanged");
    }

    @Test
    void aSessionDirectoryMustExistBeADirectoryAndBeAllowed() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> dirs.from(temp.toString()));
        assertThrows(IllegalArgumentException.class, () -> dirs.from(root.resolve("missing").toString()));
        assertThrows(IllegalArgumentException.class, () -> dirs.from(root.resolve("inside.txt").toString()));
        assertEquals(root, dirs.from(root.toString()).main());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "符号链接在 Windows 上要管理员权限")
    void aLinkOutOfTheAllowedDirectoriesIsNotASessionDirectory() throws Exception {
        Path link = Files.createSymbolicLink(root.resolve("escape"), temp);
        assertThrows(IllegalArgumentException.class, () -> dirs.from(link.toString()));
    }

    /**
     * tool-scenarios 第一轮：真模型常写错相对路径（少一层目录）。报错里说明相对路径从哪算起，它一步就能改对。
     * 绝对路径的报错不变；存在不存在、越不越界仍然是同一句话。
     */
    @Test
    void aRelativePathThatIsNotFoundSaysWhereRelativePathsStart() throws Exception {
        Path main = Files.createDirectories(temp.resolve("project")).toRealPath();
        Files.createDirectories(main.resolve("src"));
        Files.writeString(temp.resolve("secret.txt"), "x");
        AllowedDirs dirs = new AllowedDirs(List.of(main));
        String hint = AllowedDirs.RELATIVE_HINT;
        assertFalse(hint.contains("/"), "the hint is a fixed sentence without any real path");

        assertEquals(AllowedDirs.NOT_FOUND + "pricing.py" + hint, assertThrows(IllegalArgumentException.class, () -> dirs.resolve("pricing.py")).getMessage());
        // 越界的相对路径和不存在的是同一句话
        assertEquals(AllowedDirs.NOT_FOUND + "../secret.txt" + hint, assertThrows(IllegalArgumentException.class, () -> dirs.resolve("../secret.txt")).getMessage());
        assertEquals(AllowedDirs.NOT_FOUND + "../nope.txt" + hint, assertThrows(IllegalArgumentException.class, () -> dirs.resolve("../nope.txt")).getMessage());
        assertEquals(AllowedDirs.NOT_FOUND + "../new.txt" + hint, assertThrows(IllegalArgumentException.class, () -> dirs.resolveForWrite("../new.txt")).getMessage());
        // 绝对路径：不加
        String outside = temp.resolve("secret.txt").toString();
        assertEquals(AllowedDirs.NOT_FOUND + outside, assertThrows(IllegalArgumentException.class, () -> dirs.resolve(outside)).getMessage());
        // 模型凭印象猜的一个绝对路径（用临时目录拼，Windows 上带盘符才算绝对路径）
        String guessed = temp.resolve("someone").resolve("claude-work").toString();
        assertEquals(AllowedDirs.NOT_FOUND + guessed, assertThrows(IllegalArgumentException.class, () -> dirs.resolve(guessed)).getMessage());
        // 会话有工作目录时，起点是它
        AllowedDirs inSrc = dirs.from("src");
        assertEquals(AllowedDirs.NOT_FOUND + "a.py" + hint,
                assertThrows(IllegalArgumentException.class, () -> inSrc.resolve("a.py")).getMessage());
    }
}
