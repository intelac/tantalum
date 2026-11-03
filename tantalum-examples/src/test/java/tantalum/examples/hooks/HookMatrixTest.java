package tantalum.examples.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static tantalum.examples.hooks.MatrixHook.Result.ALLOW;
import static tantalum.examples.hooks.MatrixHook.Result.NULL;
import static tantalum.examples.hooks.MatrixHook.Result.PATCH;
import static tantalum.examples.hooks.MatrixHook.Result.STOP;
import static tantalum.examples.hooks.MatrixHook.Result.THROW;
import static tantalum.examples.hooks.MatrixHook.Result.WORK_FAIL;
import static tantalum.examples.hooks.MatrixHook.Result.WORK_OK;
import static tantalum.examples.hooks.MatrixHook.Result.WORK_TIMEOUT;
import static tantalum.value.hook.HookPoint.BEFORE_COMPACT;
import static tantalum.value.hook.HookPoint.BEFORE_MODEL_CALL;
import static tantalum.value.hook.HookPoint.BEFORE_RUN_END;
import static tantalum.value.hook.HookPoint.BEFORE_TOOL_CALL;
import static tantalum.value.hook.HookPoint.ON_FAILURE;
import static tantalum.value.hook.HookPoint.ON_WORK_RESULT;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tantalum.examples.hooks.MatrixHook.Result;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.hook.HookPoint;
import tantalum.value.json.Json;
import tantalum.value.model.FailureReason;

/**
 * 需求 4.1、4.3、4.5：6 个内核钩子位置 × 每种结果，外加处理器钩子 {@code afterToolExecution} 的三种情况，全部走真实管线。
 * 每格查结束原因和详情、journal 事件序列、对话条目、模型收到的 prompt、工作和工具的执行次数，以及回放出的 prompt 与实时相同；
 * 全部格子跑完后重开同一数据目录，每个会话的视图逐字节相同。期望值按 kernel 的代码推出，推导写在每张表前面。
 */
class HookMatrixTest {

    // journal 事件的简写
    private static final String U = "UserPrompt";
    private static final String C = "ContextAssembled";
    private static final String R = "ModelReplied";
    private static final String F = "ModelFailed[SERVER_ERROR]";
    private static final String TIMER = "TimerFired";
    private static final String TF = "ToolFinished[echo-ok]";
    private static final String CR = "CompactRequested";
    private static final String W_OK = "WorkDone[matrix]";
    private static final String W_FAIL = "WorkDone[matrix failed: java.lang.IllegalStateException: " + MatrixWork.FAILURE + "]";
    private static final String W_TIMEOUT = "WorkDone[matrix failed: work timed out after PT1S]";
    private static final String W_SOURCE = "WorkDone[matrixSource]";

    // 对话条目和 prompt 的简写
    private static final String USER = "user";
    private static final String TEXT = "text:answer";
    private static final String TOOL_DONE = "tool:echo:DONE:echo-ok";
    private static final String P_USER = "msgs=1 last=user";
    private static final String P_RESULT = "msgs=3 last=result:echo-ok";
    private static final String LONG = "x ".repeat(1000);

    @TempDir
    static Path temp;
    private static MatrixFixture fixture;

    /** 一格：位置、结果、是否强制，以及期望的结束、事件、条目、prompt、工作次数、工具次数。 */
    record Cell(String id, HookPoint point, Result result, boolean mandatory, String end, List<String> events,
                List<String> items, List<String> prompts, int works, int tools) {

        @Override
        public String toString() {
            return id;
        }
    }

    private static Cell cell(String id, HookPoint point, Result result, boolean mandatory, String end, List<String> events,
                             List<String> items, List<String> prompts, int works, int tools) {
        return new Cell(id, point, result, mandatory, end, events, items, prompts, works, tools);
    }

    private static String hook(String id) {
        return MatrixHook.nameOf(id);
    }

    private static String run(String end) {
        return "run:" + end;
    }

    private static String blocked(String reason) {
        return "tool:echo:BLOCKED:Tool call blocked: " + reason;
    }

    private static String blockedPrompt(String reason) {
        return "msgs=3 last=result!:Tool call blocked: " + reason;
    }

