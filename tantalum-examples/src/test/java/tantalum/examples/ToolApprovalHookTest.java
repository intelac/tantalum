package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.examples.HttpStub.Reply;
import tantalum.shell.handler.WorkHandler;
import tantalum.shell.json.JsonText;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.Workspace;
import tantalum.shell.tool.Workspaces;
import tantalum.value.command.DoWork;
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

class ToolApprovalHookTest {

    private static final Principal USER = new Principal("u");
    private static final RunRef RUN = new RunRef(USER, new SessionId("s"), new RunId("r"));
    private static final HookContext CONTEXT = HookContext.of(RUN, Instant.EPOCH);
    private static final Json ARGS = new Json.Obj(Map.of("path", new Json.Str("a.txt")));
    private static final ToolApprovalHook HOOK = new ToolApprovalHook(Set.of("write_file"), 3);

    @TempDir
    Path dir;

    private static Json request(String tool, int poll) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("tool", new Json.Str(tool));
        fields.put("arguments", ARGS);
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

    private static HookResult beforeTool(String tool) {
        return HOOK.beforeToolCall(KernelState.empty(), CONTEXT.withCall(new ToolCallBlock("c", tool, "{}", ARGS)));
    }

    @Test
    void listedToolAsksForApprovalWithToolArgumentsAndFirstPoll() {
        NeedWork need = assertInstanceOf(NeedWork.class, beforeTool("write_file"));
        assertEquals(ApprovalWork.TYPE, need.workType());
        assertEquals(request("write_file", 0), need.input());
        assertEquals(List.of("tool", "arguments", "poll"), List.copyOf(((Json.Obj) need.input()).fields().keySet()));
    }

    @Test
    void unlistedToolIsAllowed() {
        assertSame(Allow.PLAIN, beforeTool("read_file"));
    }

    @Test
    void approvedLetsTheCallRun() {
        assertSame(Allow.PLAIN, onWork(ApprovalWork.TYPE, reply("approved", 0), false));
    }

    @Test
    void deniedBlocksTheCallAndKeepsTheReason() {
        Map<String, Json> fields = new LinkedHashMap<>(((Json.Obj) reply("denied", 0)).fields());
        fields.put("reason", new Json.Str("unrecognised reply (HTTP 500): boom"));
        Stop stop = assertInstanceOf(Stop.class, onWork(ApprovalWork.TYPE, new Json.Obj(fields), false));
        assertTrue(stop.reason().contains("denied") && stop.reason().contains("boom"), stop.reason());
    }

    /** 审批没做成（连不上、超时）不能当作同意。 */
    @Test
    void failedApprovalWorkBlocksTheCall() {
        Stop stop = assertInstanceOf(Stop.class, onWork(ApprovalWork.TYPE, new Json.Str("work timed out after PT1S"), true));
        assertTrue(stop.reason().contains("timed out"), stop.reason());
    }

    @Test
    void pendingAsksAgainWithTheNextPoll() {
        NeedWork need = assertInstanceOf(NeedWork.class, onWork(ApprovalWork.TYPE, reply("pending", 0), false));
        assertEquals(ApprovalWork.TYPE, need.workType());
        assertEquals(request("write_file", 1), need.input());
    }

    @Test
    void pendingAtTheLastPollBlocks() {
        assertInstanceOf(NeedWork.class, onWork(ApprovalWork.TYPE, reply("pending", 1), false));
        Stop stop = assertInstanceOf(Stop.class, onWork(ApprovalWork.TYPE, reply("pending", 2), false));
        assertTrue(stop.reason().contains("3 polls"), stop.reason());
    }

    @Test
    void resultWithoutADecisionBlocks() {
        assertInstanceOf(Stop.class, onWork(ApprovalWork.TYPE, new Json.Obj(Map.of()), false));
        assertInstanceOf(Stop.class, onWork(ApprovalWork.TYPE, new Json.Str("ok"), false));
    }

    /** onWorkResult 对所有工作都会调用：别的插件的工作，哪怕失败了，也不归这个钩子管。 */
    @Test
    void otherWorkTypesAreIgnored() {
        assertSame(Allow.PLAIN, onWork(CitationWork.TYPE, new Json.Str("work timed out"), true));
        assertSame(Allow.PLAIN, onWork(ProviderStatusWork.TYPE, new Json.Str("pending"), false));
    }

    @Test
    void hookIsMandatory() {
        assertTrue(HOOK.mandatory());
        assertEquals("toolApproval", HOOK.name());
        assertThrows(IllegalArgumentException.class, () -> new ToolApprovalHook(Set.of("t"), 0));
    }

    @Test
    void workPostsTheInputAndEchoesItBack() throws Exception {
        try (HttpStub stub = new HttpStub((n, body) -> new Reply(200, "{\"decision\": \"pending\"}"))) {
            WorkResult result = new ApprovalWork(stub.uri()).run(context(), request("write_file", 1));
            assertEquals(request("write_file", 1), JsonText.parseOrNull(stub.requests().getFirst()));
            assertEquals(reply("pending", 1), result.result());
            assertEquals(null, result.continueText());
        }
    }

