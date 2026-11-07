package tantalum.examples.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tantalum.examples.CitationCheckHook;
import tantalum.examples.CitationWork;
import tantalum.examples.ToolApprovalHook;
import tantalum.kernel.tool.ToolPairing;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.value.json.Json;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.hook.HookPoint;

/**
 * 需求 4.4、2.2、2.3：一个每次都要工作的钩子在 4 个等待点各撞一次工作轮数上限（默认 8），运行以 FAILED 结束，
 * 详情写明钩子、位置和轮数；正常的引用核对（1 轮）不受影响。全部走真实管线。
 * （审批以前靠反复要工作来等人，waiting-state 之后它不占工作轮数，不再在这里测。）
 */
class WorkRoundLimitTest {

    private static final String W = "WorkDone[matrix]";
    private static final String W_SOURCE = "WorkDone[matrixSource]";

    @TempDir
    static Path temp;
    private static MatrixFixture fixture;

    private static String settled(String hook, HookPoint at) {
        return "FAILED: plugin work did not settle: " + hook + " at " + at + " after 8 rounds (limit 8)";
    }

    private static List<String> repeat(int times, String... events) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            out.addAll(List.of(events));
        }
        return out;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }

    /** 一格：等待点、期望的结束、事件、条目、prompt 数、MatrixWork 执行次数。 */
    record Cap(String id, String end, List<String> events, List<String> items, int prompts, int works) {

        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * 模型调用前：钩子每次都要（forever），第 1–8 轮都做了，第 9 次被拒；模型没被调用。
     * 工具调用前：source 钩子要第 1 轮，onWorkResult 钩子接着要第 2–8 轮，计在原来的等待点（工具调用前）上，报的是 onWorkResult 那个钩子；
     * 运行结束时还没发出的工具调用补上“没有运行”的结果。
     * 结束前：每轮工作带续写文字，回到模型再到结束前；每圈都有模型回复，但结束前的轮数整个运行累计，第 9 次被拒，模型被调了 9 次。
     * 工作结果后：source 钩子在模型调用前要第 1 轮，onWorkResult 钩子接着要，计在模型调用前上。
     */
    static Stream<Cap> caps() {
        String bre = settled(MatrixHook.nameOf("cap-bre"), HookPoint.BEFORE_RUN_END);
        List<String> breItems = new ArrayList<>(List.of("user", "run:" + bre));
        breItems.addAll(Collections.nCopies(9, "text:answer"));
        return Stream.of(
                new Cap("cap-bmc", settled(MatrixHook.nameOf("cap-bmc"), HookPoint.BEFORE_MODEL_CALL),
                        concat(List.of("UserPrompt", "ContextAssembled"), repeat(8, W)),
                        List.of("user", "run:" + settled(MatrixHook.nameOf("cap-bmc"), HookPoint.BEFORE_MODEL_CALL)), 0, 8),
                new Cap("cap-btc", settled(MatrixHook.nameOf("cap-btc"), HookPoint.BEFORE_TOOL_CALL),
                        concat(List.of("UserPrompt", "ContextAssembled", "ModelReplied", W_SOURCE), repeat(7, W)),
                        List.of("user", "run:" + settled(MatrixHook.nameOf("cap-btc"), HookPoint.BEFORE_TOOL_CALL),
                                "tool:echo:FAILED:(" + ToolPairing.ABORTED_BEFORE_DISPATCH + ")"), 1, 7),
                new Cap("cap-bre", bre,
                        concat(List.of("UserPrompt", "ContextAssembled", "ModelReplied"), repeat(8, W, "ModelReplied")), breItems, 9, 8),
                new Cap("cap-owr", settled(MatrixHook.nameOf("cap-owr"), HookPoint.BEFORE_MODEL_CALL),
                        concat(List.of("UserPrompt", "ContextAssembled", W_SOURCE), repeat(7, W)),
                        List.of("user", "run:" + settled(MatrixHook.nameOf("cap-owr"), HookPoint.BEFORE_MODEL_CALL)), 0, 7));
    }

    @BeforeAll
    static void start() throws Exception {
        fixture = new MatrixFixture(temp, "--tantalum.tool-timeout=1s", "--tantalum.shutdown-wait=1s");
        fixture.hook(new MatrixHook("cap-bmc", HookPoint.BEFORE_MODEL_CALL, MatrixHook.Result.WORK_OK, false).forever());
        fixture.hook(MatrixHook.source("cap-btc", HookPoint.BEFORE_TOOL_CALL));
        fixture.hook(new MatrixHook("cap-btc", HookPoint.ON_WORK_RESULT, MatrixHook.Result.WORK_OK, false).forever());
        fixture.model.script("cap-btc", MatrixModel.tool("cap-btc", "t1", "echo"));
        fixture.hook(new MatrixHook("cap-bre", HookPoint.BEFORE_RUN_END, MatrixHook.Result.WORK_CONTINUE, false));
        fixture.model.script("cap-bre", Collections.nCopies(9, MatrixModel.answer("answer")).toArray(new ModelOutcome[0]));
        fixture.hook(MatrixHook.source("cap-owr", HookPoint.BEFORE_MODEL_CALL));
        fixture.hook(new MatrixHook("cap-owr", HookPoint.ON_WORK_RESULT, MatrixHook.Result.WORK_OK, false).forever());

        fixture.hook(MatrixFixture.scoped("citation"::equals, new CitationCheckHook())).workType(new CitationWork());
        fixture.model.script("citation", MatrixModel.answer("see A.cbl:9"), MatrixModel.answer("it is the second line"));

        fixture.start();
    }

    @AfterAll
    static void close() throws Exception {
        try {
            fixture.reopenAndCompare();
        } finally {
            fixture.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caps")
    void capIsReached(Cap cap) throws Exception {
        fixture.submit(cap.id(), "go");
        assertEquals(cap.end(), fixture.awaitEnd(cap.id(), 1));
        assertEquals(cap.events(), fixture.events(cap.id()), "journal");
        assertEquals(cap.items(), fixture.items(cap.id()), "items");
        assertEquals(cap.prompts(), fixture.model.prompts(cap.id()).size(), "model calls");
        assertEquals(cap.works(), fixture.work.calls(cap.id()), "work runs");
        assertEquals(0, fixture.echo.calls(cap.id()), "tool runs");
        assertEquals(fixture.model.prompts(cap.id()), fixture.replayedPrompts(cap.id()), "replayed prompts");
    }

    /** 第一次回答引了没读过的行，核对带着续写文字回到模型；第二次不再引用，放行（核对是纯函数，不再要工作）。结束前一共 1 轮。 */
    @Test
    void citationCheckIsUnaffected() throws Exception {
        fixture.submit("citation", "where?");
        assertEquals("COMPLETED", fixture.awaitEnd("citation", 1));
        assertEquals(List.of("UserPrompt", "ContextAssembled", "ModelReplied", "WorkDone[citation]", "ModelReplied"),
                fixture.events("citation"));
        assertEquals(List.of("msgs=1 last=user", "msgs=3 last=user"), fixture.prompts("citation"));
        assertEquals(fixture.model.prompts("citation"), fixture.replayedPrompts("citation"), "replayed prompts");
    }
}
