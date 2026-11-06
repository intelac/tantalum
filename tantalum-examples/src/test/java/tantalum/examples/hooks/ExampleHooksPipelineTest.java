package tantalum.examples.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tantalum.examples.ApprovalWork;
import tantalum.examples.PinnedContextHook;
import tantalum.examples.ProviderBackoffHook;
import tantalum.examples.ProviderStatusWork;
import tantalum.examples.ToolApprovalHook;
import tantalum.value.model.FailureReason;

/**
 * 需求 4.6：三个新示例插件走真实管线，每种结果各一次，外部服务用本地桩。会话名决定哪个插件生效（pb 提供方退避、ta 审批、pin 钉住），
 * 窗口设得很小（阈值 3500 token），压缩的三种触发方式都能走到。期望值按插件和 kernel 的代码推出。
 */
class ExampleHooksPipelineTest {

    private static final String U = "UserPrompt";
    private static final String C = "ContextAssembled";
    private static final String R = "ModelReplied";
    private static final String PIN = "PIN";
    private static final String PINNED = "a message pinned with " + PIN + " would be summarized away";
    /** 窗口 6000、预留 500：阈值 5500，和 CompactApiTest 相同（local-workspace 的工具定义变长，从 4000 加到 6000）。 */
    private static final String[] ARGS = {"--tantalum.tool-timeout=1s", "--tantalum.shutdown-wait=1s",
        "--tantalum.claude.context-window=6000", "--tantalum.claude.max-tokens=500", "--tantalum.claude.bytes-per-token=3",
        "--tantalum.compaction.reserve-tokens=500", "--tantalum.compaction.keep-recent-tokens=600"};
    private static final Pattern CELL = Pattern.compile("\"cell\":\"([^\"]+)\"");

    @TempDir
    static Path temp;
    private static MatrixFixture fixture;
    private static ServiceStub status;
    private static ServiceStub approvals;
    private static final AtomicBoolean providerUp = new AtomicBoolean(true);
    private static final MatrixFixture.CountingTool deploy = new MatrixFixture.CountingTool("deploy");

    /** 审批服务按参数里的 cell 回复。 */
    private static ServiceStub.Reply approve(String body) {
        Matcher cell = CELL.matcher(body);
        String id = cell.find() ? cell.group(1) : "";
        boolean first = body.contains("\"poll\":0");
        return switch (id) {
            case "ta-approved" -> ServiceStub.Reply.of(200, "{\"decision\":\"approved\"}");
            case "ta-denied" -> ServiceStub.Reply.of(200, "{\"decision\":\"denied\"}");
            case "ta-pending" -> ServiceStub.Reply.of(200, first ? "{\"decision\":\"pending\"}" : "{\"decision\":\"approved\"}");
            case "ta-exhausted" -> ServiceStub.Reply.of(200, "{\"decision\":\"pending\"}");
            case "ta-slow" -> new ServiceStub.Reply(200, "{\"decision\":\"approved\"}", 3_000);
            case "ta-garbage" -> ServiceStub.Reply.of(200, "<html>busy</html>");
            default -> ServiceStub.Reply.of(500, "unknown cell " + id);
        };
    }

    @BeforeAll
    static void start() throws Exception {
        status = new ServiceStub(body -> ServiceStub.Reply.of(providerUp.get() ? 200 : 503, ""));
        approvals = new ServiceStub(ExampleHooksPipelineTest::approve);
        fixture = new MatrixFixture(temp, ARGS)
                .hook(MatrixFixture.scoped(session -> session.startsWith("pb-"), new ProviderBackoffHook(Duration.ofMinutes(1))))
                .workType(new ProviderStatusWork(status.uri(), Duration.ofMillis(50)))
                .hook(MatrixFixture.scoped(session -> session.startsWith("ta-"), new ToolApprovalHook(Set.of("deploy"), 3)))
                .workType(new ApprovalWork(approvals.uri()))
                .tool(deploy)
                .hook(MatrixFixture.scoped(session -> session.contains("pin-"), new PinnedContextHook(PIN)));
        for (Backoff cell : backoff().toList()) {
            fixture.model.script(cell.id(), MatrixModel.failed(cell.reason(), true, cell.retryAfter(), "provider trouble"),
                    MatrixModel.answer("answer"));
        }
        for (Approval cell : approval().toList()) {
            fixture.model.script(cell.id(), MatrixModel.tool(cell.id(), "t1", cell.tool()), MatrixModel.answer("answer"));
        }
        fixture.start();
    }

    @AfterAll
    static void close() throws Exception {
        try {
            fixture.reopenAndCompare();
        } finally {
            fixture.close();
            status.close();
            approvals.close();
        }
    }

