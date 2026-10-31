package tantalum.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.command.CallModel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.model.Prompt;

class ReplayTest {

    @Test
    void replayReachesTheSameState() {
        LoopFixture loop = TheLoopTest.runTenTurns();
        Replay.Result replay = Replay.run(loop.kernel, loop.journal);
        assertEquals(loop.state, replay.state());
    }

    @Test
    void replayRestoresEveryPromptOfTheRun() {
        LoopFixture loop = TheLoopTest.runTenTurns();
        Replay.Result first = Replay.run(loop.kernel, loop.journal);
        List<Prompt> prompts = first.commands().stream()
                .filter(CallModel.class::isInstance).map(command -> ((CallModel) command).prompt()).toList();

        assertEquals(4, prompts.size());
        assertEquals(1, prompts.get(0).messages().size());
        assertEquals(3, prompts.get(1).messages().size());
        assertEquals(5, prompts.get(2).messages().size());
        assertEquals(7, prompts.get(3).messages().size());
    }

    @Test
    void replayIsRepeatable() {
        LoopFixture loop = TheLoopTest.runTenTurns();
        assertEquals(Replay.run(loop.kernel, loop.journal), Replay.run(loop.kernel, loop.journal));
    }

    @Test
    void outOfOrderSequenceIsRejected() {
        LoopFixture loop = TheLoopTest.runTenTurns();
        List<EventEnvelope> shuffled = List.of(loop.journal.get(1), loop.journal.get(0));
        assertThrows(IllegalArgumentException.class, () -> Replay.run(loop.kernel, shuffled));
    }
}
