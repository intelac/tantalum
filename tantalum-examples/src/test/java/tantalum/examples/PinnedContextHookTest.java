package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.compaction.SummaryPrompt;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.Compacting.Trigger;
import tantalum.value.state.Compaction;
import tantalum.value.state.KernelState;
import tantalum.value.state.Plan;
import tantalum.value.state.SessionState;

class PinnedContextHookTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final HookContext CONTEXT = HookContext.of(new RunRef(USER, SESSION, new RunId("r")), Instant.EPOCH);
    private static final PinnedContextHook HOOK = new PinnedContextHook("#pin");

    private static Message assistant(String text) {
        return new Message(Role.ASSISTANT, List.of(new TextBlock(text)), false);
    }

    private static KernelState session(Message... messages) {
        SessionState session = SessionState.start(SESSION, USER);
        for (Message message : messages) {
            session = session.append(message);
        }
        return KernelState.empty().with(session);
    }

    /** 头部是视图的前 {@code head} 条。 */
    private static HookResult compact(KernelState state, int head, Trigger trigger) {
        return HOOK.beforeCompact(state, CONTEXT.withCompaction(new Plan(head, 1, 1, 100, null), trigger));
    }

    private static KernelState pinnedInHead() {
        return session(Message.user("#pin always use tabs"), assistant("ok"), Message.user("now refactor"), assistant("done"));
    }

    @Test
    void pinnedMessageInTheHeadStopsAutoCompaction() {
        Stop stop = assertInstanceOf(Stop.class, compact(pinnedInHead(), 2, Trigger.AUTO));
        assertTrue(stop.reason().contains("#pin"), stop.reason());
    }

    @Test
    void pinnedMessageInTheHeadStopsManualCompaction() {
        assertInstanceOf(Stop.class, compact(pinnedInHead(), 2, Trigger.MANUAL));
    }

    /** 拒绝超长恢复会让运行失败，宁可钉住的内容进摘要。 */
    @Test
    void overflowRecoveryIsAllowedEvenWithAPin() {
        assertSame(Allow.PLAIN, compact(pinnedInHead(), 2, Trigger.OVERFLOW));
    }

    @Test
    void pinOnlyInTheKeptPartIsAllowed() {
        KernelState state = session(Message.user("start"), assistant("ok"), Message.user("#pin always use tabs"), assistant("done"));
        assertSame(Allow.PLAIN, compact(state, 2, Trigger.AUTO));
    }

    @Test
    void noPinIsAllowed() {
        KernelState state = session(Message.user("start"), assistant("ok"), Message.user("next"), assistant("done"));
        assertSame(Allow.PLAIN, compact(state, 2, Trigger.MANUAL));
    }

    /** 摘要引用过标记不算钉住，否则压缩过一次之后每次都会被拒。头部取自压缩后的视图。 */
    @Test
    void markerOnlyInASummaryIsAllowed() {
        SessionState compacted = SessionState.start(SESSION, USER)
                .append(Message.user("#pin always use tabs")).append(assistant("ok"))
                .append(Message.user("next")).append(assistant("done"))
                .compacted(new Compaction(List.of(SummaryPrompt.summaryMessage("user pinned: #pin always use tabs"), Message.user("next")), 3))
                .append(Message.user("more"));
        KernelState state = KernelState.empty().with(compacted);
        assertEquals(4, compacted.view().size());
        assertSame(Allow.PLAIN, compact(state, 3, Trigger.AUTO));
    }

    @Test
    void markerOnlyInAToolResultIsAllowed() {
        KernelState state = session(Message.user("read the notes"),
                new Message(Role.ASSISTANT, List.of(new ToolCallBlock("c", "read_file", "{}", new Json.Null())), false),
                new Message(Role.USER, List.of(new ToolResultBlock("c", "#pin from a file", false, null)), false),
                assistant("done"), Message.user("next"));
        assertSame(Allow.PLAIN, compact(state, 4, Trigger.AUTO));
    }

    @Test
    void otherHookPointsAndContextsAreUntouched() {
        assertSame(Allow.PLAIN, HOOK.beforeCompact(pinnedInHead(), CONTEXT));
        assertSame(Allow.PLAIN, HOOK.beforeModelCall(pinnedInHead(), CONTEXT));
        assertEquals("pinnedContext", HOOK.name());
        assertThrows(IllegalArgumentException.class, () -> new PinnedContextHook(" "));
    }
}
