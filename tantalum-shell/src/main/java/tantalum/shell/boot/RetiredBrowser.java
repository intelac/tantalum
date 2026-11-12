package tantalum.shell.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务器上以前有浏览器（web-tools），local-browser 起搬到用户电脑上。升级后的收尾两件事（local-browser 需求 8.2、8.4）：
 * 旧配置还写着时提示一行（照常启动，不像改名的配置那样拒绝：这些键没有新名字，写着也无害）；
 * 数据目录里以前解压的 Playwright 驱动 {@code browser/}（约 130 MB 的程序文件，不是用户数据）删掉。
 */
final class RetiredBrowser {

    private static final Logger LOG = LoggerFactory.getLogger(RetiredBrowser.class);
    /** 以前的驱动目录：{@code <数据目录>/browser/driver-<版本>/}。 */
    static final String DIR = "browser";

    private RetiredBrowser() {}

    /** 还写着的旧配置（只有键名）。{@code configured} 说一个键有没有配。 */
    static List<String> staleKeys(Predicate<String> configured) {
        return TantalumProperties.STALE_BROWSER_KEYS.stream().filter(configured).toList();
    }

    /** 启动时调：有旧配置就 WARN 一行；清掉旧驱动目录。都不影响启动。 */
    static void atStartup(Predicate<String> configured, Path dataDir) {
        List<String> stale = staleKeys(configured);
        if (!stale.isEmpty()) {
            LOG.warn("These settings no longer do anything and can be removed: {}. The browser now runs on each user's own computer, "
                    + "through the connector (start it with --allow-browser); the server needs no browser.", String.join(", ", stale));
        }
        removeOldDriver(dataDir);
    }

    /**
     * 删掉 {@code <数据目录>/browser}，前提是它确实是以前的驱动目录：是个真目录（不是符号链接），里面只有 {@code driver-} 开头的条目。
     * 不是这个样子就不动它。删不掉只记一行。返回删没删。
     */
    static boolean removeOldDriver(Path dataDir) {
        Path dir = dataDir.resolve(DIR);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            try (Stream<Path> entries = Files.list(dir)) {
                if (!entries.allMatch(entry -> entry.getFileName().toString().startsWith("driver-"))) {
                    LOG.warn("{} holds something other than the old browser driver; leaving it alone", dir);
                    return false;
                }
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
            LOG.info("removed the old server-side browser driver in {}", dir);
            return true;
        } catch (IOException | RuntimeException failure) {
            LOG.warn("could not remove the old browser driver in {}: {}", dir, failure.getClass().getSimpleName());
            return false;
        }
    }
}
