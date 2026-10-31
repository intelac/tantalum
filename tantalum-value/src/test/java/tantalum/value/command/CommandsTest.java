package tantalum.value.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.Prompt;
import tantalum.value.model.ToolCallBlock;

class CommandsTest {

    private static final CommandId ID = new CommandId("1-0");
    private static final RunRef RUN = new RunRef(new Principal("p"), new SessionId("s"), new RunId("r"));

    @Test
    void everyCommandCarriesIdAndRun() {
        List<Command> commands = List.of(
                new AssembleContext(ID, RUN),
                new CallModel(ID, RUN, new Prompt("s", List.of(), List.of())),
                new RunTool(ID, RUN, new ToolCallBlock("c", "grep", "{}", new Json.Null())),
                new StartTimer(ID, RUN, Duration.ofSeconds(1)),
                new DoWork(ID, RUN, "citation", new Json.Null()),
                new AbortCommands(ID, RUN, Set.of()));

        for (Command command : commands) {
            assertEquals(ID, command.id());
            assertEquals(RUN, command.run());
        }
    }

    @Test
    void runRefRequiresAllParts() {
        Principal principal = new Principal("p");
        SessionId session = new SessionId("s");
        RunId run = new RunId("r");
        assertThrows(NullPointerException.class, () -> new RunRef(null, session, run));
        assertThrows(NullPointerException.class, () -> new RunRef(principal, null, run));
        assertThrows(NullPointerException.class, () -> new RunRef(principal, session, null));
    }
}
