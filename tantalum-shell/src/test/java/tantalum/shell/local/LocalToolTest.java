package tantalum.shell.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.tool.Tools;
import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.attachment.ImageNormalizer;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/** 转发工具（local-workspace 任务 13，L6、L7、L8，需求 3.3）：结果照样变成 {@code ToolOutput}，图片存进附件目录，没连上时直接报错。 */
class LocalToolTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final Duration STANDARD = Duration.ofSeconds(10);

    @TempDir
    Path temp;

    private LocalTokens tokens;
    private LocalLinks links;
    private AttachmentStore store;
    private Path computer;
    private Map<String, Tool> tools;

    @BeforeEach
    void setUp() throws Exception {
        tokens = LocalTokens.open(temp.resolve("data"), java.time.Clock.systemUTC());
        links = new LocalLinks(tokens, java.time.Clock.systemUTC(), LocalLinks.Timing.DEFAULT);
        store = new AttachmentStore(temp.resolve("data"), AttachmentLimits.defaults(), new ImageNormalizer(AttachmentLimits.defaults()));
        computer = Files.createDirectories(temp.resolve("mac"));
        tools = new java.util.LinkedHashMap<>();
        LocalTool.all(links, new tantalum.shell.attachment.ToolImages(store), STANDARD).forEach(tool -> tools.put(tool.name(), tool));
    }

    @AfterEach
    void tearDown() throws Exception {
        links.close();
        tokens.close();
    }

    private ToolOutput run(Principal who, String tool, Map<String, Json> arguments) throws Exception {
        Path server = Files.createDirectories(temp.resolve("server").resolve(who.value()));
        return tools.get(tool).run(new ToolContext(who, new SessionId("s-1"), new Workspace(server)), new Json.Obj(arguments));
    }

    private TestComputer connect(boolean write) throws Exception {
        return TestComputer.connect(links, ALICE, Instant.now().plus(Duration.ofHours(1)), computer, write, false);
    }

    @Test
    void definitionsComeFromTheSharedModule() {
        assertEquals(Tools.all().stream().map(tantalum.local.tool.LocalTool::name).toList(), List.copyOf(tools.keySet()));
        for (Tool tool : tools.values()) {
            assertEquals(LocalTool.SERVER, tool.server());
            assertEquals(64, tool.serverConcurrency());
        }
    }

    /** 没有连接时立即返回错误，不等超时（设计“一次调用”第 3 步）。 */
    @Test
    void withoutAComputerTheCallFailsAtOnce() throws Exception {
        long started = System.nanoTime();
        ToolOutput output = run(ALICE, "read_file", Map.of("path", new Json.Str("a.txt")));
        assertTrue(output.error());
        assertEquals("The local workspace is not connected.", output.text());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0);
    }

    @Test
    void resultsAndErrorsFromTheComputerBecomeToolOutput() throws Exception {
        Files.writeString(computer.resolve("a.txt"), "hello from the mac\n");
        try (TestComputer ignored = connect(false)) {
            ToolOutput read = run(ALICE, "read_file", Map.of("path", new Json.Str("a.txt")));
            assertFalse(read.error(), read.text());
            assertEquals("hello from the mac", read.text());

            ToolOutput missing = run(ALICE, "read_file", Map.of("path", new Json.Str("../outside.txt")));
            assertTrue(missing.error());
            assertTrue(missing.text().startsWith("path not found in the allowed directories"), missing.text());

            // 电脑没给 --allow-write：服务器硬发也被电脑拒绝（L4）
            ToolOutput write = run(ALICE, "write_file", Map.of("path", new Json.Str("b.txt"), "content", new Json.Str("x")));
            assertTrue(write.error());
            assertEquals("This computer does not allow file changes.", write.text());
            assertFalse(Files.exists(computer.resolve("b.txt")));

            // 别人的调用不会落到这台电脑上
            assertEquals("The local workspace is not connected.", run(BOB, "read_file", Map.of("path", new Json.Str("a.txt"))).text());
        }
    }

    /** 电脑上的图片：服务器规范化后存进本人的附件目录，重复读不重复存，交给模型的是规范化后的引用（从原 read_file 的测试搬来）。 */
    @Test
    void imagesAreNormalizedAndStoredAsTheUsersAttachments() throws Exception {
        try (InputStream in = LocalToolTest.class.getResourceAsStream("/attachments/rotated.jpg")) {
            Files.write(computer.resolve("photo.jpg"), in.readAllBytes());
        }
        try (TestComputer ignored = connect(false)) {
            ToolOutput first = run(ALICE, "read_file", Map.of("path", new Json.Str("photo.jpg")));
            assertFalse(first.error(), first.text());
            ImageRef image = first.images().getFirst();
            assertEquals(8, image.width());
            assertEquals(16, image.height());
            assertEquals("photo.jpg", image.name());
            assertEquals(AttachmentText.image(image), first.text());
            assertTrue(store.find(ALICE, image.id()).isPresent());
            long used = store.used(ALICE);
            assertEquals(image.id(), run(ALICE, "read_file", Map.of("path", new Json.Str("photo.jpg"))).images().getFirst().id());
            assertEquals(used, store.used(ALICE));
            assertTrue(store.find(BOB, image.id()).isEmpty());
        }
    }

    /** 调用途中断开：结果是“不确定”，不重发（L8）。 */
    @Test
    void aDisconnectDuringTheCallIsReportedAsUnknown() throws Exception {
        Files.writeString(computer.resolve("a.txt"), "x");
        TestComputer mac = connect(true);
        mac.holdCalls();
        CompletableFuture<ToolOutput> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return run(ALICE, "edit_file", Map.of("path", new Json.Str("a.txt"), "old_string", new Json.Str("x"),
                        "new_string", new Json.Str("y")));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertNotNull(mac.nextCall(5));
        mac.disconnect();
        ToolOutput output = pending.get(5, TimeUnit.SECONDS);
        assertTrue(output.error());
        assertEquals("The computer disconnected. Whether this operation finished is unknown.", output.text());
        assertTrue(mac.nextCall(1) == null, "not sent again");
    }
}
