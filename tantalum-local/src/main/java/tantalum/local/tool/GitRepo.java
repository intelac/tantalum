package tantalum.local.tool;

import java.io.IOException;
import java.nio.file.Path;
import tantalum.local.fs.AllowedDirs;

/** 连接程序启动时告诉服务器每个允许的目录是不是 Git 仓库（{@code hello.roots[].git}）。规则同 Git 工具：仓库顶层也在允许的目录里才算。 */
public final class GitRepo {

    private GitRepo() {}

    /** 没装 git、不是仓库、顶层在允许的目录外都是 false。 */
    public static boolean at(AllowedDirs dirs, Path dir) throws InterruptedException {
        try {
            Git.place(new LocalContext("hello", dirs, new Observations()), dir.toString());
            return true;
        } catch (IllegalArgumentException | IOException notARepository) {
            return false;
        }
    }
}
