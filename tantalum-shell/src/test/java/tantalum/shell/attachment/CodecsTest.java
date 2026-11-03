package tantalum.shell.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.twelvemonkeys.imageio.metadata.Directory;
import com.twelvemonkeys.imageio.metadata.jpeg.JPEG;
import com.twelvemonkeys.imageio.metadata.jpeg.JPEGSegment;
import com.twelvemonkeys.imageio.metadata.jpeg.JPEGSegmentUtil;
import com.twelvemonkeys.imageio.metadata.tiff.TIFF;
import com.twelvemonkeys.imageio.metadata.tiff.TIFFReader;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/** 任务 1：新依赖能做附件要用的三件事。JDK 自带的 ImageIO 读不了 WebP，也不看 EXIF 方向，PDF 更是没有。 */
class CodecsTest {

    private static InputStream sample(String name) {
        return CodecsTest.class.getResourceAsStream("/attachments/" + name);
    }

    @Test
    void readsWebP() throws Exception {
        try (InputStream in = sample("photo.webp")) {
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "没有找到 WebP 解码器");
            assertEquals(6, image.getWidth());
            assertEquals(4, image.getHeight());
        }
    }

    /** 手机照片常是横着存、靠 EXIF 方向标记竖着显示；像素按存储方向解出来，方向要另外读。 */
    @Test
    void readsExifOrientationOfJpeg() throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(sample("rotated.jpg"))) {
            List<JPEGSegment> exif = JPEGSegmentUtil.readSegments(in, JPEG.APP1, "Exif");
            assertEquals(1, exif.size());
            InputStream data = exif.getFirst().data();
            data.read(); // "Exif" 后面的补零字节
            try (ImageInputStream tiff = ImageIO.createImageInputStream(data)) {
                Directory directory = new TIFFReader().read(tiff);
                assertEquals(6, ((Number) directory.getEntryById(TIFF.TAG_ORIENTATION).getValue()).intValue());
            }
        }
        try (InputStream in = sample("rotated.jpg")) {
            BufferedImage image = ImageIO.read(in);
            assertEquals(16, image.getWidth());
            assertEquals(8, image.getHeight());
        }
    }

    @Test
    void countsPdfPages() throws Exception {
        try (InputStream in = sample("two-pages.pdf");
                PDDocument document = Loader.loadPDF(in.readAllBytes())) {
            assertEquals(2, document.getNumberOfPages());
        }
    }
}
