package tantalum.kernel.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.hook.PromptPatch;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

class PromptProjectionTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final ToolSpec GREP = new ToolSpec("grep", "d", new Json.Null());

    private static RunState run() {
        return RunState.start(new RunRef(USER, SESSION, new RunId("r")), new OwnerRef.OfSession(SESSION))
                .withContext("system", List.of(GREP));
    }

    @Test
    void usesSystemToolsAndSessionMessages() {
        SessionState session = SessionState.start(SESSION, USER).append(Message.user("hi"));
        Prompt prompt = PromptProjection.project(run(), session, PromptPatch.NONE);
        assertEquals(new Prompt("system", List.of(GREP), List.of(Message.user("hi"))), prompt);
    }

    @Test
    void appliesThePatchAfterTheBase() {
        ToolSpec extra = new ToolSpec("extra", "d", new Json.Null());
        Prompt prompt = PromptProjection.project(run(), SessionState.start(SESSION, USER),
                new PromptPatch("+more", List.of(extra)));
        assertEquals("system+more", prompt.system());
        assertEquals(List.of(GREP, extra), prompt.tools());
    }

    @Test
    void keepsReasoningBlocksExactly() {
        ContentBlock reasoning = new ReasoningBlock("claude", new Json.Str("signed"));
        Message reply = new Message(Role.ASSISTANT, List.of(reasoning), false);
        SessionState session = SessionState.start(SESSION, USER).append(reply);
        assertEquals(List.of(reply), PromptProjection.project(run(), session, PromptPatch.NONE).messages());
    }
}
