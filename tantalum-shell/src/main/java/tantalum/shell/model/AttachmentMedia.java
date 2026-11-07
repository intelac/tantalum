package tantalum.shell.model;

import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;

/**
 * 发请求时附件的内容。prompt 里只有引用，客户端组请求时按引用来这里取：图片是请求用的版本（设计第 4 节），文件是原样的字节。
 * 取不到或哈希不符时抛 {@link Unavailable}，这次模型调用失败且不重试，别的会话不受影响。
 */
public interface AttachmentMedia {

    /** 请求用的一张图片。 */
    record Image(byte[] bytes, String mediaType) {}

    Image image(ImageRef ref);

    byte[] file(FileRef ref);

    /** 附件不在了、读不出或哈希不符。消息写明会话和附件 ID。 */
    final class Unavailable extends RuntimeException {

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 没有附件目录（测试和不带附件的嵌入）：碰到附件就失败。 */
    AttachmentMedia NONE = new AttachmentMedia() {
        @Override
        public Image image(ImageRef ref) {
            throw new Unavailable("attachment " + ref.id() + " is not available here", null);
        }

        @Override
        public byte[] file(FileRef ref) {
            throw new Unavailable("attachment " + ref.id() + " is not available here", null);
        }
    };
}
