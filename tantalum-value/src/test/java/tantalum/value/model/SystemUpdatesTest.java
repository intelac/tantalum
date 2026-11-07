package tantalum.value.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** review-fixes 需求 12.1：“不是用户写的”消息的判定只在这里一份。 */
class SystemUpdatesTest {

    @Test
    void contextUpdatesStartWithThePrefixAndTheHeaderIsThePrefixPlusTheRest() {
        assertTrue(SystemUpdates.HEADER.startsWith(SystemUpdates.PREFIX));
        assertTrue(SystemUpdates.isContextUpdate(SystemUpdates.HEADER + "memory-index changed"));
        assertFalse(SystemUpdates.isContextUpdate("please read the file"));
    }

    @Test
    void systemUpdatesAndModelChangesAreTheSystemMessagesMadeOnlyOfTheirBlocks() {
        Message toolChange = new Message(Role.SYSTEM, List.of(new ToolChangeBlock(List.of("grep"), List.of())), false);
        Message hookText = new Message(Role.SYSTEM, List.of(new HookTextBlock("quota", "80% left")), false);
        Message modelChange = new Message(Role.SYSTEM, List.of(new ModelChangeBlock("a", "b")), false);
        Message mixed = new Message(Role.SYSTEM, List.of(new HookTextBlock("quota", "x"), new ModelChangeBlock("a", "b")), false);
        Message user = Message.user("hi");
        assertTrue(SystemUpdates.isSystemUpdate(toolChange));
        assertTrue(SystemUpdates.isSystemUpdate(hookText));
        assertFalse(SystemUpdates.isSystemUpdate(modelChange));
        assertFalse(SystemUpdates.isSystemUpdate(mixed));
        assertFalse(SystemUpdates.isSystemUpdate(user));
        assertTrue(SystemUpdates.isModelChange(modelChange));
        assertFalse(SystemUpdates.isModelChange(hookText));
        assertFalse(SystemUpdates.isModelChange(mixed));
        assertFalse(SystemUpdates.isModelChange(new Message(Role.SYSTEM, List.of(), false)));
    }
}
