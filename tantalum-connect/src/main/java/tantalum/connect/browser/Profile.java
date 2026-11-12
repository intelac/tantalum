package tantalum.connect.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tantalum.local.fs.PrivateFiles;

/**
 * 连接程序在这台电脑上自己的目录，和里面的浏览器资料目录（local-browser 需求 3）。资料目录放 Cookie、登录状态、本地存储，
 * 连接程序重启后还在；和用户平时的 Chrome、Edge 的资料目录不是一个地方，那边一个字节也不碰。
 * 默认位置在家目录下，不在任何 {@code --allow} 目录里（家目录本身不许 {@code --allow}），命令的沙箱也读不到。
 */
public final class Profile {

    static final String APP = "Tantalum Connect";
    static final String DIR = "browser-profile";

    private Profile() {}

    /** 这台电脑上连接程序自己的目录。 */
    public static Path home() {
        return home(System.getProperty("os.name", ""), System.getenv(), Path.of(System.getProperty("user.home", "")));
    }

    /**
     * macOS：{@code ~/Library/Application Support/Tantalum Connect}；Windows：{@code %LOCALAPPDATA%\Tantalum Connect}
     * （没有这个变量时 {@code ~\AppData\Local\…}）；其他：{@code ~/.local/share/tantalum-connect}。
     */
    static Path home(String os, Map<String, String> env, Path userHome) {
        if (Installed.mac(os)) {
            return userHome.resolve("Library").resolve("Application Support").resolve(APP);
        }
        if (Installed.windows(os)) {
            String local = env.get("LOCALAPPDATA");
            Path base = local == null || local.isBlank() ? userHome.resolve("AppData").resolve("Local") : Path.of(local);
            return base.resolve(APP);
        }
        return userHome.resolve(".local").resolve("share").resolve("tantalum-connect");
    }

    /** 默认的资料目录。 */
    public static Path standard() {
        return home().resolve(DIR);
    }

    /** 建好资料目录（连同上级），POSIX 上只有本人能进；已经有就不动里面的东西。返回真实路径。 */
    public static Path prepare(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            PrivateFiles.createDirectories(dir);
        }
        PrivateFiles.restrictDirectory(dir);
        return dir.toRealPath();
    }
}
