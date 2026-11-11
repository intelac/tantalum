package tantalum.local.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.wire.DirState;

/** skills-wiki 需求 2.2–2.4：用户填的目录在这台电脑上落在哪；{@code ~} 按这台电脑的家目录展开。 */
class UserDirTest {

    @TempDir
    Path temp;

    private Path home;
    private AllowedDirs dirs;

    @BeforeEach
    void setUp() throws Exception {
        temp = temp.toRealPath();
        home = Files.createDirectory(temp.resolve("home"));
        Files.createDirectories(home.resolve(".agents/skills"));
        dirs = new AllowedDirs(List.of(home));
    }

    private UserDir locate(String dir) {
        return UserDir.locate(dirs, dir, home.toString());
    }

    @Test
    void expandsTheTildeOnThisComputer() {
        assertEquals(new UserDir(DirState.OK, home.resolve(".agents/skills")), locate("~/.agents/skills"));
        assertEquals(new UserDir(DirState.OK, home.resolve(".agents/skills")), locate("~\\.agents/skills"));
        assertEquals(new UserDir(DirState.OK, home.resolve(".agents/skills")), locate("  ~/.agents/skills/  "));
        assertEquals(new UserDir(DirState.OK, home), locate("~"));
        assertEquals(new UserDir(DirState.MISSING, null), locate("~/wiki"));
    }

    /** {@code ~name}（别人的家目录）不展开，按相对路径处理：不在范围里。 */
    @Test
    void onlyAbsolutePathsAndTheOwnHomeAreAccepted() {
        assertEquals(DirState.OUTSIDE, locate("~other/skills").state());
        assertEquals(DirState.OUTSIDE, locate(".agents/skills").state());
        assertEquals(DirState.OUTSIDE, locate("").state());
        assertEquals(DirState.OUTSIDE, locate("bad\0path").state());
    }

    @Test
    void outsideIsOutsideWhetherOrNotItExists() throws Exception {
        Files.createDirectory(temp.resolve("there"));
        assertEquals(DirState.OUTSIDE, locate(temp.resolve("there").toString()).state());
        assertEquals(DirState.OUTSIDE, locate(temp.resolve("not-there").toString()).state());
        assertEquals(DirState.OUTSIDE, locate(home + "/../there").state());
        assertEquals(DirState.OUTSIDE, locate(home + "/../not-there").state());
    }

    @Test
    void theRealPathDecides() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.createSymbolicLink(home.resolve("escape"), outside);
        assertEquals(DirState.OUTSIDE, locate("~/escape").state());
        Path inside = Files.createSymbolicLink(home.resolve("alias"), home.resolve(".agents"));
        assertEquals(new UserDir(DirState.OK, home.resolve(".agents/skills")), locate(inside + "/skills"));
    }

    @Test
    void aFileIsNotADirectory() throws Exception {
        Files.writeString(home.resolve("file.txt"), "x");
        assertEquals(DirState.MISSING, locate("~/file.txt").state());
    }
}
