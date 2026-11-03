package tantalum.shell.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.attachment.AttachmentRejected.Reason;
import tantalum.shell.attachment.ImageNormalizer.Normalized;

/** 任务 4：设计第 3 节的每一步。小样例在 {@code resources/attachments}，大图在这里现画。 */
class ImageNormalizerTest {

    @TempDir
    Path temp;

    private final ImageNormalizer normalizer = new ImageNormalizer(AttachmentLimits.defaults());

    private Path sample(String name) throws IOException {
        try (InputStream in = ImageNormalizerTest.class.getResourceAsStream("/attachments/" + name)) {
            Path file = temp.resolve(name);
            Files.write(file, in.readAllBytes());
            return file;
        }
    }

    private Path write(String name, byte[] bytes) throws IOException {
        return Files.write(temp.resolve(name), bytes);
    }

    private Path drawn(String name, int width, int height, boolean alpha) throws IOException {
        BufferedImage image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(255, 0, 0, alpha ? 128 : 255), width, height, Color.BLUE));
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return write(name, out.toByteArray());
    }

    private static BufferedImage decode(Normalized result) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(result.bytes()));
    }

    private static ImageScan scan(Normalized result) throws IOException {
        return ImageScan.of(new MemoryCacheImageInputStream(new ByteArrayInputStream(result.bytes())), result.mediaType());
    }

    private static boolean red(int rgb) {
        return (rgb >> 16 & 0xFF) > 150 && (rgb & 0xFF) < 100;
    }

    private static boolean blue(int rgb) {
        return (rgb & 0xFF) > 150 && (rgb >> 16 & 0xFF) < 100;
    }

    private static boolean contains(byte[] bytes, String text) {
        byte[] needle = text.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i + needle.length <= bytes.length; i++) {
            if (Arrays.equals(bytes, i, i + needle.length, needle, 0, needle.length)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void cleanImagesOfEveryFormatAreKeptByteForByte() throws Exception {
        for (String name : new String[] {"clean.png", "clean.jpg", "clean.webp", "clean.gif"}) {
            Path file = sample(name);
            Normalized result = normalizer.normalize(file);
            assertArrayEquals(Files.readAllBytes(file), result.bytes(), name);
            assertEquals(40, result.width(), name);
            assertEquals(30, result.height(), name);
            assertNull(result.sourceWidth(), name);
        }
        assertEquals("image/webp", normalizer.normalize(sample("clean.webp")).mediaType());
        assertEquals("image/gif", normalizer.normalize(sample("clean.gif")).mediaType());
    }

    /** 样例存为 16×8、左红右蓝、方向 6（顺时针 90°）：转正后 8×16，上红下蓝，不再带方向。 */
    @Test
    void exifOrientationIsAppliedAndDropped() throws Exception {
        for (String name : new String[] {"rotated.jpg", "rotated.webp"}) {
            Normalized result = normalizer.normalize(sample(name));
            assertEquals("image/jpeg", result.mediaType(), name);
            assertEquals(8, result.width(), name);
            assertEquals(16, result.height(), name);
            assertNull(result.sourceWidth(), name);
            BufferedImage image = decode(result);
            assertTrue(red(image.getRGB(4, 2)), name);
            assertTrue(blue(image.getRGB(4, 13)), name);
            assertEquals(new ImageScan(1, false, false), scan(result), name);
        }
    }

    @Test
    void everyOrientationTurnsUpright() {
        // 原图 3×2：第一行 a b c，第二行 d e f。每种方向转正后，左上角都应是 a 的位置所示。
        BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        int[] pixels = {1, 2, 3, 4, 5, 6};
        source.setRGB(0, 0, 3, 2, pixels, 0, 3);
        int[][] expected = {
                {},
                {1, 2, 3, 4, 5, 6},
                {3, 2, 1, 6, 5, 4},
                {6, 5, 4, 3, 2, 1},
                {4, 5, 6, 1, 2, 3},
                {1, 4, 2, 5, 3, 6},
                {4, 1, 5, 2, 6, 3},
                {6, 3, 5, 2, 4, 1},
                {3, 6, 2, 5, 1, 4},
        };
        for (int orientation = 1; orientation <= 8; orientation++) {
            BufferedImage upright = ImageNormalizer.orient(source, orientation);
            int[] actual = upright.getRGB(0, 0, upright.getWidth(), upright.getHeight(), null, 0, upright.getWidth());
            for (int i = 0; i < actual.length; i++) {
                actual[i] &= 0xFFFFFF;
            }
            assertArrayEquals(expected[orientation], actual, "orientation " + orientation);
            assertEquals(orientation >= 5 ? 2 : 3, upright.getWidth(), "orientation " + orientation);
        }
    }

    @Test
    void metadataIsRemoved() throws Exception {
        Normalized gps = normalizer.normalize(sample("gps.jpg"));
        assertFalse(contains(gps.bytes(), "Exif"));
        assertEquals(new ImageScan(1, false, false), scan(gps));
        assertEquals(40, gps.width());

        Normalized text = normalizer.normalize(sample("text.png"));
        assertFalse(contains(text.bytes(), "secret location"));
        assertEquals(new ImageScan(1, false, false), scan(text));
    }

    @Test
    void gifKeepsOnlyTheFirstFrame() throws Exception {
        Normalized result = normalizer.normalize(sample("animated.gif"));
        assertEquals(20, result.width());
        assertTrue(red(decode(result).getRGB(10, 10)));
        assertFalse(scan(result).animated());
    }

    @Test
    void largeImagesShrinkToFitAndRememberTheSourceSize() throws Exception {
        Normalized opaque = normalizer.normalize(drawn("wide.png", 3000, 1500, false));
        assertEquals("image/jpeg", opaque.mediaType());
        assertEquals(2048, opaque.width());
        assertEquals(1024, opaque.height());
        assertEquals(3000, opaque.sourceWidth());
        assertEquals(1500, opaque.sourceHeight());
        BufferedImage image = decode(opaque);
        assertEquals(2048, image.getWidth());

        Normalized transparent = normalizer.normalize(drawn("tall.png", 1000, 5000, true));
        assertEquals("image/png", transparent.mediaType());
        assertEquals(410, transparent.width());
        assertEquals(2048, transparent.height());
        assertEquals(5000, transparent.sourceHeight());
        assertTrue(decode(transparent).getColorModel().hasAlpha());
        assertTrue(transparent.bytes().length <= ImageNormalizer.STORED.maxBytes());
    }

    @Test
    void sameInputGivesSameBytes() throws Exception {
        Path wide = drawn("wide.png", 3000, 1500, false);
        assertArrayEquals(normalizer.normalize(wide).bytes(), normalizer.normalize(wide).bytes());
        Path rotated = sample("rotated.jpg");
        assertArrayEquals(normalizer.normalize(rotated).bytes(), new ImageNormalizer(AttachmentLimits.defaults()).normalize(rotated).bytes());
    }

    /** 头里写的尺寸超限时，在解码前就拒绝：像素数据是乱写的，真去解码会得到“无法解码”，而不是“超过上限”。 */
    @Test
    void oversizedHeaderIsRejectedBeforeDecoding() throws Exception {
        AttachmentRejected edge = assertThrows(AttachmentRejected.class,
                () -> normalizer.normalize(write("edge.png", pngHeaderOnly(70_000, 10))));
        assertEquals(Reason.IMAGE_TOO_LARGE, edge.reason());
        assertTrue(edge.getMessage().contains("边长"), edge.getMessage());
        AttachmentRejected pixels = assertThrows(AttachmentRejected.class,
                () -> normalizer.normalize(write("pixels.png", pngHeaderOnly(10_000, 10_000))));
        assertEquals(Reason.IMAGE_TOO_LARGE, pixels.reason());
        assertTrue(pixels.getMessage().contains("像素"), pixels.getMessage());
        assertEquals(Reason.INVALID_IMAGE, assertThrows(AttachmentRejected.class,
                () -> normalizer.normalize(write("small.png", pngHeaderOnly(10, 10)))).reason());
    }

    @Test
    void brokenFilesAreRejected() throws Exception {
        byte[] jpeg = Files.readAllBytes(sample("clean.jpg"));
        for (byte[] broken : new byte[][] {
                Arrays.copyOf(jpeg, jpeg.length / 2),
                Arrays.copyOf(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4}, 64),
                "GIF89a not really".getBytes(StandardCharsets.US_ASCII),
        }) {
            assertEquals(Reason.INVALID_IMAGE,
                    assertThrows(AttachmentRejected.class, () -> normalizer.normalize(write("broken", broken))).reason());
        }
    }

    /** 名字说是图片、内容不是：不认作图片（上传时据此拒绝，需求 2.2）。SVG 不算图片（A4）。 */
    @Test
    void claimedImagesMustLookLikeImages() throws Exception {
        Path text = write("photo.png", "hello".getBytes(StandardCharsets.UTF_8));
        assertTrue(ImageNormalizer.claimsImage("photo.png"));
        assertTrue(ImageNormalizer.claimsImage("IMG_1.JPEG"));
        assertFalse(ImageNormalizer.claimsImage("notes.txt"));
        assertFalse(ImageNormalizer.claimsImage("png"));
        assertEquals(Optional.empty(), ImageNormalizer.sniff(text));
        assertEquals(Reason.INVALID_IMAGE, assertThrows(AttachmentRejected.class, () -> normalizer.normalize(text)).reason());
        Path svg = write("logo.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8));
        assertEquals(Optional.empty(), ImageNormalizer.sniff(svg));
        assertFalse(ImageNormalizer.claimsImage("logo.svg"));
    }

    @Test
    void waitsForAFreeSlotAndGivesUpWhenBusy() throws Exception {
        AttachmentLimits one = new AttachmentLimits(20, 1L << 30, 1L << 30, 64_000_000, 65_535, 20, 1L << 30, 1L << 40, 1);
        ImageNormalizer busy = new ImageNormalizer(one, Duration.ofMillis(50));
        Path file = sample("clean.png");
        busy.permits.acquire();
        assertEquals(Reason.BUSY, assertThrows(AttachmentRejected.class, () -> busy.normalize(file)).reason());
        busy.permits.release();
        assertEquals(40, busy.normalize(file).width());
    }

    /** 只有签名、IHDR、一段乱写的 IDAT 和 IEND 的 PNG。结构完整，像素数据不可解。 */
    private static byte[] pngHeaderOnly(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(ihdr);
        header.writeInt(width);
        header.writeInt(height);
        header.write(new byte[] {8, 2, 0, 0, 0});
        chunk(out, "IHDR", ihdr.toByteArray());
        chunk(out, "IDAT", new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        chunk(out, "IEND", new byte[0]);
        return bytes.toByteArray();
    }

    private static void chunk(DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(name);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }
}