    /** 提供方退避的一格：失败原因、要等多久、状态页通不通，期望的结束和事件。 */
    record Backoff(String id, FailureReason reason, Duration retryAfter, boolean up, String end, List<String> events, boolean polled) {

        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * ProviderBackoffHook（onFailure）：限流且要等 10 分钟（上限 1 分钟）→ Stop，运行 FAILED 并写明要等多久；
     * 限流要等 1 秒 → 放行，内核退避 1 秒后重试；服务端错误 → 要 providerStatus 工作，状态页 200 就恢复，重试一次；
     * 连接错误且状态页一直 503 → 工作在 1 秒时超时（失败），内核照样重试一次（retryAfterWork）。上下文超长的放行见 overflow 那一格。
     */
    static Stream<Backoff> backoff() {
        return Stream.of(
                new Backoff("pb-wait-too-long", FailureReason.RATE_LIMITED, Duration.ofMinutes(10), true,
                        "FAILED: provider asks to wait 600s, longer than the 60s this deployment accepts",
                        List.of(U, C, "ModelFailed[RATE_LIMITED]"), false),
                new Backoff("pb-rate-short", FailureReason.RATE_LIMITED, Duration.ofSeconds(1), true, "COMPLETED",
                        List.of(U, C, "ModelFailed[RATE_LIMITED]", "TimerFired", R), false),
                new Backoff("pb-server-recovers", FailureReason.SERVER_ERROR, null, true, "COMPLETED",
                        List.of(U, C, "ModelFailed[SERVER_ERROR]", "WorkDone[providerStatus]", R), true),
                new Backoff("pb-connection-down", FailureReason.CONNECTION_ERROR, null, false, "COMPLETED",
                        List.of(U, C, "ModelFailed[CONNECTION_ERROR]", "WorkDone[providerStatus failed: work timed out after PT1S]", R), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backoff")
    void providerBackoff(Backoff cell) throws Exception {
        providerUp.set(cell.up());
        int checks = status.requests().size();
        fixture.submit(cell.id(), "go");
        assertEquals(cell.end(), fixture.awaitEnd(cell.id(), 1));
        assertEquals(cell.events(), fixture.events(cell.id()), "journal");
        int prompts = cell.end().equals("COMPLETED") ? 2 : 1;
        assertEquals(Collections.nCopies(prompts, "msgs=1 last=user"), fixture.prompts(cell.id()), "prompts");
        assertEquals(cell.polled(), status.requests().size() > checks, "status page polled");
        assertEquals(fixture.model.prompts(cell.id()), fixture.replayedPrompts(cell.id()), "replayed prompts");
    }

    /**
     * 上下文超长：ProviderBackoffHook 放行，内核做超长恢复；PinnedContextHook 看到触发方式是超长恢复，即使头部有钉住的消息也放行。
     * 只留最新的用户消息，头部（U1、A1）进摘要，采用后重新组装上下文，再调一次模型。
     */
    @Test
    void overflowIsLeftToTheKernelAndPinnedContextAllowsIt() throws Exception {
        String id = "pb-pin-overflow";
        fixture.model.script(id, MatrixModel.answer("x ".repeat(600)),
                MatrixModel.failed(FailureReason.CONTEXT_OVERFLOW, false, null, "prompt is too long"),
                MatrixModel.summary(id), MatrixModel.answer("answer"));
        fixture.submit(id, PIN + " keep this " + "y ".repeat(600));
        fixture.awaitEnd(id, 1);
        fixture.submit(id, "second");
        assertEquals("COMPLETED", fixture.awaitEnd(id, 2));
        assertEquals(List.of(U, C, R, U, C, "ModelFailed[CONTEXT_OVERFLOW]", R, C, R), fixture.events(id), "journal");
        assertEquals(List.of("msgs=1 last=user", "msgs=3 last=user", "summary msgs=3 last=user", "msgs=2 last=user"),
                fixture.prompts(id), "prompts");
        // 引出恢复的那次超长失败不记在运行条目上：TranscriptPass.failed 看的是这一步之后的状态，此时运行已在等摘要。
        assertEquals(List.of("user", "run:COMPLETED", "text:" + MatrixFixture.shorten("x ".repeat(600)), "user", "run:COMPLETED",
                "compaction:OVERFLOW ok", "text:answer"), fixture.items(id), "items");
        assertEquals(fixture.model.prompts(id), fixture.replayedPrompts(id), "replayed prompts");
    }

    /** 审批的一格：调哪个工具，期望的事件、工具条目、工具执行次数。 */
    record Approval(String id, String tool, List<String> events, String item, int runs) {

        @Override
        public String toString() {
            return id;
        }
    }

    private static String blocked(String reason) {
        return "tool:deploy:BLOCKED:Tool call blocked: " + reason;
    }

    /**
     * ToolApprovalHook（beforeToolCall + onWorkResult，最多问 3 次）：同意 → 工具执行；拒绝 → 只拦这个调用；先待定再同意 → 问 2 次后执行；
     * 一直待定 → 第 3 次后拦下；服务太慢 → 工作超时失败，拦下；回复认不出 → 按拒绝，原因带原文；不在列表里的工具不问。运行都照常完成。
     */
    static Stream<Approval> approval() {
        String asked = "WorkDone[approval]";
        String ran = "ToolFinished[deploy-ok]";
        return Stream.of(
                new Approval("ta-approved", "deploy", List.of(U, C, R, asked, ran, R), "tool:deploy:DONE:deploy-ok", 1),
                new Approval("ta-denied", "deploy", List.of(U, C, R, asked, R), blocked("approval denied"), 0),
                new Approval("ta-pending", "deploy", List.of(U, C, R, asked, asked, ran, R), "tool:deploy:DONE:deploy-ok", 1),
                new Approval("ta-exhausted", "deploy", List.of(U, C, R, asked, asked, asked, R), blocked("no approval decision after 3 polls"), 0),
                new Approval("ta-slow", "deploy", List.of(U, C, R, "WorkDone[approval failed: work timed out after PT1S]", R),
                        blocked("approval failed: work timed out after PT1S"), 0),
                new Approval("ta-garbage", "deploy", List.of(U, C, R, asked, R),
                        blocked("approval denied: unrecognised reply (HTTP 200): <html>busy</html>"), 0),
                new Approval("ta-unlisted", "echo", List.of(U, C, R, "ToolFinished[echo-ok]", R), "tool:echo:DONE:echo-ok", 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("approval")
    void toolApproval(Approval cell) throws Exception {
        fixture.submit(cell.id(), "go");
        assertEquals("COMPLETED", fixture.awaitEnd(cell.id(), 1));
        assertEquals(cell.events(), fixture.events(cell.id()), "journal");
        assertEquals(List.of("user", "run:COMPLETED", cell.item(), "text:answer"), fixture.items(cell.id()), "items");
        assertEquals(cell.runs(), deploy.calls(cell.id()), "deploy runs");
        assertEquals(fixture.model.prompts(cell.id()), fixture.replayedPrompts(cell.id()), "replayed prompts");
    }

    /** 两轮对话后手动压缩；第一条用户消息带不带钉住的标记由 {@code pinned} 决定。 */
    private static String manual(String id, boolean pinned) throws Exception {
        fixture.model.script(id, MatrixModel.answer("x ".repeat(600)), MatrixModel.answer("answer"), MatrixModel.summary(id));
        fixture.submit(id, (pinned ? PIN + " " : "") + "keep this " + "y ".repeat(600));
        fixture.awaitEnd(id, 1);
        fixture.submit(id, "second");
        fixture.awaitEnd(id, 2);
        fixture.compact(id);
        return fixture.awaitEnd(id, 3);
    }

    /** PinnedContextHook（beforeCompact）：手动压缩，头部有钉住的消息 → Stop，压缩运行以原因 FAILED，摘要没有发出。 */
    @Test
    void manualCompactionOverAPinnedMessageIsRefused() throws Exception {
        String id = "pin-manual-pinned";
        assertEquals("FAILED: " + PINNED, manual(id, true));
        assertEquals(List.of(U, C, R, U, C, R, "CompactRequested"), fixture.events(id), "journal");
        assertEquals(2, fixture.model.prompts(id).size(), "no summary call");
        List<String> items = fixture.items(id);
        assertEquals("compaction:MANUAL failed: " + PINNED, items.getLast(), items.toString());
        // 和 HookMatrixTest 的压缩前几格一样：同一步开始又结束的运行，条目应当带上结束原因（目前缺陷：只有 "Ended"）。
        assertEquals("run:FAILED: " + PINNED, items.get(items.size() - 2), items.toString());
    }

    /** 没有钉住的消息 → 放行，摘要采用，COMPLETED "compacted"。 */
    @Test
    void manualCompactionWithoutAPinIsAllowed() throws Exception {
        String id = "pin-manual-free";
        assertEquals("COMPLETED: compacted", manual(id, false));
        assertEquals(List.of(U, C, R, U, C, R, "CompactRequested", R), fixture.events(id), "journal");
        assertEquals(List.of("msgs=1 last=user", "msgs=3 last=user", "summary msgs=4 last=user"), fixture.prompts(id), "prompts");
        assertEquals("compaction:MANUAL ok", fixture.items(id).getLast());
    }

    /**
     * 自动压缩：第二次调模型前越过阈值，头部（U1、A1）里有钉住的消息 → Stop。自动压缩被拒只是这次不压（不改用裁剪），
     * 本运行不再自动压缩，照常调模型，运行 COMPLETED，对话里有一条失败的压缩记录。
     */
    @Test
    void autoCompactionOverAPinnedMessageIsSkipped() throws Exception {
        String id = "pin-auto-pinned";
        fixture.model.script(id, MatrixModel.answer("x ".repeat(1500)), MatrixModel.answer("answer"));
        fixture.submit(id, PIN + " keep this " + "y ".repeat(1500));
        fixture.awaitEnd(id, 1);
        fixture.submit(id, "second " + "z ".repeat(3000));
        assertEquals("COMPLETED", fixture.awaitEnd(id, 2));
        assertEquals(List.of(U, C, R, U, C, R), fixture.events(id), "journal");
        assertEquals(List.of("msgs=1 last=user", "msgs=3 last=user"), fixture.prompts(id), "prompts");
        assertEquals(List.of("user", "run:COMPLETED", "text:" + MatrixFixture.shorten("x ".repeat(1500)), "user", "run:COMPLETED",
                "compaction:AUTO failed: " + PINNED, "text:answer"), fixture.items(id), "items");
        assertEquals(fixture.model.prompts(id), fixture.replayedPrompts(id), "replayed prompts");
    }

}
