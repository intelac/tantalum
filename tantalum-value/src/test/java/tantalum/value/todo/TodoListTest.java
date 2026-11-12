package tantalum.value.todo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;
import tantalum.value.todo.TodoList.Item;
import tantalum.value.todo.TodoList.Status;

/** todo-tool 需求 1：整张清单、三种状态、校验；压缩时附在摘要后的写法。 */
class TodoListTest {

    private static Json item(String content, String status) {
        Map<String, Json> fields = new LinkedHashMap<>();
        if (content != null) fields.put("content", new Json.Str(content));
        if (status != null) fields.put("status", new Json.Str(status));
        return new Json.Obj(fields);
    }

    private static Json todos(Json... items) {
        return new Json.Obj(Map.of("todos", new Json.Arr(List.of(items))));
    }

    private static String refused(Json arguments) {
        return assertThrows(IllegalArgumentException.class, () -> TodoList.parse(arguments)).getMessage();
    }

    @Test
    void parsesAndTrims() {
        TodoList list = TodoList.parse(todos(item("  Read the failing test ", "completed"), item("Fix apply_discount", "in_progress"),
                item("Run all tests", "pending")));
        assertEquals(List.of(new Item("Read the failing test", Status.COMPLETED), new Item("Fix apply_discount", Status.IN_PROGRESS),
                new Item("Run all tests", Status.PENDING)), list.items());
        assertEquals("Todo list updated: 1 of 3 completed; in progress: Fix apply_discount.", list.summary());
        assertEquals(new TodoList(List.of()), TodoList.parse(todos()), "an empty list clears it");
    }

    @Test
    void refusesWhatTheModelCanFix() {
        assertEquals("Invalid todos: content must not be empty (item 2).", refused(todos(item("a", "pending"), item("   ", "pending"))));
        assertEquals("Invalid todos: duplicate content \"a\".", refused(todos(item("a", "pending"), item(" a", "completed"))));
        assertEquals("Invalid todos: at most one item may be in_progress (got 2).", refused(todos(item("a", "in_progress"), item("b", "in_progress"))));
        assertEquals("Invalid todos: status must be pending, in_progress or completed (item 1).", refused(todos(item("a", "done"))));
        assertEquals("Invalid todos: status must be pending, in_progress or completed (item 1).", refused(todos(item("a", null))));
        assertTrue(refused(todos(new Json.Obj(Map.of("content", new Json.Str("a"), "status", new Json.Str("pending"),
                "activeForm", new Json.Str("Doing a"))))).contains("unknown field \"activeForm\""));
        assertTrue(refused(new Json.Obj(Map.of())).startsWith("Invalid todos: the argument todos must be a list"));
        assertTrue(refused(todos(new Json.Str("a"))).contains("item 1 must be an object"));
        assertTrue(TodoList.tryParse(todos(item("a", "done"))).isEmpty());
    }

    @Test
    void theSummarySaysHowFarItGot() {
        assertEquals("Todo list updated: 2 of 2 completed; all done.", TodoList.parse(todos(item("a", "completed"), item("b", "completed"))).summary());
        assertEquals("Todo list updated: 0 of 2 completed; nothing in progress.", TodoList.parse(todos(item("a", "pending"), item("b", "pending"))).summary());
        assertEquals("Todo list updated: 0 of 0 completed.", TodoList.parse(todos()).summary());
    }

    @Test
    void theLinesForTheSummaryGoBothWays() {
        TodoList list = TodoList.parse(todos(item("Read", "completed"), item("Fix [x] marker", "in_progress"), item("Test", "pending")));
        assertEquals("[x] Read\n[>] Fix [x] marker\n[ ] Test", list.lines());
        assertEquals(list, TodoList.fromLines(list.lines()));
        assertEquals(list, TodoList.fromLines("noise\n" + list.lines() + "\n[?] odd\n"));
        assertEquals(list, TodoList.parse(list.toJson()));
        List<Json> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(item("step " + i, i == 3 ? "in_progress" : "pending"));
        assertEquals(30, TodoList.parse(new Json.Obj(Map.of("todos", new Json.Arr(many)))).items().size());
    }
}
