package tantalum.kernel.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;

class PromptProjectionTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final ToolSpec GREP = new ToolSpec("grep", "d", new Json.Null());

    private static SessionState session() {
        return SessionState.start(SESSION, USER).withContext(new SessionContext("system", List.of(GREP), null, List.of()));
    }

    @Test
    void usesSystemToolsAndSessionMessages() {
        SessionState session = session().append(Message.user("hi"));
        Prompt prompt = PromptProjection.project(session);
        assertEquals(new Prompt("system", List.of(GREP), List.of(Message.user("hi"))), prompt);
    }

    /** hook-patch：钩子加的工具接在全集后面、永远 deferred；system 就是冻结的那份，钩子的说明在消息里。 */
    @Test
    void hookToolsFollowTheBaseAndStayDeferred() {
        ToolSpec extra = new ToolSpec("extra", "d", new Json.Null());
        SessionState session = session();
        session = session.withContext(session.context().withHooks(new SessionContext.HookState("be brief",
                List.of(new SessionContext.HookState.HookTool("style", extra)), List.of("extra"))));
        Prompt prompt = PromptProjection.project(session);
        assertEquals("system", prompt.system());
        assertEquals(List.of(GREP, extra.deferred(true)), prompt.tools());
    }

    @Test
    void keepsReasoningBlocksExactly() {
        ContentBlock reasoning = new ReasoningBlock("claude", new Json.Str("signed"));
        Message reply = new Message(Role.ASSISTANT, List.of(reasoning), false);
        SessionState session = session().append(reply);
        assertEquals(List.of(reply), PromptProjection.project(session).messages());
    }
}
