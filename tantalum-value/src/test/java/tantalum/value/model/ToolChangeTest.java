package tantalum.value.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.event.ContextAssembled;
import tantalum.value.id.CommandId;
import tantalum.value.json.Json;
import tantalum.value.state.SessionContext;

/** core-tools C14、C15 的值类型。 */
class ToolChangeTest {

    private static final ToolSpec READ = new ToolSpec("read_file", "read", new Json.Obj(Map.of()));

    @Test
    void aToolChangeChangesSomethingAndCopiesItsLists() {
        List<String> added = new ArrayList<>(List.of("write_file"));
        ToolChangeBlock change = new ToolChangeBlock(added, List.of());
        added.clear();
        assertEquals(List.of("write_file"), change.added());
        assertThrows(IllegalArgumentException.class, () -> new ToolChangeBlock(List.of(), List.of()));
    }

    @Test
    void deferredIsOffByDefaultAndCopiesOnlyWhenItChanges() {
        assertFalse(READ.deferred());
        assertSame(READ, READ.deferred(false));
        ToolSpec hidden = READ.deferred(true);
        assertTrue(hidden.deferred());
        assertEquals(READ, hidden.deferred(false));
    }

    @Test
    void assembledContextWithoutListsMeansEverythingIsOn() {
        ContextAssembled old = new ContextAssembled(new CommandId("c"), "sys", List.of(READ), null, List.of());
        assertEquals(null, old.enabledTools());
        assertEquals(null, old.enabledPlugins());
        ContextAssembled lists = new ContextAssembled(new CommandId("c"), "sys", List.of(READ), null, List.of(),
                new ArrayList<>(List.of("read_file")), List.of());
        assertEquals(List.of("read_file"), lists.enabledTools());
    }

    @Test
    void sessionContextKnowsWhatIsActive() {
        SessionContext old = new SessionContext("sys", List.of(READ), null, List.of());
        assertTrue(old.toolActive("anything"));
        assertTrue(old.pluginActive("anything"));
        SessionContext started = new SessionContext("sys", List.of(READ), null, List.of(), List.of(), List.of(), List.of("quota"));
        assertFalse(started.toolActive("read_file"));
        assertTrue(started.pluginActive("quota"));
        SessionContext changed = started.withActive(List.of("read_file"), List.of());
        assertTrue(changed.toolActive("read_file"));
        assertFalse(changed.pluginActive("quota"));
        assertEquals(List.of(), changed.initialTools(), "the start of the session does not move");
        assertEquals(changed.activeTools(), changed.withSections(List.of()).activeTools());
    }
}
