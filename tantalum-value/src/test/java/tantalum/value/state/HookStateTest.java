package tantalum.value.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;
import tantalum.value.model.HookTextBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.SessionContext.HookState;
import tantalum.value.state.SessionContext.HookState.HookTool;

/** hook-patch 的值类型：钩子的说明块和会话上下文里的钩子状态。 */
class HookStateTest {

    private static final ToolSpec CITE = new ToolSpec("cite", "cite sources", new Json.Obj(Map.of()));
    private static final SessionContext PLAIN = new SessionContext("sys", List.of(CITE), null, List.of());

    @Test
    void aHookTextNamesItsHookAndSaysSomething() {
        HookTextBlock note = new HookTextBlock("quota", "80% left");
        assertEquals("quota", note.hook());
        assertEquals("80% left", note.text());
        assertThrows(IllegalArgumentException.class, () -> new HookTextBlock("", "x"));
        assertThrows(IllegalArgumentException.class, () -> new HookTextBlock("quota", ""));
        assertThrows(NullPointerException.class, () -> new HookTextBlock(null, "x"));
        assertThrows(NullPointerException.class, () -> new HookTextBlock("quota", null));
    }

    @Test
    void anOldContextHasNoHookStateAndReadsAsNone() {
        assertNull(PLAIN.hooks());
        assertSame(HookState.NONE, PLAIN.hookState());
        assertEquals("", HookState.NONE.text());
        assertEquals(List.of(), HookState.NONE.tools());
        assertEquals(List.of(), HookState.NONE.active());
    }

    @Test
    void theHookStateSurvivesTheOtherChanges() {
        HookState hooks = new HookState("be brief", List.of(new HookTool("style", CITE)), List.of("cite"));
        SessionContext context = PLAIN.withHooks(hooks);
        assertEquals(hooks, context.hookState());
        assertEquals(hooks, context.withSections(List.of()).hooks());
        assertEquals(hooks, context.withCompaction(null).hooks());
        assertEquals(hooks, context.withActive(List.of("cite"), List.of()).hooks());
        assertEquals("sys", context.system());
        assertEquals(List.of(CITE), context.tools());
    }

    @Test
    void requiredFieldsAreChecked() {
        assertThrows(NullPointerException.class, () -> new HookState(null, List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new HookTool(null, CITE));
        assertThrows(NullPointerException.class, () -> new HookTool("style", null));
    }
}
