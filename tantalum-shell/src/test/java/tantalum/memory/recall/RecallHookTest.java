package tantalum.memory.recall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.memory.MemoryContext;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.NeedWork;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.SystemUpdates;
import tantalum.value.state.ContextSection;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;
import tantalum.value.state.SessionContextFixture;

/** 拿去找记忆的那句话是用户的问题，不是问题后面内核写的说明。 */
class RecallHookTest {

    private static final RunRef RUN = new RunRef(new Principal("u"), new SessionId("s"), new RunId("r"));

    private static KernelState state(Message... messages) {
        SessionState session = SessionState.start(RUN.session(), RUN.principal()).withContext(SessionContextFixture.of("sys", List.of(), null,
                List.of(new ContextSection(MemoryContext.INDEX_TAG, "- about-me: who the user is", true))));
        for (Message message : messages) {
            session = session.append(message);
        }
        return KernelState.empty().with(session).with(RunState.start(RUN, new OwnerRef.OfSession(RUN.session())));
    }

    private static String query(KernelState state) {
        NeedWork work = assertInstanceOf(NeedWork.class, new RecallHook().beforeModelCall(state, HookContext.of(RUN, Instant.EPOCH)));
        return ((Json.Str) ((Json.Obj) work.input()).fields().get("query")).value();
    }

    /**
     * 上下文里某一段正好在这一轮变了（比如“工作区”那一段里这个会话的大小过了一万 token），内核在问题后面追加一条说明。
     * 2026-11-17 发现：那时拿去找记忆的是这条说明，这一轮什么也想不起来。
     */
    @Test
    void aContextUpdateAfterTheQuestionIsNotTakenForTheQuestion() {
        Message update = Message.user(SystemUpdates.HEADER + "<workspaces>\nAdded:\n- this conversation · ~10k tokens in context\n</workspaces>");
        assertEquals("我工位号多少？", query(state(Message.user("我工位号多少？"))));
        assertEquals("我工位号多少？", query(state(Message.user("我工位号多少？"), update)));
        NeedWork work = assertInstanceOf(NeedWork.class, new RecallHook().beforeModelCall(
                state(Message.user("先聊点别的"), update, Message.user("我工位号多少？"), update), HookContext.of(RUN, Instant.EPOCH)));
        assertEquals(List.of(new Json.Str("先聊点别的")), ((Json.Arr) ((Json.Obj) work.input()).fields().get("recent")).items(),
                "the earlier words it looks at are the user's too");
        assertSame(Allow.PLAIN, new RecallHook().beforeModelCall(state(update), HookContext.of(RUN, Instant.EPOCH)), "nothing the user said");
    }
}
