package tantalum.shell.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.tool.Workspaces;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

class MemoryToolsTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    @TempDir
    Path temp;

    private Workspaces workspaces;
    private MemoryStores stores;
    private MemoryWriteTool write;
    private MemoryReadTool read;
    private MemoryDeleteTool delete;

    @BeforeEach
    void setUp() {
        workspaces = Workspaces.perUser(temp.resolve("ws"));
        stores = new MemoryStores(workspaces, temp.resolve("data"), new MemoryLimits(2, 16 * 1024, 64), Clock.systemUTC());
        write = new MemoryWriteTool(stores);
        read = new MemoryReadTool(stores);
        delete = new MemoryDeleteTool(stores);
    }

    private ToolContext as(Principal principal) {
        return new ToolContext(principal, workspaces.of(principal));
    }

    private static Json args(String... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put(pairs[i], new Json.Str(pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    private ToolOutput save(Principal who, String name, String content) {
        return write.run(as(who), args("name", name, "type", "user", "description", "about " + name, "content", content));
    }

    @Test
    void writeCreatesThenUpdates() {
        ToolOutput created = save(ALICE, "java", "Java 25");
        assertFalse(created.error());
        assertEquals("Remembered java.", created.text());
        ToolOutput updated = save(ALICE, "java", "Java 25, always");
        assertEquals("Updated java.", updated.text());
        assertEquals("Java 25, always", stores.of(ALICE).read("java").orElseThrow().content());
    }

    @Test
    void writeReportsEveryKindOfRejection() {
        assertTrue(write.run(as(ALICE), args("name", "x")).text().contains("Missing argument: type"));
        assertTrue(write.run(as(ALICE), args("name", "Bad Name", "type", "user", "description", "d", "content", "c")).text().contains("name"));
        assertTrue(write.run(as(ALICE), args("name", "x", "type", "video", "description", "d", "content", "c")).text().contains("type"));
        assertTrue(write.run(as(ALICE), args("name", "x", "type", "user", "description", "", "content", "c")).text().contains("description"));
        assertTrue(write.run(as(ALICE), args("name", "x", "type", "user", "description", "d", "content", "y".repeat(65))).text().contains("65 bytes"));
        ToolOutput secret = write.run(as(ALICE), args("name", "x", "type", "user", "description", "d", "content", "token=abcdef123456"));
        assertTrue(secret.error());
        assertEquals(CredentialGuard.REASON, secret.text());
        save(ALICE, "a", "1");
        save(ALICE, "b", "2");
        ToolOutput full = save(ALICE, "c", "3");
        assertTrue(full.error());
        assertTrue(full.text().contains("memory is full (2/2"), full.text());
    }

    @Test
    void readReturnsHeaderAndBodyOrNotFound() {
        save(ALICE, "java", "Use Java 25.");
        ToolOutput found = read.run(as(ALICE), args("name", "java"));
        assertFalse(found.error());
        assertTrue(found.text().startsWith("name: java\ntype: user\ndescription: about java\nupdated: "), found.text());
        assertTrue(found.text().endsWith("\n\nUse Java 25."), found.text());
        assertEquals("No memory named nope.", read.run(as(ALICE), args("name", "nope")).text());
        assertTrue(read.run(as(ALICE), args()).text().contains("Missing argument: name"));
    }

    @Test
    void deleteRemovesOrReportsNotFound() {
        save(ALICE, "java", "x");
        assertEquals("Deleted java.", delete.run(as(ALICE), args("name", "java")).text());
        assertTrue(stores.of(ALICE).read("java").isEmpty());
        ToolOutput again = delete.run(as(ALICE), args("name", "java"));
        assertTrue(again.error());
        assertEquals("No memory named java.", again.text());
    }

    @Test
    void usersCannotSeeEachOther() {
        save(ALICE, "alice-only", "secret preference");
        assertTrue(read.run(as(BOB), args("name", "alice-only")).error());
        assertTrue(delete.run(as(BOB), args("name", "alice-only")).error());
        assertTrue(stores.of(ALICE).read("alice-only").isPresent());
    }

    @Test
    void outputsNeverContainServerPaths() {
        save(ALICE, "java", "x");
        for (ToolOutput output : List.of(save(ALICE, "java", "y"), read.run(as(ALICE), args("name", "java")),
                read.run(as(ALICE), args("name", "nope")), delete.run(as(ALICE), args("name", "java")),
                write.run(as(ALICE), args("name", "../x", "type", "user", "description", "d", "content", "c")))) {
            assertFalse(output.text().contains(temp.toString()), output.text());
        }
    }

    @Test
    void schemasDeclareRequiredArgumentsAndShareOneServer() {
        for (Tool tool : MemoryTools.all(stores)) {
            Json.Obj schema = (Json.Obj) tool.schema();
            Json.Arr required = (Json.Arr) schema.fields().get("required");
            assertFalse(required.items().isEmpty(), tool.name());
            assertEquals("memory", tool.server());
            assertTrue(tool.isConcurrencySafe(args()));
        }
        assertEquals(new Json.Arr(List.of(new Json.Str("name"), new Json.Str("type"), new Json.Str("description"), new Json.Str("content"))),
                ((Json.Obj) write.schema()).fields().get("required"));
        ToolCatalog catalog = new ToolCatalog();
        MemoryTools.all(stores).forEach(catalog::register);
        assertEquals(List.of("memory_delete", "memory_read", "memory_write"),
                catalog.specs().stream().map(spec -> spec.name()).toList());
    }

    /** 需求 3.5：补齐的凭据格式都被拒绝；不含数字的长 sk- 名称不再误伤。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "github_pat_11ABCDEFG0abcdefghijklmnop", "glpat-abcdefghij0123456789", "npm_abcdefghijklmnopqrstuvwxyz0123456789",
        "hf_abcdefghijklmnopqrstuvwxyz012345", "AIzaSyA1234567890abcdefghijklmnopqrstuvw", "ASIAABCDEFGHIJKLMNOP",
        "LTAI5tAbCdEfGhIjKlMn", "AKIDabcdefghij1234567", "whsec_abcdefghijklmnopqrstuvwx", "sk_live_abcdefghij0123456789",
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijklmnop", "postgres://admin:hunter2@db.example.com/x"})
    void moreCredentialFormatsAreRefused(String secret) {
        assertTrue(CredentialGuard.looksLikeCredential("value " + secret), secret);
    }

    @Test
    void longKebabNamesAreNotMistakenForKeys() {
        assertFalse(CredentialGuard.looksLikeCredential("see sk-learn-pipeline-tuning-notes for this"));
        assertTrue(CredentialGuard.looksLikeCredential("sk-abc123def456ghi789jkl"));
    }

    /** 需求 3.4：坏行跳过，好行留下。 */
    @Test
    void indexTextSkipsBadLines() throws Exception {
        save(ALICE, "java", "x");
        MemoryStore store = stores.of(ALICE);
        Path index = store.dir().resolve(MemoryFormat.INDEX_FILE);
        String good = java.nio.file.Files.readString(index);
        java.nio.file.Files.writeString(index, good + "this line is garbage\n");
        assertEquals(good.strip(), store.indexText().strip());
    }

    /** 需求 3.3：索引超限时截断并告诉模型。 */
    @Test
    void oversizedIndexIsCutWithANotice() throws Exception {
        MemoryStores small = new MemoryStores(workspaces, temp.resolve("data2"), new MemoryLimits(50, 200, 64), Clock.systemUTC());
        MemoryStore store = small.of(ALICE);
        java.nio.file.Files.createDirectories(store.dir());
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            many.append(MemoryFormat.formatIndexLine(new MemoryEntry("n" + i, MemoryType.USER, "about " + i, "c", Instant.EPOCH))).append('\n');
        }
        java.nio.file.Files.writeString(store.dir().resolve(MemoryFormat.INDEX_FILE), many.toString());
        String text = store.indexText();
        assertTrue(text.contains("n0"));
        assertFalse(text.contains("n39"));
        assertTrue(text.contains("truncated"), text);
    }

    /** 需求 3.6：写入后不留临时文件，已存在的路径不会被跟随。 */
    @Test
    void tempFilesAreRandomAndLeaveNothingBehind() throws Exception {
        save(ALICE, "java", "x");
        Path dir = stores.of(ALICE).dir();
        java.nio.file.Files.createSymbolicLink(dir.resolve("java.md.tmp"), temp.resolve("outside"));
        save(ALICE, "java", "y");
        assertFalse(java.nio.file.Files.exists(temp.resolve("outside")), "the planted link was not followed");
        try (var files = java.nio.file.Files.list(dir)) {
            assertEquals(1, files.filter(file -> file.getFileName().toString().endsWith(".tmp")).count(), "only the planted link");
        }
    }
}