    /**
     * 模型调用前（ModelSteps.callModel）。脚本：直接回答。放行和补丁照常调模型，补丁进 system 和工具；
     * Stop、强制钩子抛异常、返回 null 结束为 STOPPED，模型没被调用；要求工作后（WorkSteps.resume → callModel 带上 work），
     * 钩子看到自己的工作放行，成功、失败、超时都照常调模型；非强制钩子抛异常被跳过。
     */
    static Stream<Cell> beforeModelCall() {
        List<String> answered = List.of(USER, run("COMPLETED"), TEXT);
        return Stream.of(
                cell("bmc-allow", BEFORE_MODEL_CALL, ALLOW, false, "COMPLETED", List.of(U, C, R), answered, List.of(P_USER), 0, 0),
                cell("bmc-patch", BEFORE_MODEL_CALL, PATCH, false, "COMPLETED", List.of(U, C, R), answered,
                        List.of(P_USER + " patched"), 0, 0),
                cell("bmc-stop", BEFORE_MODEL_CALL, STOP, false, "STOPPED: matrix stop", List.of(U, C),
                        List.of(USER, run("STOPPED: matrix stop")), List.of(), 0, 0),
                cell("bmc-work-ok", BEFORE_MODEL_CALL, WORK_OK, false, "COMPLETED", List.of(U, C, W_OK, R), answered, List.of(P_USER), 1, 0),
                cell("bmc-work-fail", BEFORE_MODEL_CALL, WORK_FAIL, false, "COMPLETED", List.of(U, C, W_FAIL, R), answered,
                        List.of(P_USER), 1, 0),
                cell("bmc-work-timeout", BEFORE_MODEL_CALL, WORK_TIMEOUT, false, "COMPLETED", List.of(U, C, W_TIMEOUT, R), answered,
                        List.of(P_USER), 1, 0),
                cell("bmc-throw-m", BEFORE_MODEL_CALL, THROW, true, "STOPPED: hook failed: " + hook("bmc-throw-m"), List.of(U, C),
                        List.of(USER, run("STOPPED: hook failed: " + hook("bmc-throw-m"))), List.of(), 0, 0),
                cell("bmc-throw-o", BEFORE_MODEL_CALL, THROW, false, "COMPLETED", List.of(U, C, R), answered, List.of(P_USER), 0, 0),
                cell("bmc-null", BEFORE_MODEL_CALL, NULL, false, "STOPPED: hook returned null: " + hook("bmc-null"), List.of(U, C),
                        List.of(USER, run("STOPPED: hook returned null: " + hook("bmc-null"))), List.of(), 0, 0));
    }

    /**
     * 工具调用前（ToolSteps.checkRemaining）。脚本：调一次 echo，再回答。补丁被忽略；Stop 只拦这个调用，模型收到“被拦下”的结果，运行照常完成；
     * 这个位置抛异常不分强制与否一律拦截（HookChain），返回 null 也拦截；要求工作后由 onWorkResult 决定，
     * 测试钩子在别的位置放行，所以工作成功、失败、超时之后工具都照常执行。
     */
    static Stream<Cell> beforeToolCall() {
        List<String> ran = List.of(USER, run("COMPLETED"), TOOL_DONE, TEXT);
        List<String> prompts = List.of(P_USER, P_RESULT);
        return Stream.of(
                cell("btc-allow", BEFORE_TOOL_CALL, ALLOW, false, "COMPLETED", List.of(U, C, R, TF, R), ran, prompts, 0, 1),
                cell("btc-patch", BEFORE_TOOL_CALL, PATCH, false, "COMPLETED", List.of(U, C, R, TF, R), ran, prompts, 0, 1),
                cell("btc-stop", BEFORE_TOOL_CALL, STOP, false, "COMPLETED", List.of(U, C, R, R),
                        List.of(USER, run("COMPLETED"), blocked("matrix stop"), TEXT), List.of(P_USER, blockedPrompt("matrix stop")), 0, 0),
                cell("btc-work-ok", BEFORE_TOOL_CALL, WORK_OK, false, "COMPLETED", List.of(U, C, R, W_OK, TF, R), ran, prompts, 1, 1),
                cell("btc-work-fail", BEFORE_TOOL_CALL, WORK_FAIL, false, "COMPLETED", List.of(U, C, R, W_FAIL, TF, R), ran, prompts, 1, 1),
                cell("btc-work-timeout", BEFORE_TOOL_CALL, WORK_TIMEOUT, false, "COMPLETED", List.of(U, C, R, W_TIMEOUT, TF, R), ran,
                        prompts, 1, 1),
                cell("btc-throw-m", BEFORE_TOOL_CALL, THROW, true, "COMPLETED", List.of(U, C, R, R),
                        List.of(USER, run("COMPLETED"), blocked("hook failed: " + hook("btc-throw-m")), TEXT),
                        List.of(P_USER, blockedPrompt("hook failed: " + hook("btc-throw-m"))), 0, 0),
                cell("btc-throw-o", BEFORE_TOOL_CALL, THROW, false, "COMPLETED", List.of(U, C, R, R),
                        List.of(USER, run("COMPLETED"), blocked("hook failed: " + hook("btc-throw-o")), TEXT),
                        List.of(P_USER, blockedPrompt("hook failed: " + hook("btc-throw-o"))), 0, 0),
                cell("btc-null", BEFORE_TOOL_CALL, NULL, false, "COMPLETED", List.of(U, C, R, R),
                        List.of(USER, run("COMPLETED"), blocked("hook returned null: " + hook("btc-null")), TEXT),
                        List.of(P_USER, blockedPrompt("hook returned null: " + hook("btc-null"))), 0, 0));
    }

