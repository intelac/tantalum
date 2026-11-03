package tantalum.shell.attachment;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import tantalum.shell.attachment.AttachmentRejected.Reason;

/**
 * 上传的图片在存储前统一规范化（设计第 3 节，需求 A4、2.2、2.3）：先查头再解码；干净的原样保存；
 * 否则按 EXIF 方向转正、缩到目标以内、去掉元数据、重新编码。同样的输入总是得到同样的字节。
 */
public final class ImageNormalizer {

    private static final long MIB = 1024 * 1024;

    /** 存储版本的目标：两边都不超过 2048，不超过 4 MiB（A4）。 */
    public static final Target STORED = new Target(2048, 4 * MIB);

    private static final float[] JPEG_QUALITIES = {0.92f, 0.85f, 0.75f, 0.6f};
    private static final double SHRINK = 0.75;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");

    /** 一个编码目标：长边上限和字节上限。 */
    public record Target(int maxEdge, long maxBytes) {}

    /** 规范化的结果。{@code sourceWidth}、{@code sourceHeight} 只在缩小过时有值，是转正后的原尺寸。 */
    public record Normalized(byte[] bytes, String mediaType, int width, int height, Integer sourceWidth,
                             Integer sourceHeight) {}

    private final AttachmentLimits limits;
    final Semaphore permits;
    private final Duration wait;

    public ImageNormalizer(AttachmentLimits limits) {
        this(limits, Duration.ofSeconds(30));
    }

    ImageNormalizer(AttachmentLimits limits, Duration wait) {
        // 打包成 Spring Boot 的 jar 后，插件在嵌套 jar 里；按当前的类加载器再找一遍，WebP 才读得了。
        ImageIO.scanForPlugins();
        this.limits = limits;
        this.permits = new Semaphore(limits.normalizeConcurrency(), true);
        this.wait = wait;
    }

