package tantalum.shell.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
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
        assertEquals(List.of("memory_write", "memory_read", "memory_delete"),
                catalog.specs().stream().map(spec -> spec.name()).toList());
    }
}
