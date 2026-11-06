package tantalum.value.attachment;

import java.util.Objects;

/**
 * 一个附件在某处出现时的引用。{@code id} 是存储字节的 SHA-256（{@code sha256:<64 位小写十六进制>}），
 * {@code name} 是这一次出现时的显示名：同一内容可以有不同名字。内容本身不在引用里，在用户的附件目录里。
 */
public sealed interface AttachmentRef permits ImageRef, FileRef {

    String PREFIX = "sha256:";

    String id();

    String name();

    String mediaType();

    long bytes();

    /** 64 位十六进制的哈希，不带前缀。 */
    default String hash() {
        return id().substring(PREFIX.length());
    }

    /** 给模型和 {@code read_attachment} 用的只读路径。不是服务器上的真实路径。 */
    default String readonlyPath() {
        return "/attachments/sha256-" + hash();
    }

    /** 校验 ID 的格式，原样返回。存储路径由哈希拼出，所以格式不对的一律不收。 */
    static String checkId(String id) {
        Objects.requireNonNull(id, "id");
        if (!isId(id)) {
            throw new IllegalArgumentException("attachment id must be sha256:<64 lowercase hex>: " + id);
        }
        return id;
    }

    static boolean isId(String id) {
        if (id == null || id.length() != PREFIX.length() + 64 || !id.startsWith(PREFIX)) {
            return false;
        }
        for (int i = PREFIX.length(); i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    /** 引用里的显示名：不能为空或空白。清洗在上传时做，这里只挡住明显的错。 */
    static String checkName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("attachment name must not be blank");
        }
        return name;
    }
}