    /** 按文件头认四种图片；其余（包括 SVG）返回空。 */
    public static Optional<String> sniff(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return ImageScan.sniff(in.readNBytes(12));
        }
    }

    /** 文件名后缀声称是图片。声称是但文件头不是的，上传时拒绝（需求 2.2）。 */
    public static boolean claimsImage(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 规范化一张图片。同时进行的最多 {@code normalizeConcurrency} 张，其余排队；排太久返回 {@link Reason#BUSY}。 */
    public Normalized normalize(Path file) throws IOException, AttachmentRejected {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AttachmentRejected(Reason.BUSY, "图片处理被中断，请重试");
        }
        if (!acquired) {
            throw new AttachmentRejected(Reason.BUSY, "图片处理繁忙，请稍后重试");
        }
        try {
            return normalizeNow(file);
        } finally {
            permits.release();
        }
    }

    private Normalized normalizeNow(Path file) throws IOException, AttachmentRejected {
        String type = sniff(file).orElseThrow(() -> invalid("不是 PNG、JPEG、WebP 或 GIF 图片"));
        long bytes = Files.size(file);
        try (ImageInputStream in = new FileImageInputStream(file.toFile())) {
            ImageScan scan = scan(in, type);
            ImageReader reader = reader(type);
            try {
                in.seek(0);
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                checkSize(width, height);
                // 很大的图片隔行隔列地解，解出来的长边仍不小于目标的两倍，再用双三次缩小。省内存，也快。
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(width, height) / (2 * STORED.maxEdge()));
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                BufferedImage decoded = reader.read(0, param);
                boolean clean = width <= STORED.maxEdge() && height <= STORED.maxEdge() && bytes <= STORED.maxBytes()
                        && scan.orientation() == 1 && !scan.metadata() && !scan.animated();
                if (clean) {
                    return new Normalized(Files.readAllBytes(file), type, width, height, null, null);
                }
                boolean swap = scan.orientation() >= 5;
                return fit(decoded, scan.orientation(), swap ? height : width, swap ? width : height, STORED);
            } catch (IOException | RuntimeException e) {
                throw invalid("图片无法解码");
            } finally {
                reader.dispose();
            }
        }
    }

    private static ImageScan scan(ImageInputStream in, String type) throws AttachmentRejected {
        try {
            return ImageScan.of(in, type);
        } catch (IOException e) {
            throw invalid("图片文件不完整或已损坏");
        }
    }

    private void checkSize(int width, int height) throws AttachmentRejected {
        if (width > limits.maxImageEdge() || height > limits.maxImageEdge()) {
            throw new AttachmentRejected(Reason.IMAGE_TOO_LARGE,
                    "图片边长 " + width + "×" + height + " 超过上限 " + limits.maxImageEdge() + " 像素");
        }
        if ((long) width * height > limits.maxImagePixels()) {
            throw new AttachmentRejected(Reason.IMAGE_TOO_LARGE,
                    "图片像素 " + width + "×" + height + " 超过上限 " + limits.maxImagePixels() + " 像素");
        }
    }

    /**
     * 转正、缩到目标以内、编码。{@code width}、{@code height} 是转正后的原尺寸（解码时可能隔行隔列取过）。
     * 有透明通道的编码成 PNG，没有的编码成 JPEG；超过字节上限时按 0.75 倍再缩，缩到 1×1 还放不下就报错。
     * 请求版本（{@code RequestImages}）也走这里。
     */
    static Normalized fit(BufferedImage decoded, int orientation, int width, int height, Target target) throws IOException {
        boolean alpha = decoded.getColorModel().hasAlpha();
        boolean swap = orientation >= 5;
        double scale = Math.min(1.0, (double) target.maxEdge() / Math.max(width, height));
        while (true) {
            int w = Math.max(1, (int) Math.round(width * scale));
            int h = Math.max(1, (int) Math.round(height * scale));
            BufferedImage upright = orient(resize(decoded, swap ? h : w, swap ? w : h, alpha), orientation);
            byte[] encoded = alpha ? png(upright) : jpeg(upright, target.maxBytes());
            if (encoded != null && encoded.length <= target.maxBytes()) {
                boolean resized = w != width || h != height;
                return new Normalized(encoded, alpha ? "image/png" : "image/jpeg", w, h,
                        resized ? width : null, resized ? height : null);
            }
            if (w == 1 && h == 1) {
                throw new IOException("image does not fit in " + target.maxBytes() + " bytes even at 1x1");
            }
            scale *= SHRINK;
        }
    }

    /** 每次最多缩一半，多次双三次，避免一次缩很多时的锯齿。结果是 INT_RGB 或 INT_ARGB。 */
    private static BufferedImage resize(BufferedImage source, int width, int height, boolean alpha) {
        BufferedImage current = source;
        do {
            int w = Math.max(width, current.getWidth() / 2);
            int h = Math.max(height, current.getHeight() / 2);
            BufferedImage next = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(current, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            current = next;
        } while (current.getWidth() != width || current.getHeight() != height);
        return current;
    }

    /** 按 EXIF 方向 2–8 翻转或旋转，得到正着看的图。 */
    static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return source;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        boolean swap = orientation >= 5;
        int ow = swap ? h : w;
        int oh = swap ? w : h;
        int[] in = source.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx;
                int dy;
                switch (orientation) {
                    case 2 -> { dx = w - 1 - x; dy = y; }          // 水平翻转
                    case 3 -> { dx = w - 1 - x; dy = h - 1 - y; }  // 转 180°
                    case 4 -> { dx = x; dy = h - 1 - y; }          // 垂直翻转
                    case 5 -> { dx = y; dy = x; }                  // 沿主对角线翻转
                    case 6 -> { dx = h - 1 - y; dy = x; }          // 顺时针 90°
                    case 7 -> { dx = h - 1 - y; dy = w - 1 - x; }  // 沿副对角线翻转
                    default -> { dx = y; dy = w - 1 - x; }         // 8：逆时针 90°
                }
                out[dy * ow + dx] = in[y * w + x];
            }
        }
        BufferedImage target = new BufferedImage(ow, oh, source.getType());
        target.setRGB(0, 0, ow, oh, out, 0, ow);
        return target;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("no PNG writer");
        }
        return out.toByteArray();
    }

    /** 质量依次试 0.92、0.85、0.75、0.6，第一个不超过上限的为准；都超过时返回 null，由调用方再缩。 */
    private static byte[] jpeg(BufferedImage image, long maxBytes) throws IOException {
        ImageWriter writer = writer("image/jpeg");
        try {
            for (float quality : JPEG_QUALITIES) {
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                    writer.setOutput(stream);
                    writer.write(null, new IIOImage(image, null, null), param);
                }
                if (out.size() <= maxBytes) {
                    return out.toByteArray();
                }
            }
            return null;
        } finally {
            writer.dispose();
        }
    }

    private static ImageReader reader(String mediaType) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(mediaType);
        if (!readers.hasNext()) {
            throw new IOException("no reader for " + mediaType);
        }
        return readers.next();
    }

    private static ImageWriter writer(String mediaType) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(mediaType);
        if (!writers.hasNext()) {
            throw new IOException("no writer for " + mediaType);
        }
        return writers.next();
    }

    private static AttachmentRejected invalid(String message) {
        return new AttachmentRejected(Reason.INVALID_IMAGE, message);
    }
}