    /**
     * 失败后（RetrySteps.modelFailed）。脚本：先回可重试的 SERVER_ERROR，再回答。放行、补丁（被忽略）、非强制钩子抛异常交给内核：
     * 退避 1 秒（backoff(0) = 1s）后由 TimerFired 重试；Stop、强制钩子抛异常、返回 null 以 FAILED 结束；
     * 要求工作后（retryAfterWork）不等定时器直接重试一次，工作成功、失败、超时都一样。运行条目记下最近一次失败的原因。
     */
    static Stream<Cell> onFailure() {
        String completed = run("COMPLETED after SERVER_ERROR");
        List<String> retried = List.of(USER, completed, TEXT);
        List<String> twice = List.of(P_USER, P_USER);
        return Stream.of(
                cell("onf-allow", ON_FAILURE, ALLOW, false, "COMPLETED", List.of(U, C, F, TIMER, R), retried, twice, 0, 0),
                cell("onf-patch", ON_FAILURE, PATCH, false, "COMPLETED", List.of(U, C, F, TIMER, R), retried, twice, 0, 0),
                cell("onf-stop", ON_FAILURE, STOP, false, "FAILED: matrix stop", List.of(U, C, F),
                        List.of(USER, run("FAILED: matrix stop after SERVER_ERROR")), List.of(P_USER), 0, 0),
                cell("onf-work-ok", ON_FAILURE, WORK_OK, false, "COMPLETED", List.of(U, C, F, W_OK, R), retried, twice, 1, 0),
                cell("onf-work-fail", ON_FAILURE, WORK_FAIL, false, "COMPLETED", List.of(U, C, F, W_FAIL, R), retried, twice, 1, 0),
                cell("onf-work-timeout", ON_FAILURE, WORK_TIMEOUT, false, "COMPLETED", List.of(U, C, F, W_TIMEOUT, R), retried, twice, 1, 0),
                cell("onf-throw-m", ON_FAILURE, THROW, true, "FAILED: hook failed: " + hook("onf-throw-m"), List.of(U, C, F),
                        List.of(USER, run("FAILED: hook failed: " + hook("onf-throw-m") + " after SERVER_ERROR")), List.of(P_USER), 0, 0),
                cell("onf-throw-o", ON_FAILURE, THROW, false, "COMPLETED", List.of(U, C, F, TIMER, R), retried, twice, 0, 0),
                cell("onf-null", ON_FAILURE, NULL, false, "FAILED: hook returned null: " + hook("onf-null"), List.of(U, C, F),
                        List.of(USER, run("FAILED: hook returned null: " + hook("onf-null") + " after SERVER_ERROR")), List.of(P_USER), 0, 0));
    }

