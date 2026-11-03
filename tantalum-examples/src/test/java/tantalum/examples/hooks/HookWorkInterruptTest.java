package tantalum.examples.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tantalum.kernel.tool.ToolPairing;
import tantalum.value.hook.HookPoint;
import tantalum.value.model.FailureReason;

/**
 * 需求 4.2：会要求工作的 5 个位置，工作进行中（MatrixWork 的 hold 模式，确认已开始）分别取消和重启。
 * 取消：运行 CANCELLED；在途的工作被中断，迟到的（失败）结果照样落盘，但运行已结束，内核不再理它，对话和 prompt 都不变。
 * 重启：用很短的 shutdown-wait 关服务，再用同一个数据目录打开，运行按“被重启中断”收尾，工作只执行过 1 次，模型、工具都不再被调用。
 * 工作超时设成 30 秒，hold 的工作只会被取消或关闭打断。
 */
class HookWorkInterruptTest {

    private static final String LATE = "WorkDone[matrix failed: java.lang.InterruptedException]";
    private static final String SOURCE = "WorkDone[matrixSource]";
    private static final String[] ARGS = {"--tantalum.tool-timeout=30s", "--tantalum.shutdown-wait=200ms"};

    @TempDir
    static Path cancelDir;
    @TempDir
    static Path restartDir;
    private static MatrixFixture cancels;
    private static MatrixFixture restarts;
    /** 关服务前每个会话收到的 prompt 数。 */
    private static final Map<String, Integer> promptsBefore = new HashMap<>();
    private static long closeMillis;

    /** 一个位置：工作开始前 journal 里已有的事件、prompt 数，以及运行结束时补给工具调用的合成结果。 */
    record Point(HookPoint point, String tag, List<String> before, int prompts, boolean tool) {

        @Override
        public String toString() {
            return tag;
        }
    }

    static Stream<Point> points() {
        return Stream.of(
                new Point(HookPoint.BEFORE_MODEL_CALL, "bmc", List.of("UserPrompt", "ContextAssembled"), 0, false),
                new Point(HookPoint.BEFORE_TOOL_CALL, "btc", List.of("UserPrompt", "ContextAssembled", "ModelReplied"), 1, true),
                new Point(HookPoint.ON_FAILURE, "onf", List.of("UserPrompt", "ContextAssembled", "ModelFailed[SERVER_ERROR]"), 1, false),
                new Point(HookPoint.BEFORE_RUN_END, "bre", List.of("UserPrompt", "ContextAssembled", "ModelReplied"), 1, false),
                new Point(HookPoint.ON_WORK_RESULT, "owr", List.of("UserPrompt", "ContextAssembled", "ModelReplied", SOURCE), 1, true));
    }

    /** 每个位置一个会话：钩子在该位置要 hold 工作；onWorkResult 由工具调用前的 source 工作引出。 */
    private static void plan(MatrixFixture fixture, String session, Point point) {
        fixture.hook(new MatrixHook(session, point.point(), MatrixHook.Result.WORK_HOLD, false));
        switch (point.point()) {
            case BEFORE_TOOL_CALL -> fixture.model.script(session, MatrixModel.tool(session, "t1", "echo"), MatrixModel.answer("answer"));
            case ON_WORK_RESULT -> {
                fixture.hook(MatrixHook.source(session, HookPoint.BEFORE_TOOL_CALL));
                fixture.model.script(session, MatrixModel.tool(session, "t1", "echo"), MatrixModel.answer("answer"));
            }
            case ON_FAILURE -> fixture.model.script(session,
                    MatrixModel.failed(FailureReason.SERVER_ERROR, true, null, "outage"), MatrixModel.answer("answer"));
            default -> fixture.model.script(session, MatrixModel.answer("answer"));
        }
    }

