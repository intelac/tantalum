package tantalum.shell.param;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.local.fs.PrivateFiles;

/**
 * 插件密码的密钥从哪来：环境变量 {@code TANTALUM_SECRET_KEY}（Base64 的 32 字节）优先；没设就用数据目录里的 {@code secret.key}，
 * 第一次启动时生成，只有服务的账号能读。密钥丢了或换了，存着的密码解不开，页面上显示“要重新填”。
 */
public final class SecretKey {

    private static final Logger LOG = LoggerFactory.getLogger(SecretKey.class);

    public static final String ENV = "TANTALUM_SECRET_KEY";
    public static final String FILE = "secret.key";
    static final int BYTES = 32;

    private SecretKey() {}

    /** {@code fromEnv} 是环境变量的值，没设为 null 或空。格式不对抛 {@link IllegalStateException}，消息里不带值。 */
    public static byte[] load(Path dataDir, String fromEnv) throws IOException {
        if (fromEnv != null && !fromEnv.isBlank()) {
            return decode(fromEnv.strip(), ENV);
        }
        Path file = dataDir.resolve(FILE);
        if (Files.exists(file)) {
            return decode(Files.readString(file).strip(), file.toString());
        }
        byte[] key = new byte[BYTES];
        new SecureRandom().nextBytes(key);
        Files.createDirectories(dataDir);
        PrivateFiles.createFile(file);
        Files.writeString(file, Base64.getEncoder().encodeToString(key));
        LOG.warn("generated the key for plugin secrets at {}; keep it apart from data backups, or set {} instead", file, ENV);
        return key;
    }

    private static byte[] decode(String text, String where) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(where + " must be 32 bytes in Base64");
        }
        if (key.length != BYTES) {
            throw new IllegalStateException(where + " must be 32 bytes in Base64 but decodes to " + key.length + " bytes");
        }
        return key;
    }
}