    /**
     * 结束前（RunEnd.beforeEnd）。脚本：直接回答。放行、补丁（被忽略）、非强制钩子抛异常 → COMPLETED；
     * Stop、强制钩子抛异常、返回 null → STOPPED，回答已在对话里；要求工作后（无续写文字）再问一次，钩子看到自己的工作放行。
     */
    static Stream<Cell> beforeRunEnd() {
        List<String> answered = List.of(USER, run("COMPLETED"), TEXT);
        return Stream.of(
                cell("bre-allow", BEFORE_RUN_END, ALLOW, false, "COMPLETED", List.of(U, C, R), answered, List.of(P_USER), 0, 0),
                cell("bre-patch", BEFORE_RUN_END, PATCH, false, "COMPLETED", List.of(U, C, R), answered, List.of(P_USER), 0, 0),
                cell("bre-stop", BEFORE_RUN_END, STOP, false, "STOPPED: matrix stop", List.of(U, C, R),
                        List.of(USER, run("STOPPED: matrix stop"), TEXT), List.of(P_USER), 0, 0),
                cell("bre-work-ok", BEFORE_RUN_END, WORK_OK, false, "COMPLETED", List.of(U, C, R, W_OK), answered, List.of(P_USER), 1, 0),
                cell("bre-work-fail", BEFORE_RUN_END, WORK_FAIL, false, "COMPLETED", List.of(U, C, R, W_FAIL), answered, List.of(P_USER), 1, 0),
                cell("bre-work-timeout", BEFORE_RUN_END, WORK_TIMEOUT, false, "COMPLETED", List.of(U, C, R, W_TIMEOUT), answered,
                        List.of(P_USER), 1, 0),
                cell("bre-throw-m", BEFORE_RUN_END, THROW, true, "STOPPED: hook failed: " + hook("bre-throw-m"), List.of(U, C, R),
                        List.of(USER, run("STOPPED: hook failed: " + hook("bre-throw-m")), TEXT), List.of(P_USER), 0, 0),
                cell("bre-throw-o", BEFORE_RUN_END, THROW, false, "COMPLETED", List.of(U, C, R), answered, List.of(P_USER), 0, 0),
                cell("bre-null", BEFORE_RUN_END, NULL, false, "STOPPED: hook returned null: " + hook("bre-null"), List.of(U, C, R),
                        List.of(USER, run("STOPPED: hook returned null: " + hook("bre-null")), TEXT), List.of(P_USER), 0, 0));
    }

    /**
     * 工作结果后（WorkSteps.workDone）。引子：同一会话的 source 钩子在工具调用前要一轮 matrixSource 工作，所以工作完成后回到工具调用前。
     * 脚本：调一次 echo，再回答。放行、补丁（被忽略）、非强制钩子抛异常 → 工具执行；Stop、强制钩子抛异常、返回 null →
     * 只拦这个调用（ToolSteps.resumeChecks），运行照常完成；要求工作 → 记在原来的等待点上再做一轮，做完钩子放行，工具执行。
     */
    static Stream<Cell> onWorkResult() {
        List<String> ran = List.of(USER, run("COMPLETED"), TOOL_DONE, TEXT);
        List<String> prompts = List.of(P_USER, P_RESULT);
        return Stream.of(
                cell("owr-allow", ON_WORK_RESULT, ALLOW, false, "COMPLETED", List.of(U, C, R, W_SOURCE, TF, R), ran, prompts, 0, 1),
                cell("owr-patch", ON_WORK_RESULT, PATCH, false, "COMPLETED", List.of(U, C, R, W_SOURCE, TF, R), ran, prompts, 0, 1),
                cell("owr-stop", ON_WORK_RESULT, STOP, false, "COMPLETED", List.of(U, C, R, W_SOURCE, R),
                        List.of(USER, run("COMPLETED"), blocked("matrix stop"), TEXT), List.of(P_USER, blockedPrompt("matrix stop")), 0, 0),
                cell("owr-work-ok", ON_WORK_RESULT, WORK_OK, false, "COMPLETED", List.of(U, C, R, W_SOURCE, W_OK, TF, R), ran, prompts, 1, 1),
                cell("owr-work-fail", ON_WORK_RESULT, WORK_FAIL, false, "COMPLETED", List.of(U, C, R, W_SOURCE, W_FAIL, TF, R), ran,
                        prompts, 1, 1),
                cell("owr-work-timeout", ON_WORK_RESULT, WORK_TIMEOUT, false, "COMPLETED", List.of(U, C, R, W_SOURCE, W_TIMEOUT, TF, R), ran,
                        prompts, 1, 1),
                cell("owr-throw-m", ON_WORK_RESULT, THROW, true, "COMPLETED", List.of(U, C, R, W_SOURCE, R),
                        List.of(USER, run("COMPLETED"), blocked("hook failed: " + hook("owr-throw-m")), TEXT),
                        List.of(P_USER, blockedPrompt("hook failed: " + hook("owr-throw-m"))), 0, 0),
                cell("owr-throw-o", ON_WORK_RESULT, THROW, false, "COMPLETED", List.of(U, C, R, W_SOURCE, TF, R), ran, prompts, 0, 1),
                cell("owr-null", ON_WORK_RESULT, NULL, false, "COMPLETED", List.of(U, C, R, W_SOURCE, R),
                        List.of(USER, run("COMPLETED"), blocked("hook returned null: " + hook("owr-null")), TEXT),
                        List.of(P_USER, blockedPrompt("hook returned null: " + hook("owr-null"))), 0, 0));
    }

