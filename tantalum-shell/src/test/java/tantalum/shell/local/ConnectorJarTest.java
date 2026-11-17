package tantalum.shell.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.wire.Hello;
import tantalum.local.wire.HelloFixture;

/** 服务器提供下载的连接程序和版本比较（connector-jar 需求 2）。 */
class ConnectorJarTest {

    @TempDir
    Path dir;

    private Path jar(String name, String version) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.flush();
        }
        return file;
    }

    private static Hello hello(int protocol, String version, String sha) {
        return new Hello(protocol, version, new Hello.Device("pc", "windows-11", "10.0", "amd64"), List.of(), false,
                HelloFixture.shell(false, "--allow-shell was not given"), true, false, null, sha);
    }

    @Test
    void findsTheJarNextToTheServerAndComparesProtocolAndVersion() throws Exception {
        jar("tantalum-connect.jar", "0.1.0");
        ConnectorJar connector = ConnectorJar.find("", dir);
        ConnectorJar.Info info = connector.info().orElseThrow();
        assertEquals("0.1.0", info.version());
        assertEquals(64, info.sha256().length());
        assertEquals(Optional.of(true), connector.matches(hello(6, "0.1.0", info.sha256())));
        // 同一个版本、另一次构建（哈希不同）不算不一致：构建不可重复，重新打包不该让人人都看到提示（QAgent 问题 66）
        assertEquals(Optional.of(true), connector.matches(hello(6, "0.1.0", "0".repeat(64))));
        assertEquals(Optional.of(false), connector.matches(hello(6, "0.2.0", null)), "another release");
        assertEquals(Optional.of(true), connector.matches(hello(6, "dev", null)), "run from source: no warning");
        assertEquals(Optional.of(true), connector.matches(hello(6, "test", null)));
        assertEquals(Optional.of(false), connector.matches(hello(5, "0.1.0", null)), "older protocol: always tell to update");
    }

    @Test
    void theBuildOutputNameAndTheConfiguredPathWorkToo() throws Exception {
        Path built = jar("tantalum-connect-0.2.0-all.jar", "0.2.0");
        assertEquals("0.2.0", ConnectorJar.find(null, dir).info().orElseThrow().version());
        Path elsewhere = Files.createDirectory(dir.resolve("x"));
        assertEquals(built, ConnectorJar.find(built.toString(), elsewhere).info().orElseThrow().file());
    }

    @Test
    void nothingToOfferMeansUnknown() {
        ConnectorJar none = ConnectorJar.find("", dir);
        assertTrue(none.info().isEmpty());
        assertEquals(Optional.empty(), none.matches(hello(6, "0.1.0", null)));
        assertTrue(ConnectorJar.find(dir.resolve("missing.jar").toString(), dir).info().isEmpty());
    }
}
