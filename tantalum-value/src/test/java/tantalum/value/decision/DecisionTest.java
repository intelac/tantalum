package tantalum.value.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;

class DecisionTest {

    private static final RunRef RUN = new RunRef(new Principal("p"), new SessionId("s"), new RunId("r"));

    @Test
    void unchangedKeepsStateAndHasNoOutput() {
        KernelState state = KernelState.empty();
        Decision decision = Decision.unchanged(state);
        assertEquals(state, decision.newState());
        assertEquals(0, decision.commands().size());
        assertEquals(0, decision.facts().size());
        assertThrows(NullPointerException.class, () -> Decision.unchanged(null));
    }

    @Test
    void factsAreCopied() {
        List<Fact> facts = new ArrayList<>(List.of(new PhaseChanged(RUN, "AwaitingModel")));
        Decision decision = new Decision(KernelState.empty(), List.of(), facts);
        facts.clear();
        assertEquals(1, decision.facts().size());
    }

    @Test
    void factsNameThemselves() {
        assertEquals("PhaseChanged", new PhaseChanged(RUN, "x").type());
        assertEquals("HookReported", new HookReported(RUN, "beforeModelCall", "boom").type());
        assertThrows(NullPointerException.class, () -> new PhaseChanged(null, "x"));
        assertThrows(NullPointerException.class, () -> new PhaseChanged(RUN, null));
        assertThrows(NullPointerException.class, () -> new HookReported(null, "h", ""));
        assertThrows(NullPointerException.class, () -> new HookReported(RUN, null, ""));
    }
}
