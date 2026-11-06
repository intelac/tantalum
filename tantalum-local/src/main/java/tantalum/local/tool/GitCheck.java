package tantalum.local.tool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 连接程序启动时看这台电脑能不能跑 {@code git}（{@code hello.git}）。只看装没装：哪个目录是仓库由每次调用按路径判断
 * （{@link Git#place}），所以子文件夹里的仓库、任何一个允许目录里的仓库都能查。
 */
public final class GitCheck {

    private GitCheck() {}

    /** {@code git --version} 能跑通就是装了；在 {@code dir} 里跑，环境变量的处理同 Git 工具。 */
    public static boolean installed(Path dir) throws InterruptedException {
        try {
            return Git.run(dir, List.of("--version")).exitCode() == 0;
        } catch (IOException missing) {
            return false;
        }
    }
}
