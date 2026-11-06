package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.json.JsonText;
import tantalum.shell.plugin.Question;
import tantalum.shell.plugin.Questions;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.Workspace;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;

/** 工具审批（A4）：在对话页上问用户，允许这一次 / 这个会话里一直允许 / 拒绝。钩子和工作各自测，不经过管线。 */
class ToolApprovalHookTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final RunRef RUN = new RunRef(USER, SESSION, new RunId("r"));
    private static final HookContext CONTEXT = HookContext.of(RUN, Instant.EPOCH);
    private static final Json ARGS = new Json.Obj(Map.of("path", new Json.Str("a.txt")));
    private static final ToolApprovalHook HOOK = new ToolApprovalHook(Set.of("write_file"), 3);

    @TempDir
    Path dir;

    private static Json request(String tool, int poll) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("tool", new Json.Str(tool));
        fields.put("arguments", ARGS);
        fields.put("call", new Json.Str("c"));
        fields.put("poll", new Json.Num(BigDecimal.valueOf(poll)));
        return new Json.Obj(fields);
    }

    private static Json reply(String decision, int poll) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("decision", new Json.Str(decision));
        fields.putAll(((Json.Obj) request("write_file", poll)).fields());
        return new Json.Obj(fields);
    }

    private static HookResult onWork(String type, Json result, boolean failed) {
        return HOOK.onWorkResult(KernelState.empty(), CONTEXT.withWork(new WorkDone(new CommandId("w"), type, result, failed, null, null)));
    }

    private static HookResult beforeTool(KernelState state, String tool) {
        return HOOK.beforeToolCall(state, CONTEXT.withCall(new ToolCallBlock("c", tool, "{}", ARGS)));
    }

    @Test
    void listedToolAsksWithToolArgumentsCallAndFirstPoll() {
        NeedWork need = assertInstanceOf(NeedWork.class, beforeTool(KernelState.empty(), "write_file"));
        assertEquals(ApprovalWork.TYPE, need.workType());
        assertEquals(request("write_file", 0), need.input());
        assertSame(Allow.PLAIN, beforeTool(KernelState.empty(), "read_file"));
    }

    /** 会话里记着“一直允许”这个工具就不再问；别的工具、别的会话照样问。 */
    @Test
    void aToolAlwaysAllowedInThisSessionIsNotAskedAgain() {
        SessionState allowed = SessionState.start(SESSION, USER)
                .withNote(ApprovalWork.TYPE, new Json.Obj(Map.of("write_file", new Json.Bool(true))));
        assertSame(Allow.PLAIN, beforeTool(KernelState.empty().with(allowed), "write_file"));

        ToolApprovalHook two = new ToolApprovalHook(Set.of("write_file", "bash"), 3);
        assertInstanceOf(NeedWork.class, two.beforeToolCall(KernelState.empty().with(allowed),
                CONTEXT.withCall(new ToolCallBlock("c", "bash", "{}", ARGS))));
        SessionState other = SessionState.start(new SessionId("other"), USER);
        HookContext otherContext = HookContext.of(new RunRef(USER, other.id(), new RunId("r2")), Instant.EPOCH)
                .withCall(new ToolCallBlock("c", "write_file", "{}", ARGS));
        assertInstanceOf(NeedWork.class, HOOK.beforeToolCall(KernelState.empty().with(allowed).with(other), otherContext));
    }

    @Test
    void approvedLetsTheCallRun() {
        assertSame(Allow.PLAIN, onWork(ApprovalWork.TYPE, reply("approved", 0), false));
    }

    /** 拒绝时告诉模型不要换个工具绕过去（同 DSH 电脑操作插件的拒绝说明）。 */
    @Test
    void deniedBlocksOnlyThisCallAndSaysNotToWorkAround() {
        Stop stop = assertInstanceOf(Stop.class, onWork(ApprovalWork.TYPE, reply("denied", 0), false));
        assertTrue(stop.reason().contains("the user denied this call to write_file"), stop.reason());
        assertTrue(stop.reason().contains("Do not retry"), stop.reason());
    }

    @Test
    void failedApprovalWorkBlocksTheCall() {
        Stop stop = assertInstanceOf(Stop.class, onWork(ApprovalWork.TYPE, new Json.Str("work timed out after PT1S"), true));
        assertTrue(stop.reason().contains("timed out"));
    }

    @Test
    void pendingAsksAgainUntilThePollsRunOut() {
        NeedWork need = assertInstanceOf(NeedWork.class, onWork(ApprovalWork.TYPE, reply("pending", 0), false));
        assertEquals(request("write_file", 1), need.input());
        assertInstanceOf(NeedWork.class, onWork(ApprovalWork.TYPE, reply("pending", 1), false));
        Stop stop = assertInstanceOf(Stop.class, onWork(ApprovalWork.TYPE, reply("pending", 2), false));
        assertTrue(stop.reason().contains("3 polls"));
    }

    @Test
    void aReplyWithoutADecisionBlocks() {
        assertInstanceOf(Stop.class, onWork(ApprovalWork.TYPE, new Json.Obj(Map.of()), false));
        assertInstanceOf(Stop.class, onWork(ApprovalWork.TYPE, new Json.Str("ok"), false));
    }

    /** onWorkResult 对所有工作都会调用：别的插件的工作，哪怕失败了，也不归这个钩子管。 */
    @Test
    void otherWorkTypesAreIgnored() {
        assertSame(Allow.PLAIN, onWork(CitationWork.TYPE, new Json.Str("work timed out"), true));
        assertSame(Allow.PLAIN, onWork(ProviderStatusWork.TYPE, new Json.Str("pending"), false));
    }

    /** 策略插件：默认关、用户可以关，开着时出错按拦截处理（A2）。 */
    @Test
    void hookIsAnOptionalPolicyThatFailsClosed() {
        assertEquals(false, HOOK.mandatory());
        assertEquals(false, HOOK.defaultEnabled());
        assertEquals(true, HOOK.failClosed());
        assertEquals("toolApproval", HOOK.name());
        assertThrows(IllegalArgumentException.class, () -> new ToolApprovalHook(Set.of("t"), 0));
        assertThrows(IllegalArgumentException.class, () -> new ApprovalWork(Duration.ZERO));
    }

    /** 记下问了什么、按给定的回答答；回答为 null 表示没人回答。 */
    private static final class Answering implements Questions {
        final List<Question> asked = new ArrayList<>();
        final String choice;

        Answering(String choice) {
            this.choice = choice;
        }

        @Override
        public Optional<String> ask(ToolContext context, Question question, Duration wait) {
            asked.add(question);
            return Optional.ofNullable(choice);
        }
    }

    private WorkResult run(Answering questions, int poll) throws Exception {
        ToolContext context = new ToolContext(USER, SESSION, new Workspace(dir), questions);
        return new ApprovalWork(Duration.ofSeconds(1)).run(context, request("write_file", poll));
    }

    @Test
    void theWorkOffersThreeChoicesForThisCall() throws Exception {
        Answering questions = new Answering(ApprovalWork.ONCE);
        run(questions, 0);
        Question question = questions.asked.getFirst();
        assertEquals("approval-c", question.id(), "the same call keeps the same question across polls");
        assertTrue(question.title().contains("write_file"));
        assertEquals("{\"path\":\"a.txt\"}", question.detail());
        assertEquals(List.of("允许这一次", "这个会话里一直允许", "拒绝"), question.choices().stream().map(Question.Choice::label).toList());
    }

    @Test
    void allowOnceApprovesWithoutANote() throws Exception {
        WorkResult result = run(new Answering(ApprovalWork.ONCE), 0);
        assertEquals(reply("approved", 0, "once"), result.result());
        assertNull(result.note());
    }

    @Test
    void alwaysAllowInThisSessionApprovesAndNotesTheTool() throws Exception {
        WorkResult result = run(new Answering(ApprovalWork.SESSION), 0);
        assertEquals(reply("approved", 0, "session"), result.result());
        assertEquals(new Json.Obj(Map.of("write_file", new Json.Bool(true))), result.note());
    }

    @Test
    void denyAndNoAnswer() throws Exception {
        assertEquals(reply("denied", 0), run(new Answering(ApprovalWork.DENY), 0).result());
        WorkResult unanswered = run(new Answering(null), 2);
        assertEquals(reply("pending", 2), unanswered.result());
        assertNull(unanswered.note());
    }

    /** 钩子和工作接起来：先没人回答，第二轮答“一直允许”；第二轮的输入来自第一轮带回的结果。 */
    @Test
    void pendingThenAlwaysAllowRoundTrip() throws Exception {
        NeedWork first = assertInstanceOf(NeedWork.class, beforeTool(KernelState.empty(), "write_file"));
        ToolContext nobody = new ToolContext(USER, SESSION, new Workspace(dir), new Answering(null));
        Json firstResult = new ApprovalWork(Duration.ofSeconds(1)).run(nobody, first.input()).result();
        NeedWork second = assertInstanceOf(NeedWork.class, onWork(ApprovalWork.TYPE, firstResult, false));
        assertEquals(request("write_file", 1), second.input());
        ToolContext user = new ToolContext(USER, SESSION, new Workspace(dir), new Answering(ApprovalWork.SESSION));
        assertSame(Allow.PLAIN, onWork(ApprovalWork.TYPE, new ApprovalWork(Duration.ofSeconds(1)).run(user, second.input()).result(), false));
    }

    @Test
    void writerEscapesStrings() {
        Json value = new Json.Obj(Map.of("s", new Json.Str("q\"b\\n\n\u0001é😀"), "a", new Json.Arr(List.of(new Json.Bool(true), new Json.Null()))));
        assertEquals(value, JsonText.parseOrNull(ApprovalWork.write(value)));
    }

    private static Json reply(String decision, int poll, String scope) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("decision", new Json.Str(decision));
        fields.put("scope", new Json.Str(scope));
        fields.putAll(((Json.Obj) request("write_file", poll)).fields());
        return new Json.Obj(fields);
    }
}
