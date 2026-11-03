package tantalum.shell.attachment;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import tantalum.shell.attachment.ImageNormalizer.Normalized;
import tantalum.shell.attachment.ImageNormalizer.Target;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;

/**
 * 发请求用的图片版本（设计第 4 节，同 DSH {@code request-image.ts}）。存储的图片已满足目标时直接用存储的字节；
 * 否则按规范化同样的确定性规则再编码一次，存到 {@code requests/} 下，以后直接读，不重新编码。
 */
public final class RequestImages {

    /** Claude 和 OpenAI 兼容共用的目标：长边 2048，3.75 MiB（base64 后不超过 5 MiB，在两家的单图上限以内）。 */
    public static final Target MODEL = new Target(2048, 3_932_160);

    /** 变换规则改了就加一，旧缓存自然不再命中。 */
    static final int TRANSFORM_VERSION = 1;

    /** 一张请求用的图片。 */
    public record Version(byte[] bytes, String mediaType, int width, int height) {}

    private final AttachmentStore store;
    private final AtomicInteger encodes = new AtomicInteger();

    public RequestImages(AttachmentStore store) {
        this.store = store;
    }

    /**
     * 这张图片按 {@code target} 的版本。附件不在了或哈希不符时抛出（需求 4.2），由调用方让这次模型调用失败。
     *
     * @throws NoSuchFileException 附件不在了
     */
    public Version of(Principal principal, ImageRef ref, Target target) throws IOException {
        byte[] stored = store.bytes(principal, ref.id());
        if (fits(ref, stored.length, target)) {
            return new Version(stored, ref.mediaType(), ref.width(), ref.height());
        }
        Path cached = store.requestCache(principal, ref.hash(), versionKey(target));
        if (Files.isRegularFile(cached)) {
            byte[] bytes = Files.readAllBytes(cached);
            return described(bytes);
        }
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stored));
        if (decoded == null) {
            throw new IOException("attachment " + ref.id() + " cannot be decoded");
        }
        Normalized version = ImageNormalizer.fit(decoded, 1, ref.width(), ref.height(), target);
        encodes.incrementAndGet();
        AttachmentStore.writePrivately(cached, version.bytes());
        return new Version(version.bytes(), version.mediaType(), version.width(), version.height());
    }

    /** 到目前为止重新编码了几次（测试和性能检查用）。 */
    public int encodes() {
        return encodes.get();
    }

    static String versionKey(Target target) {
        return target.maxEdge() + "x" + target.maxBytes() + "-v" + TRANSFORM_VERSION;
    }

    private static boolean fits(ImageRef ref, long bytes, Target target) {
        return ref.width() <= target.maxEdge() && ref.height() <= target.maxEdge() && bytes <= target.maxBytes();
    }

    /** 缓存文件只存字节；类型看文件头，尺寸只读头，不解码。 */
    private static Version described(byte[] bytes) throws IOException {
        String type = ImageScan.sniff(bytes).orElseThrow(() -> new IOException("cached request image is not an image"));
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("no reader for cached request image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new Version(bytes, type, reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }
}
