package tantalum.value.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.command.AssembleContext;
import tantalum.value.decision.SessionChange;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** model-picker 任务 1（M12、M13）：选择是“路线 + 模型 + 档位”；只换档位不算换模型；新字段在旧的构造里为空。 */
class ModelChoiceTest {

    private static final ModelChoice SONNET = new ModelChoice("claude", "claude-sonnet-5-5", "medium");
    private static final SessionId SESSION = new SessionId("s");
    private static final Principal USER = new Principal("u");

    @Test
    void aChoiceNamesARouteAndAModelAndOnlyThoseMakeASwitch() {
        assertEquals("claude/claude-sonnet-5-5", SONNET.label());
        assertTrue(SONNET.sameModel(new ModelChoice("claude", "claude-sonnet-5-5", "high")), "changing only the level is not a switch");
        assertFalse(SONNET.sameModel(new ModelChoice("local", "claude-sonnet-5-5", "medium")));
        assertFalse(SONNET.sameModel(null));
        assertNull(new ModelChoice("local", "qwen", null).effort(), "a model without levels");
        assertThrows(NullPointerException.class, () -> new ModelChoice(null, "m", null));
        assertThrows(IllegalArgumentException.class, () -> new ModelChoice("claude", " ", null));
        assertEquals("[Model changed: earlier assistant turns were written by a/x; you are now b/y.]", new ModelChangeBlock("a/x", "b/y").text());
    }

    @Test
    void oldConstructorsLeaveTheNewFieldsEmpty() {
        assertNull(new Prompt("s", List.of(), List.of()).model());
        assertEquals(SONNET, new Prompt("s", List.of(), List.of()).withModel(SONNET).model());
        assertNull(new ReasoningBlock("claude", new Json.Str("x")).route());
        assertNull(new UserPrompt(new SubmissionId("a"), new RequestId("r"), new OwnerRef.OfSession(SESSION), "t").model());
        assertNull(new FollowUpSubmitted(new SubmissionId("a"), new RequestId("r"), "t").model());
        assertNull(new ContextAssembled(new CommandId("c"), "s", List.of(), null, List.of()).model());
        AssembleContext assemble = new AssembleContext(new CommandId("c"), new RunRef(USER, SESSION, new RunId("r")));
        assertNull(assemble.requested());
        assertNull(assemble.current());
    }

    @Test
    void theSessionsChoiceIsPartOfItsRecordedChanges() {
        SessionState before = SessionState.start(SESSION, USER);
        SessionState after = before.withModel(SONNET).withFollowUpModel(new ModelChoice("local", "qwen", "on"));
        SessionChange change = SessionChange.between(before, after);
        assertFalse(change.isEmpty());
        assertEquals(SONNET, change.model().value());
        assertEquals(after, change.applyTo(before));
        assertTrue(SessionChange.between(after, after).isEmpty());

        RunState run = RunState.start(new RunRef(USER, SESSION, new RunId("r")), new OwnerRef.OfSession(SESSION));
        RunState switched = run.withModel(SONNET, new ModelChoice("local", "qwen", "on"));
        assertEquals(SONNET, switched.model());
        assertEquals("local", switched.modelBefore().route());
        assertEquals(SONNET, switched.withPhase(run.phase()).withRetries(2).model(), "other changes keep the run's model");
    }
}
