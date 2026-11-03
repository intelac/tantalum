package tantalum.shell.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import tantalum.value.json.Json;

/** 任务 17：read_file 读本人的附件路径和图片（设计第 6 节，需求 3.5、3.7、A8）。 */
class ReadFileAttachmentsTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    @TempDir
    Path temp;

    private AttachmentStore store;
    private ReadFileTool read;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        store = new AttachmentStore(temp.resolve("data"), AttachmentLimits.defaults(), new ImageNormalizer(AttachmentLimits.defaults()));
        read = new ReadFileTool(store);
        root = Files.createDirectories(temp.resolve("ws"));
    }

    private ToolOutput run(Principal who, String path) throws Exception {
        return read.run(new ToolContext(who, new tantalum.value.id.SessionId("s"), new Workspace(root)), new Json.Obj(Map.of("path", new Json.Str(path))));
    }

    private AttachmentRef upload(Principal who, String name, byte[] bytes) throws Exception {
        return store.upload(who, name, null, new ByteArrayInputStream(bytes), bytes.length);
    }

    private static byte[] sample(String name) throws Exception {
        try (InputStream in = ReadFileAttachmentsTest.class.getResourceAsStream("/attachments/" + name)) {
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

    @Test
    void anImageAttachmentIsShownToTheModel() throws Exception {
        ImageRef image = assertInstanceOf(ImageRef.class, upload(ALICE, "cat.jpg", sample("rotated.jpg")));
        ToolOutput output = run(ALICE, image.readonlyPath());
        assertFalse(output.error());
        assertEquals(1, output.images().size());
        assertEquals(image.id(), output.images().getFirst().id());
        assertEquals(AttachmentText.image(output.images().getFirst()), output.text());
    }

    /** 工作区里的图片：规范化后存进本人的附件目录，重复读不重复存，交给模型的是规范化后的引用。 */
    @Test
    void aWorkspaceImageIsNormalizedStoredAndShown() throws Exception {
        Files.write(root.resolve("photo.jpg"), sample("rotated.jpg"));
        ToolOutput first = run(ALICE, "photo.jpg");
        ImageRef image = first.images().getFirst();
        assertEquals(8, image.width());
        assertEquals(16, image.height());
        assertEquals("photo.jpg", image.name());
        assertTrue(store.find(ALICE, image.id()).isPresent());
        long used = store.used(ALICE);
        assertEquals(image.id(), run(ALICE, "photo.jpg").images().getFirst().id());
        assertEquals(used, store.used(ALICE));
        assertTrue(store.find(BOB, image.id()).isEmpty());
    }

    @Test
    void pdfsAreNotReturnedAsText() throws Exception {
        AttachmentRef pdf = upload(ALICE, "report.pdf", sample("two-pages.pdf"));
        ToolOutput attached = run(ALICE, pdf.readonlyPath());
        assertFalse(attached.error());
        assertTrue(attached.text().contains("is a PDF (2 pages)"), attached.text());
        Files.write(root.resolve("local.pdf"), sample("two-pages.pdf"));
        ToolOutput local = run(ALICE, "local.pdf");
        assertTrue(local.error());
        assertEquals("read_file cannot read PDF contents: local.pdf", local.text());
    }

    /** 附件的只读路径不在工作区里：工作区拒绝它，所以任何经工作区写文件的工具都写不到附件。 */
    @Test
    void theAttachmentPrefixIsNotAWorkspacePath() throws Exception {
        AttachmentRef notes = upload(ALICE, "notes.txt", "x".getBytes(StandardCharsets.UTF_8));
        Workspace workspace = new Workspace(root);
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve(notes.readonlyPath()));
        assertEquals(List.of(), List.of(Files.list(root).toArray()));
    }

    /** 没有附件目录时图片不交给模型；JPEG 里有空字节，按 core-tools C4 当二进制拒绝。 */
    @Test
    void withoutAStoreTheOldBehaviourStays() throws Exception {
        Files.write(root.resolve("photo.jpg"), sample("rotated.jpg"));
        ToolOutput output = new ReadFileTool().run(new ToolContext(ALICE, new tantalum.value.id.SessionId("s"), new Workspace(root)), new Json.Obj(Map.of("path", new Json.Str("photo.jpg"))));
        assertTrue(output.error());
        assertEquals("Binary file, not shown: photo.jpg", output.text());
    }
}
