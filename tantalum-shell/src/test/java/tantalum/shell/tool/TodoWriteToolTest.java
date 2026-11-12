package tantalum.shell.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/** todo-tool 需求 1：工具的定义钉住；合格回一行进度，不合格回错误让模型自己改。 */
class TodoWriteToolTest {

    private static final TodoWriteTool TOOL = new TodoWriteTool();

    private static Json todos(String... items) {
        java.util.List<Json> list = new java.util.ArrayList<>();
        for (String item : items) {
            String[] parts = item.split(":", 2);
            list.add(new Json.Obj(new java.util.LinkedHashMap<>(Map.of("content", new Json.Str(parts[1]), "status", new Json.Str(parts[0])))));
        }
        return new Json.Obj(Map.of("todos", new Json.Arr(list)));
    }

    @Test
    void theDefinitionIsPinned() {
        assertEquals("todo_write", TOOL.name());
        assertTrue(TOOL.description().startsWith("Record and update a structured task list for the current work. Send the ENTIRE list"));
        assertTrue(TOOL.description().contains("Keep exactly one todo in_progress while work remains."));
        Json.Obj schema = (Json.Obj) TOOL.schema();
        assertEquals(new Json.Arr(List.of(new Json.Str("todos"))), schema.fields().get("required"));
        assertEquals(new Json.Bool(false), schema.fields().get("additionalProperties"));
        Json.Obj item = (Json.Obj) ((Json.Obj) ((Json.Obj) schema.fields().get("properties")).fields().get("todos")).fields().get("items");
        assertEquals(new Json.Bool(false), item.fields().get("additionalProperties"), "no activeForm, ids or priorities");
        Json.Obj status = (Json.Obj) ((Json.Obj) item.fields().get("properties")).fields().get("status");
        assertEquals(new Json.Arr(List.of(new Json.Str("pending"), new Json.Str("in_progress"), new Json.Str("completed"))), status.fields().get("enum"));
        assertEquals("todos", TOOL.server());
    }

    @Test
    void aValidListAnswersWithTheProgressAndAnInvalidOneWithTheReason() {
        ToolOutput ok = TOOL.run(null, todos("completed:Read the failing test", "in_progress:Fix apply_discount", "pending:Run all tests"));
        assertFalse(ok.error());
        assertEquals("Todo list updated: 1 of 3 completed; in progress: Fix apply_discount.", ok.text());
        ToolOutput refused = TOOL.run(null, todos("in_progress:a", "in_progress:b"));
        assertTrue(refused.error());
        assertEquals("Invalid todos: at most one item may be in_progress (got 2).", refused.text());
    }
}
