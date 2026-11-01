package tantalum.kernel.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.compaction.Units.Unit;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;

class UnitsTest {

    static Message assistant(String text) {
        return new Message(Role.ASSISTANT, List.of(new TextBlock(text)), false);
    }

    static Message calling(String id) {
        return new Message(Role.ASSISTANT, List.of(new TextBlock("calling"),
                new ToolCallBlock(id, "read_file", "{}", new Json.Obj(Map.of()))), false);
    }

    static Message results(String... ids) {
        return new Message(Role.USER, java.util.Arrays.stream(ids)
                .map(id -> (tantalum.value.model.ContentBlock) new ToolResultBlock(id, "out " + id, false, null)).toList(), false);
    }

    @Test
    void userMessagesAreUnitsOfTheirOwnEvenWhenConsecutive() {
        List<Unit> units = Units.split(List.of(Message.user("a"), Message.user("b"), assistant("c")));
        assertEquals(List.of(new Unit(0, 1), new Unit(1, 2), new Unit(2, 3)), units);
    }

    @Test
    void assistantMessageAbsorbsItsToolResults() {
        List<Unit> units = Units.split(List.of(Message.user("q"), calling("t1"), results("t1"), calling("t2"), results("t2"),
                assistant("done")));
        assertEquals(List.of(new Unit(0, 1), new Unit(1, 3), new Unit(3, 5), new Unit(5, 6)), units);
        assertEquals(2, units.get(1).size());
    }

    @Test
    void viewEndingWithToolResultsKeepsThemInTheLastUnit() {
        List<Unit> units = Units.split(List.of(Message.user("q"), calling("t1"), results("t1")));
        assertEquals(List.of(new Unit(0, 1), new Unit(1, 3)), units);
    }

    @Test
    void userTextMixedWithResultsIsNotAbsorbed() {
        Message steerWithResult = new Message(Role.USER,
                List.of(new ToolResultBlock("t1", "out", false, null), new TextBlock("steer")), false);
        List<Unit> units = Units.split(List.of(calling("t1"), steerWithResult));
        assertEquals(List.of(new Unit(0, 1), new Unit(1, 2)), units);
        assertFalse(Units.isToolResults(steerWithResult));
        assertTrue(Units.isToolResults(results("t1", "t2")));
        assertFalse(Units.isToolResults(new Message(Role.USER, List.of(), false)));
    }

    @Test
    void emptyViewHasNoUnits() {
        assertEquals(List.of(), Units.split(List.of()));
    }
}
