package tantalum.examples.bench;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.attachment.ImageNormalizer;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.id.Principal;

/**
 * 附件上传预算（attachments 非功能“性能”）：一张 20 MB 的 JPEG 照片走 {@link AttachmentStore#upload}，
 * 含边收边写、算哈希、先查头、解码、缩小、重新编码和落盘。每个样本一个新目录，不会命中去重。
 * {@code mvn -P bench -pl tantalum-examples -am -DskipTests test-compile -Dbench.main=tantalum.examples.bench.UploadBench}
 */
public final class UploadBench {

    private static final long TARGET_BYTES = 20_000_000;

    private UploadBench() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createDirectories(Path.of(args[0])).resolve("upload-" + System.nanoTime());
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        Path photo = Files.write(Files.createDirectories(dir).resolve("photo.jpg"), photo());
        System.out.printf("photo %.1f MB%n", Files.size(photo) / 1e6);
        AttachmentLimits limits = AttachmentLimits.defaults();
        ImageNormalizer normalizer = new ImageNormalizer(limits);
        List<Long> times = new ArrayList<>();
        for (int i = 0; i <= samples; i++) {
            AttachmentStore store = new AttachmentStore(dir.resolve("data-" + i), limits, normalizer);
            long started = System.nanoTime();
            AttachmentRef ref;
            try (InputStream in = Files.newInputStream(photo)) {
                ref = store.upload(new Principal("bench"), "photo.jpg", "image/jpeg", in, Files.size(photo));
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            System.out.printf("sample %d: %d ms -> %s%n", i, ms, ref);
            if (i > 0) {
                times.add(ms); // 第 0 次是 JIT 预热
            }
        }
        Collections.sort(times);
        Budgets budgets = Budgets.fromSystemProperty();
        budgets.check("upload_20mb_ms", times.get(times.size() / 2), Budgets.UPLOAD_20MB_MS);
        budgets.exitIfOver();
    }

    /** 噪声多的照片才压不小：在平滑的渐变上加随机噪声，最高质量编码，加大尺寸直到超过 20 MB。 */
    private static byte[] photo() throws Exception {
        SplittableRandom random = new SplittableRandom(73);
        for (int width = 4000; ; width += 500) {
            int height = width * 3 / 4;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int base = 255 * x / width;
                    int noise = random.nextInt(-60, 61);
                    int r = clamp(base + noise);
                    int g = clamp(255 * y / height + random.nextInt(-60, 61));
                    int b = clamp(255 - base + random.nextInt(-60, 61));
                    image.setRGB(x, y, r << 16 | g << 8 | b);
                }
            }
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(1.0f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            writer.dispose();
            if (out.size() >= TARGET_BYTES) {
                return out.toByteArray();
            }
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