    /**
     * 压缩前（CompactSteps.tryStart），用手动压缩触发：两轮对话后提交压缩。放行、补丁（被忽略）、非强制钩子抛异常 → 摘要调用，
     * 采用后 COMPLETED "compacted"；Stop、强制钩子抛异常、返回 null → 手动压缩以原因 FAILED（被拒绝时不改用裁剪）；
     * NeedWork 在这里当作拒绝，工作不执行（成功、失败、超时三种没有区别，只测一格）。
     * 被拒绝的压缩运行在同一步里开始又结束，运行条目应当带上结束原因（TranscriptPass.phaseChanged 目前只记 "Ended"、不带原因，
     * 控制台据此当作还在运行；这是缺陷，这里按正确的期望写）。
     */
    static Stream<Cell> beforeCompact() {
        List<String> events = List.of(U, C, R, U, C, R, CR, R);
        List<String> refusedEvents = List.of(U, C, R, U, C, R, CR);
        List<String> head = List.of(USER, run("COMPLETED"), "text:" + MatrixFixture.shorten(LONG), USER, run("COMPLETED"), TEXT);
        List<String> compacted = concat(head, run("COMPLETED: compacted"), "compaction:MANUAL ok");
        // 手动压缩只留最后一个单元（A2），头部是 U1、A1、U2（Units.split），摘要调用再加一条指令
        List<String> summarized = List.of(P_USER, "msgs=3 last=user", "summary msgs=4 last=user");
        List<String> refusedPrompts = List.of(P_USER, "msgs=3 last=user");
        String work = "beforeCompact asked for work, which is not supported";
        return Stream.of(
                cell("bcp-allow", BEFORE_COMPACT, ALLOW, false, "COMPLETED: compacted", events, compacted, summarized, 0, 0),
                cell("bcp-patch", BEFORE_COMPACT, PATCH, false, "COMPLETED: compacted", events, compacted, summarized, 0, 0),
                cell("bcp-stop", BEFORE_COMPACT, STOP, false, "FAILED: matrix stop", refusedEvents,
                        concat(head, run("FAILED: matrix stop"), "compaction:MANUAL failed: matrix stop"), refusedPrompts, 0, 0),
                cell("bcp-work", BEFORE_COMPACT, WORK_OK, false, "FAILED: " + work, refusedEvents,
                        concat(head, run("FAILED: " + work), "compaction:MANUAL failed: " + work), refusedPrompts, 0, 0),
                cell("bcp-throw-m", BEFORE_COMPACT, THROW, true, "FAILED: hook failed: " + hook("bcp-throw-m"), refusedEvents,
                        concat(head, run("FAILED: hook failed: " + hook("bcp-throw-m")), "compaction:MANUAL failed: hook failed: " + hook("bcp-throw-m")),
                        refusedPrompts, 0, 0),
                cell("bcp-throw-o", BEFORE_COMPACT, THROW, false, "COMPLETED: compacted", events, compacted, summarized, 0, 0),
                cell("bcp-null", BEFORE_COMPACT, NULL, false, "FAILED: hook returned null: " + hook("bcp-null"), refusedEvents,
                        concat(head, run("FAILED: hook returned null: " + hook("bcp-null")), "compaction:MANUAL failed: hook returned null: " + hook("bcp-null")),
                        refusedPrompts, 0, 0));
    }

    private static List<String> concat(List<String> first, String... more) {
        return Stream.concat(first.stream(), Stream.of(more)).toList();
    }

    static Stream<Cell> cells() {
        return Stream.of(beforeModelCall(), beforeToolCall(), onFailure(), beforeRunEnd(), onWorkResult(), beforeCompact())
                .flatMap(cells -> cells);
    }

    /** 处理器钩子的一格：工具结果怎么改，期望的工具结果。 */
    record After(String id, String mode, String result) {

        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * afterToolExecution（ToolHandler.applyHooks）：修改的结果写进 ToolFinished，模型和对话看到修改后的；原样返回不变；
     * 抛异常只记日志，结果保持原样，运行不受影响。
     */
    static Stream<After> afterTool() {
        return Stream.of(
                new After("after-modify", "modify", "echo-ok [changed]"),
                new After("after-same", "same", "echo-ok"),
                new After("after-throw", "throw", "echo-ok"));
    }