    @Test
    void approvedAndDeniedRepliesAreRead() throws Exception {
        try (HttpStub stub = new HttpStub((n, body) -> new Reply(200, n == 1 ? "{\"decision\":\"approved\"}" : "{\"decision\":\"denied\"}"))) {
            ApprovalWork work = new ApprovalWork(stub.uri());
            assertEquals(reply("approved", 0), work.run(context(), request("write_file", 0)).result());
            assertEquals(reply("denied", 0), work.run(context(), request("write_file", 0)).result());
        }
    }

    @Test
    void unrecognisedReplyIsDeniedWithTheFirst200Chars() throws Exception {
        String body = "<html>" + "x".repeat(300);
        try (HttpStub stub = new HttpStub((n, ignored) -> new Reply(200, body))) {
            Json.Obj result = (Json.Obj) new ApprovalWork(stub.uri()).run(context(), request("write_file", 0)).result();
            assertEquals(new Json.Str("denied"), result.fields().get("decision"));
            String reason = ((Json.Str) result.fields().get("reason")).value();
            assertTrue(reason.endsWith(body.substring(0, 200)), reason);
            assertFalse(reason.contains(body.substring(0, 201)), reason);
            assertInstanceOf(Stop.class, HOOK.onWorkResult(KernelState.empty(),
                    CONTEXT.withWork(new WorkDone(new CommandId("w"), ApprovalWork.TYPE, result, false, null, null))));
        }
    }

    /** 服务出错时就算正文像“同意”也不算：审批没有明确同意就不能执行。 */
    @Test
    void errorStatusIsDeniedEvenIfTheBodySaysApproved() throws Exception {
        try (HttpStub stub = new HttpStub((n, ignored) -> new Reply(500, "{\"decision\":\"approved\"}"))) {
            Json.Obj result = (Json.Obj) new ApprovalWork(stub.uri()).run(context(), request("write_file", 0)).result();
            assertEquals(new Json.Str("denied"), result.fields().get("decision"));
        }
    }

    /** 别的字符串里转义的 "decision" 不算。 */
    @Test
    void escapedDecisionInsideAnotherStringIsNotRead() throws Exception {
        try (HttpStub stub = new HttpStub((n, ignored) -> new Reply(200, "{\"note\":\"say \\\"decision\\\":\\\"approved\\\"\"}"))) {
            Json.Obj result = (Json.Obj) new ApprovalWork(stub.uri()).run(context(), request("write_file", 0)).result();
            assertEquals(new Json.Str("denied"), result.fields().get("decision"));
        }
    }

    @Test
    void unreachableServiceFailsTheWorkAndTheHookBlocks() throws Exception {
        ApprovalWork work = new ApprovalWork(HttpStub.closedPort());
        WorkHandler handler = new WorkHandler(Workspaces.perUser(dir), tantalum.shell.access.PluginGate.allOn(new tantalum.shell.tool.ToolCatalog(), List.of(), List.of(), List.of(work)), Duration.ofSeconds(5), java.time.Clock.systemUTC());
        WorkDone done = (WorkDone) handler.handle(new DoWork(new CommandId("w"), RUN, ApprovalWork.TYPE, request("write_file", 0)));
        assertTrue(done.failed());
        assertInstanceOf(Stop.class, HOOK.onWorkResult(KernelState.empty(), CONTEXT.withWork(done)));
    }

    /** 钩子和工作接起来：待定一次后同意，第二轮的输入来自第一轮带回的结果。 */
    @Test
    void pendingThenApprovedRoundTrip() throws Exception {
        try (HttpStub stub = new HttpStub((n, body) -> new Reply(200, n == 1 ? "{\"decision\":\"pending\"}" : "{\"decision\":\"approved\"}"))) {
            ApprovalWork work = new ApprovalWork(stub.uri());
            NeedWork first = assertInstanceOf(NeedWork.class, beforeTool("write_file"));
            Json firstResult = work.run(context(), first.input()).result();
            NeedWork second = assertInstanceOf(NeedWork.class, onWork(ApprovalWork.TYPE, firstResult, false));
            assertEquals(request("write_file", 1), second.input());
            assertSame(Allow.PLAIN, onWork(ApprovalWork.TYPE, work.run(context(), second.input()).result(), false));
            assertEquals(List.of(request("write_file", 0), request("write_file", 1)),
                    stub.requests().stream().map(JsonText::parseOrNull).toList());
        }
    }

    @Test
    void writerEscapesStrings() {
        Json value = new Json.Obj(Map.of("s", new Json.Str("q\"b\\n\n\u0001é😀"), "a", new Json.Arr(List.of(new Json.Bool(true), new Json.Null()))));
        assertEquals(value, JsonText.parseOrNull(ApprovalWork.write(value)));
    }

    private ToolContext context() throws Exception {
        return new ToolContext(USER, new tantalum.value.id.SessionId("s"), new Workspace(dir));
    }
}