    @BeforeAll
    static void start() throws Exception {
        cancels = new MatrixFixture(cancelDir, ARGS);
        restarts = new MatrixFixture(restartDir, ARGS);
        for (Point point : points().toList()) {
            plan(cancels, "cancel-" + point.tag(), point);
            plan(restarts, "restart-" + point.tag(), point);
        }
        cancels.start();

        // 5 个运行同时卡在工作里，然后关服务、重开。
        restarts.start();
        for (Point point : points().toList()) {
            restarts.submit("restart-" + point.tag(), "go");
        }
        for (Point point : points().toList()) {
            String session = "restart-" + point.tag();
            restarts.work.awaitHeld(session);
            promptsBefore.put(session, restarts.model.prompts(session).size());
        }
        long started = System.nanoTime();
        restarts.stop();
        closeMillis = (System.nanoTime() - started) / 1_000_000;
        restarts.start();
    }

    @AfterAll
    static void close() throws Exception {
        try {
            cancels.reopenAndCompare();
            restarts.reopenAndCompare();
        } finally {
            cancels.close();
            restarts.close();
        }
    }

    private static List<String> concat(List<String> first, String... more) {
        return Stream.concat(first.stream(), Stream.of(more)).toList();
    }

    /** 运行结束时的对话条目：运行条目带上结束原因；调用过工具的，补上的合成结果写在工具条目里。 */
    private static List<String> items(Point point, String end, String code) {
        String run = "run:" + end + (point.point() == HookPoint.ON_FAILURE ? " after SERVER_ERROR" : "");
        if (point.tool()) {
            return List.of("user", run, "tool:echo:FAILED:(" + code + ")");
        }
        return point.point() == HookPoint.BEFORE_RUN_END ? List.of("user", run, "text:answer") : List.of("user", run);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("points")
    void cancelWhileTheWorkRuns(Point point) throws Exception {
        String session = "cancel-" + point.tag();
        cancels.submit(session, "go");
        cancels.work.awaitHeld(session);
        assertEquals(point.before(), cancels.events(session), "journal while the work runs");
        cancels.cancel(cancels.awaitRun(session));
        assertEquals("CANCELLED", cancels.awaitEnd(session, 1));

        // 迟到的结果（被中断的工作）也落盘，排在取消之后；运行已结束，它不改变任何东西。
        List<String> events = cancels.awaitEvents(session, point.before().size() + 2);
        assertEquals(concat(point.before(), "Cancel", LATE), events, "journal");
        Thread.sleep(100);
        assertEquals("CANCELLED", MatrixFixture.end(cancels.runs(session).get(0)), "late result changed the run");
        // 工具调用还没发出：补上的是“没有运行”的合成结果。
        assertEquals(items(point, "CANCELLED", ToolPairing.ABORTED_BEFORE_DISPATCH), cancels.items(session), "items");
        assertEquals(point.prompts(), cancels.model.prompts(session).size(), "model calls");
        assertEquals(1, cancels.work.calls(session), "work runs");
        assertEquals(0, cancels.echo.calls(session), "tool runs");
        assertEquals(cancels.model.prompts(session), cancels.replayedPrompts(session), "replayed prompts");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("points")
    void restartWhileTheWorkRuns(Point point) throws Exception {
        String session = "restart-" + point.tag();
        assertTrue(closeMillis < 5_000, "closing took " + closeMillis + " ms with a 200 ms shutdown wait");
        assertEquals("CANCELLED: interrupted by restart", restarts.awaitEnd(session, 1), () -> "journal: " + restarts.events(session));
        // 关服务时工作被中断，被打断的结果不写回（ops-hardening H8）；重开时以“被重启中断”收尾。
        assertEquals(concat(point.before(), "RunInterrupted"), restarts.events(session), "journal");
        // 工具调用还在等工具调用前的工作，从没发出：补上的是“没有执行”的合成结果。
        assertEquals(items(point, "CANCELLED: interrupted by restart", ToolPairing.ABORTED_BEFORE_DISPATCH), restarts.items(session), "items");
        assertEquals(promptsBefore.get(session), restarts.model.prompts(session).size(), "model calls");
        assertEquals(1, restarts.work.calls(session), "work runs");
        assertEquals(0, restarts.echo.calls(session), "tool runs");
        // 没有被打断的结果落盘，回放算出的模型调用和实时完全相同。
        assertEquals(restarts.model.prompts(session), restarts.replayedPrompts(session), "replayed prompts");
    }
}
