package tantalum.shell.journal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 数据目录里的会话文件路径。用户名和会话 ID 来自外部，编码后才进文件名。
 * 只保留小写字母、数字、{@code _}、{@code -}，其余字节写成 {@code %xx}，所以大写字母也会编码，
 * 在不区分大小写的文件系统（macOS 默认）上 {@code a} 和 {@code A} 不会撞名。
 */
public final class SessionPaths {

    static final int MAX_NAME_BYTES = 120;

    private static final String SESSIONS = "sessions";
    private static final String EXTENSION = ".jsonl";
    private static final int HASH_CHARS = 64;
    private static final char HASH_MARK = '~';
    private static final HexFormat HEX = HexFormat.of();

    private static final String SNAPSHOTS = "snapshots";
    private static final String SNAPSHOT_EXTENSION = ".json";

    private final Path root;
    private final Path snapshots;

    public SessionPaths(Path dataDir) {
        this.root = dataDir.resolve(SESSIONS).toAbsolutePath().normalize();
        this.snapshots = dataDir.resolve(SNAPSHOTS).toAbsolutePath().normalize();
    }

    public Path sessionsDir() {
        return root;
    }

    public Path userDir(Principal principal) {
        return inside(root.resolve(encode(principal.value())));
    }

    public Path sessionFile(Principal principal, SessionId session) {
        return inside(userDir(principal).resolve(encode(session.value()) + EXTENSION));
    }

    public Path snapshotFile(Principal principal, SessionId session) {
        Path file = snapshots.resolve(encode(principal.value())).resolve(encode(session.value()) + SNAPSHOT_EXTENSION).normalize();
        if (!file.startsWith(snapshots) || file.equals(snapshots)) {
            throw new IllegalArgumentException("path escapes the snapshots directory: " + file);
        }
        return file;
    }

    /** 不同输入得到不同名字。超长时截短并加 SHA-256；{@code ~} 不在编码字符内，所以不会和未截短的名字相同。 */
    static String encode(String value) {
        StringBuilder name = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            if (isKept(b)) {
                name.append((char) b);
            } else {
                name.append('%').append(HEX.toHexDigits(b));
            }
        }
        if (name.length() <= MAX_NAME_BYTES) {
            return name.toString();
        }
        return name.substring(0, MAX_NAME_BYTES - HASH_CHARS - 1) + HASH_MARK + sha256(value);
    }

    private static boolean isKept(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '_' || b == '-';
    }

    private static String sha256(String value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 编码已经排除了路径分隔符，这里是第二道检查。 */
    private Path inside(Path path) {
        Path normalized = path.normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IllegalArgumentException("path escapes the sessions directory: " + path);
        }
        return normalized;
    }
}
