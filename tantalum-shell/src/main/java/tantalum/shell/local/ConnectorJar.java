package tantalum.shell.local;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import tantalum.local.wire.Hello;

/**
 * 服务器提供下载的连接程序（connector-jar 需求 2）：同一次构建的 {@code tantalum-connect.jar}。不打进服务器的 jar
 * （{@code tantalum-connect} 的测试依赖服务器，反过来会成环），发布时放在服务器 jar 旁边。
 * <ul>
 *   <li>找：配置 {@code tantalum.local.connector-jar}；没配时在服务器 jar 所在的目录找 {@code tantalum-connect.jar}，再找
 *       {@code tantalum-connect-*-all.jar}。</li>
 *   <li>启动时算一次 SHA-256，读 jar 里的版本（{@code Implementation-Version}）。文件之后被换掉要重启服务器才认。</li>
 *   <li>和电脑上的比：协议和版本号（见 {@link #matches}），不比哈希。只用来提示“不是这里提供的版本”，不是安全校验。</li>
 * </ul>
 */
public final class ConnectorJar {

    public static final String NAME = "tantalum-connect.jar";

    /** 找到的 jar：位置、版本、哈希、大小。 */
    public record Info(Path file, String version, String sha256, long size) {}

    private final Optional<Info> info;

    private ConnectorJar(Optional<Info> info) {
        this.info = info;
    }

    /** {@code configured} 为空时在 {@code serverDir} 里找；都找不到时 {@link #info()} 为空。 */
    public static ConnectorJar find(String configured, Path serverDir) {
        Optional<Path> file = Optional.empty();
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.strip());
            file = Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        } else if (serverDir != null) {
            Path standard = serverDir.resolve(NAME);
            if (Files.isRegularFile(standard)) {
                file = Optional.of(standard);
            } else {
                try (Stream<Path> files = Files.list(serverDir)) {
                    file = files.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("tantalum-connect-") && name.endsWith("-all.jar");
                    }).sorted().findFirst();
                } catch (IOException | RuntimeException unreadable) {
                    file = Optional.empty();
                }
            }
        }
        return new ConnectorJar(file.flatMap(ConnectorJar::read));
    }

    /** 服务器 jar 所在的目录：类路径上的第一个 jar 的上级。从源码运行时为空。 */
    public static Path serverDir() {
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (entry.endsWith(".jar")) {
                Path parent = Path.of(entry).toAbsolutePath().getParent();
                if (parent != null) {
                    return parent;
                }
            }
        }
        return null;
    }

    private static Optional<Info> read(Path file) {
        try {
            String version = null;
            try (JarFile jar = new JarFile(file.toFile())) {
                if (jar.getManifest() != null) {
                    version = jar.getManifest().getMainAttributes().getValue("Implementation-Version");
                }
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    digest.update(buffer, 0, read);
                }
            }
            return Optional.of(new Info(file, version == null ? "dev" : version, HexFormat.of().formatHex(digest.digest()), Files.size(file)));
        } catch (IOException | NoSuchAlgorithmException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    public Optional<Info> info() {
        return info;
    }

    /**
     * 电脑上的连接程序是不是这里提供的那个：服务器上没放 jar 时为空（不知道）。协议比服务器的旧算不一致；两边都有正式的版本号
     * （不是 {@code dev}、{@code test}）而且不同也算不一致。不比 jar 的哈希：构建不可重复，服务器重新打包一次、连接程序的代码没变，
     * 哈希也会变，人人都会看到“不是这里提供的版本”（2026-11-14 对照 QAgent 的问题清单第 66 条查出来的）。
     */
    public Optional<Boolean> matches(Hello hello) {
        if (info.isEmpty()) {
            return Optional.empty();
        }
        if (hello.protocol() < tantalum.local.wire.Wire.PROTOCOL) {
            return Optional.of(false);
        }
        String theirs = hello.connector();
        String ours = info.get().version();
        if (!release(theirs) || !release(ours)) {
            return Optional.of(true);
        }
        return Optional.of(theirs.equals(ours));
    }

    /** 正式的版本号：数字开头，如 {@code 0.1.0}；{@code dev}（从源码跑）、{@code test} 不算。 */
    static boolean release(String version) {
        return version != null && !version.isEmpty() && Character.isDigit(version.charAt(0));
    }
}
