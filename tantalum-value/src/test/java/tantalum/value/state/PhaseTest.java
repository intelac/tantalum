package tantalum.value.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.value.id.CommandId;

class PhaseTest {

    @Test
    void awaitingCopiesItsCommands() {
        Set<CommandId> commands = new HashSet<>(Set.of(new CommandId("1-0")));
        Awaiting awaiting = new Awaiting(commands, "AwaitingModel");
        commands.clear();
        assertEquals(1, awaiting.commands().size());
        assertThrows(NullPointerException.class, () -> new Awaiting(Set.of(), null));
    }

    @Test
    void endedRequiresReason() {
        assertEquals(EndReason.STOPPED, new Ended(EndReason.STOPPED, "quota").reason());
        assertThrows(NullPointerException.class, () -> new Ended(null, ""));
    }

    @Test
    void aRunStartedByAnAssignmentOwesAReportAndNoOtherDoes() {
        assertEquals(new Deciding(), new Deciding());
        assertNotEquals(RunCause.prompt(), new RunCause(RunCause.StartedBy.ASSIGNMENT, tantalum.value.event.Root.USER, "t1-0"));
        assertThrows(IllegalArgumentException.class, () -> new RunCause(RunCause.StartedBy.ASSIGNMENT, tantalum.value.event.Root.USER, null));
        assertThrows(IllegalArgumentException.class, () -> new RunCause(RunCause.StartedBy.PROMPT, tantalum.value.event.Root.USER, "t1-0"));
    }
}
