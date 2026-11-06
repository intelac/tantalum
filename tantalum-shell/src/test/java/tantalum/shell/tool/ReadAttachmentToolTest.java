package tantalum.shell.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.attachment.ImageNormalizer;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/**
 * {@code read_attachment} 读本人的附件（local-workspace L7，需求 3.4）。用例从原来 {@code read_file} 的附件测试搬来，断言不变；
 * 读电脑上图片的那条搬到了 {@code LocalToolTest}。
 */
class ReadAttachmentToolTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    @TempDir
    Path temp;

    private AttachmentStore store;
    private ReadAttachmentTool read;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        store = new AttachmentStore(temp.resolve("data"), AttachmentLimits.defaults(), new ImageNormalizer(AttachmentLimits.defaults()));
        read = new ReadAttachmentTool(store);
        root = Files.createDirectories(temp.resolve("ws"));
    }

    private ToolOutput run(Principal who, String path) throws Exception {
        return run(who, Map.of("path", new Json.Str(path)));
    }

    private ToolOutput run(Principal who, Map<String, Json> arguments) throws Exception {
        return read.run(new ToolContext(who, new SessionId("s"), new Workspace(root)), new Json.Obj(arguments));
    }

    private AttachmentRef upload(Principal who, String name, byte[] bytes) throws Exception {
        return store.upload(who, name, null, new ByteArrayInputStream(bytes), bytes.length);
    }

    private static byte[] sample(String name) throws Exception {
        try (InputStream in = ReadAttachmentToolTest.class.getResourceAsStream("/attachments/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void readsOwnAttachmentsByTheirReadonlyPath() throws Exception {
        AttachmentRef notes = upload(ALICE, "notes.txt", "the secret word is plover".getBytes(StandardCharsets.UTF_8));
        ToolOutput output = run(ALICE, notes.readonlyPath());
        assertFalse(output.error(), output.text());
        assertEquals("the secret word is plover", output.text());
    }

    @Test
    void someoneElsesHashIsLikeAMissingFile() throws Exception {
        AttachmentRef notes = upload(ALICE, "notes.txt", "mine".getBytes(StandardCharsets.UTF_8));
        ToolOutput output = run(BOB, notes.readonlyPath());
        assertTrue(output.error());
        assertEquals("Not a file: " + notes.readonlyPath(), output.text());
        assertTrue(run(ALICE, "/attachments/sha256-" + "0".repeat(64)).error());
        assertTrue(run(ALICE, "/attachments/sha256-../../bob").error());
    }

    /** 服务器上不读附件以外的任何文件（Yi 2026-11-06）：工作目录里的文件、绝对路径都不读，并告诉模型电脑上的文件用 read_file。 */
    @Test
    void readsNothingButAttachments() throws Exception {
        Files.writeString(root.resolve("notes.txt"), "server file");
        for (String path : new String[] {"notes.txt", root.resolve("notes.txt").toString(), "/etc/hosts"}) {
            ToolOutput output = run(ALICE, path);
            assertTrue(output.error(), path);
            assertEquals("Not an attachment: " + path + ". Files on the user's computer are read with read_file.", output.text());
        }
    }

    @Test
    void anImageAttachmentIsShownToTheModel() throws Exception {
        ImageRef image = assertInstanceOf(ImageRef.class, upload(ALICE, "cat.jpg", sample("rotated.jpg")));
        ToolOutput output = run(ALICE, image.readonlyPath());
        assertFalse(output.error());
        assertEquals(1, output.images().size());
        assertEquals(image.id(), output.images().getFirst().id());
        assertEquals(AttachmentText.image(output.images().getFirst()), output.text());
    }

    @Test
    void pdfsAreNotReturnedAsText() throws Exception {
        AttachmentRef pdf = upload(ALICE, "report.pdf", sample("two-pages.pdf"));
        ToolOutput attached = run(ALICE, pdf.readonlyPath());
        assertFalse(attached.error());
        assertTrue(attached.text().contains("is a PDF (2 pages)"), attached.text());
        assertTrue(attached.text().contains("read_attachment cannot read PDF contents"), attached.text());
    }

    /** 文本附件按行窗口读，续读说明和电脑上的 read_file 相同（同一份代码）。 */
    @Test
    void longTextAttachmentsAreReadInWindows() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            text.append("line ").append(i).append('\n');
        }
        AttachmentRef notes = upload(ALICE, "lines.txt", text.toString().getBytes(StandardCharsets.UTF_8));
        Map<String, Json> arguments = new LinkedHashMap<>();
        arguments.put("path", new Json.Str(notes.readonlyPath()));
        arguments.put("offset", new Json.Num(new java.math.BigDecimal(2)));
        arguments.put("limit", new Json.Num(new java.math.BigDecimal(2)));
        ToolOutput output = run(ALICE, arguments);
        assertFalse(output.error(), output.text());
        assertEquals("line 2\nline 3\n\n[Showing lines 2-3 of 5. Use offset=4 to continue.]", output.text());
        arguments.put("offset", new Json.Num(new java.math.BigDecimal(0)));
        assertEquals("offset must be 1 or more", run(ALICE, arguments).text());
    }
}
