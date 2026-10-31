package tantalum.value.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;

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
    void ownersAreDistinct() {
        assertEquals(new Deciding(), new Deciding());
        assertNotEquals(new OwnerRef.OfSession(new SessionId("a")), new OwnerRef.OfCommand(new CommandId("a")));
    }
}