    /** 只改自己会话（参数里的 cell）的工具结果。 */
    private static AfterToolHook afterHook(After cell) {
        return (ToolContext context, String tool, Json arguments, ToolOutput output) -> {
            boolean mine = arguments instanceof Json.Obj obj && obj.fields().get("cell") instanceof Json.Str value
                    && value.value().equals(cell.id());
            if (!mine) {
                return output;
            }
            return switch (cell.mode()) {
                case "modify" -> ToolOutput.ok(output.text() + " [changed]");
                case "throw" -> throw new IllegalStateException("matrix after-tool hook failed");
                default -> output;
            };
        };
    }

    @BeforeAll
    static void start() {
        fixture = new MatrixFixture(temp, "--tantalum.tool-timeout=1s", "--tantalum.shutdown-wait=1s");
        for (Cell cell : cells().toList()) {
            fixture.hook(new MatrixHook(cell.id(), cell.point(), cell.result(), cell.mandatory()));
            switch (cell.point()) {
                case BEFORE_MODEL_CALL, BEFORE_RUN_END -> fixture.model.script(cell.id(), MatrixModel.answer("answer"));
                case BEFORE_TOOL_CALL -> fixture.model.script(cell.id(), MatrixModel.tool(cell.id(), "t1", "echo"), MatrixModel.answer("answer"));
                case ON_WORK_RESULT -> {
                    fixture.hook(MatrixHook.source(cell.id(), BEFORE_TOOL_CALL));
                    fixture.model.script(cell.id(), MatrixModel.tool(cell.id(), "t1", "echo"), MatrixModel.answer("answer"));
                }
                case ON_FAILURE -> fixture.model.script(cell.id(),
                        MatrixModel.failed(FailureReason.SERVER_ERROR, true, null, "matrix outage"), MatrixModel.answer("answer"));
                case BEFORE_COMPACT -> fixture.model.script(cell.id(), MatrixModel.answer(LONG), MatrixModel.answer("answer"),
                        MatrixModel.summary(cell.id()));
            }
        }
        for (After cell : afterTool().toList()) {
            fixture.afterHook(afterHook(cell));
            fixture.model.script(cell.id(), MatrixModel.tool(cell.id(), "t1", "echo"), MatrixModel.answer("answer"));
        }
        fixture.start();
    }

    @AfterAll
    static void reopenAndClose() throws Exception {
        try {
            fixture.reopenAndCompare();
        } finally {
            fixture.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cells")
    void kernelHook(Cell cell) throws Exception {
        String id = cell.id();
        String end;
        if (cell.point() == BEFORE_COMPACT) {
            fixture.submit(id, "first " + "y ".repeat(1000));
            fixture.awaitEnd(id, 1);
            fixture.submit(id, "second");
            fixture.awaitEnd(id, 2);
            fixture.compact(id);
            end = fixture.awaitEnd(id, 3);
        } else {
            fixture.submit(id, "go");
            end = fixture.awaitEnd(id, 1);
        }
        assertEquals(cell.end(), end, "end");
        assertEquals(cell.events(), fixture.events(id), "journal");
        assertEquals(cell.items(), fixture.items(id), "items");
        assertEquals(cell.prompts(), fixture.prompts(id), "prompts");
        assertEquals(cell.works(), fixture.work.calls(id), "work runs");
        assertEquals(cell.tools(), fixture.echo.calls(id), "tool runs");
        assertEquals(cell.point() == ON_WORK_RESULT ? 1 : 0, fixture.source.calls(id), "source work runs");
        assertEquals(fixture.model.prompts(id), fixture.replayedPrompts(id), "replayed prompts");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("afterTool")
    void afterToolExecution(After cell) throws Exception {
        String id = cell.id();
        fixture.submit(id, "go");
        assertEquals("COMPLETED", fixture.awaitEnd(id, 1));
        assertEquals(List.of(U, C, R, "ToolFinished[" + cell.result() + "]", R), fixture.events(id), "journal");
        assertEquals(List.of(USER, run("COMPLETED"), "tool:echo:DONE:" + cell.result(), TEXT), fixture.items(id), "items");
        assertEquals(List.of(P_USER, "msgs=3 last=result:" + cell.result()), fixture.prompts(id), "prompts");
        assertEquals(1, fixture.echo.calls(id), "tool runs");
        assertEquals(fixture.model.prompts(id), fixture.replayedPrompts(id), "replayed prompts");
    }
}
