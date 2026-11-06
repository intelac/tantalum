package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tantalum.local.fs.AllowedDirs;
import tantalum.value.json.Json;

/**
 * read_file 在电脑上读到图片和 PDF（L7、设计“一次调用”第 5 步）：图片按文件头认，原始字节送回服务器，由服务器规范化；PDF 和服务器上
 * 原来一样拒绝。附件路径不再认，改由服务器的 read_attachment 读。
 */
class ReadFileImagesTest {

    @TempDir
    Path root;

    private final ReadFile read = new ReadFile();
    private final Observations observations = new Observations();
    private LocalContext context;

    @BeforeEach
    void setUp() throws Exception {
        context = new LocalContext("s", new AllowedDirs(List.of(root)), observations);
    }

    private LocalResult read(String path) {
        return read.run(context, new Json.Obj(Map.of("path", new Json.Str(path))));
    }

    private static byte[] sample(String name) throws Exception {
        try (InputStream in = ReadFileImagesTest.class.getResourceAsStream("/samples/" + name)) {
            return in.readAllBytes();
        }
    }

    /** 四种图片都按文件头认，不看后缀；字节原样送回。 */
    @ParameterizedTest
    @CsvSource({"rotated.jpg, image/jpeg", "clean.png, image/png", "clean.gif, image/gif", "clean.webp, image/webp"})
    void imagesComeBackAsRawBytes(String sample, String mime) throws Exception {
        byte[] bytes = sample(sample);
        Files.write(root.resolve("picture.bin"), bytes);
        LocalResult output = read("picture.bin");
        assertFalse(output.error(), output.text());
        assertEquals(1, output.images().size());
        assertEquals(mime, output.images().getFirst().mime());
        assertArrayEquals(bytes, output.images().getFirst().bytes());
        assertEquals("Image picture.bin (" + mime + ", " + bytes.length + " bytes).", output.text());
    }

    /** 送回服务器的一帧最多 16 MB，base64 后的图片要放得下：超过 10 MB 不送。 */
    @Test
    void imagesOver10MbAreRefused() throws Exception {
        Path big = root.resolve("big.png");
        Files.write(big, sample("clean.png"));
        try (RandomAccessFile file = new RandomAccessFile(big.toFile(), "rw")) {
            file.setLength(ReadFile.MAX_IMAGE_BYTES + 1);
        }
        LocalResult output = read("big.png");
        assertTrue(output.error());
        assertEquals("Image too large: big.png is over 10 MB", output.text());
        assertTrue(output.images().isEmpty());
    }

    @Test
    void pdfsAreNotReturnedAsText() throws Exception {
        Files.write(root.resolve("local.pdf"), sample("two-pages.pdf"));
        LocalResult local = read("local.pdf");
        assertTrue(local.error());
        assertEquals("read_file cannot read PDF contents: local.pdf", local.text());
    }

    /** 服务器附件的只读路径在电脑上只是一个不存在的绝对路径，和越界一样报“找不到”。 */
    @Test
    void attachmentPathsAreNotSpecialAnyMore() {
        LocalResult output = read("/attachments/sha256-" + "0".repeat(64));
        assertTrue(output.error());
        assertTrue(output.text().startsWith(AllowedDirs.NOT_FOUND), output.text());
        assertFalse(read.description().contains("attachment"));
    }

    /** SVG 是文本，照文本读。 */
    @Test
    void svgIsReadAsText() throws Exception {
        Files.writeString(root.resolve("logo.svg"), "<svg/>\n", StandardCharsets.UTF_8);
        LocalResult output = read("logo.svg");
        assertEquals("<svg/>", output.text());
        assertTrue(output.images().isEmpty());
    }
}
