package tantalum.examples.hooks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.PromptPatch;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.KernelState;

/**
 * 测试用钩子：只管自己的会话，只在 {@code point} 返回 {@code result}，其余位置放行。
 * 自己要的工作做完后再被问到时放行（{@code forever} 时接着要）。{@code onWorkResult} 只对 {@link MatrixWork#SOURCE} 的工作出结果，
 * 那份工作由另一个 {@code source} 钩子要。纯函数：只看状态和上下文。
 */
final class MatrixHook implements KernelHook {

    enum Result { ALLOW, PATCH, STOP, WORK_OK, WORK_FAIL, WORK_TIMEOUT, WORK_HOLD, WORK_CONTINUE, THROW, NULL }

    static final String PATCH_TEXT = "\nMATRIX-PATCH";
    static final ToolSpec PATCH_TOOL = new ToolSpec("matrix_extra", "Added by the matrix patch.",
            new Json.Obj(Map.of("type", new Json.Str("object"))));
    static final String STOP_REASON = "matrix stop";

    private final String session;
    private final HookPoint point;
    private final Result result;
    private final boolean mandatory;
    private final String workType;
    private final boolean forever;

    private MatrixHook(String session, HookPoint point, Result result, boolean mandatory, String workType, boolean forever) {
        this.session = session;
        this.point = point;
        this.result = result;
        this.mandatory = mandatory;
        this.workType = workType;
        this.forever = forever;
    }

    MatrixHook(String session, HookPoint point, Result result, boolean mandatory) {
        this(session, point, result, mandatory, MatrixWork.TYPE, false);
    }

    /** 在 {@code point} 要一轮 {@link MatrixWork#SOURCE} 工作，给 {@code onWorkResult} 的格子做引子。 */
    static MatrixHook source(String session, HookPoint point) {
        return new MatrixHook(session, point, Result.WORK_OK, false, MatrixWork.SOURCE, false);
    }

    /** 自己的工作做完后接着要，用来撞工作轮数上限。 */
    MatrixHook forever() {
        return new MatrixHook(session, point, result, mandatory, workType, true);
    }

    static String nameOf(String session) {
        return "matrix-" + session;
    }

    @Override
    public String name() {
        return workType.equals(MatrixWork.SOURCE) ? nameOf(session) + "-source" : nameOf(session);
    }

    @Override
    public boolean mandatory() {
        return mandatory;
    }

    @Override
    public HookResult beforeModelCall(KernelState state, HookContext context) {
        return at(HookPoint.BEFORE_MODEL_CALL, context);
    }

    @Override
    public HookResult beforeToolCall(KernelState state, HookContext context) {
        return at(HookPoint.BEFORE_TOOL_CALL, context);
    }

    @Override
    public HookResult onFailure(KernelState state, HookContext context) {
        return at(HookPoint.ON_FAILURE, context);
    }

    @Override
    public HookResult beforeRunEnd(KernelState state, HookContext context) {
        return at(HookPoint.BEFORE_RUN_END, context);
    }

    @Override
    public HookResult onWorkResult(KernelState state, HookContext context) {
        return at(HookPoint.ON_WORK_RESULT, context);
    }

    @Override
    public HookResult beforeCompact(KernelState state, HookContext context) {
        return at(HookPoint.BEFORE_COMPACT, context);
    }

    private HookResult at(HookPoint here, HookContext context) {
        if (here != point || !context.run().session().value().equals(session)) {
            return Allow.PLAIN;
        }
        WorkDone work = context.work();
        if (here == HookPoint.ON_WORK_RESULT) {
            boolean trigger = work != null && work.workType().equals(MatrixWork.SOURCE);
            boolean again = forever && work != null && work.workType().equals(workType);
            if (!trigger && !again) {
                return Allow.PLAIN;
            }
        } else if (!forever && work != null && work.workType().equals(workType)) {
            return Allow.PLAIN;
        }
        return switch (result) {
            case ALLOW -> Allow.PLAIN;
            case PATCH -> new Allow(new PromptPatch(PATCH_TEXT, List.of(PATCH_TOOL)));
            case STOP -> new Stop(STOP_REASON);
            case WORK_OK -> work("ok");
            case WORK_FAIL -> work("fail");
            case WORK_TIMEOUT -> work("timeout");
            case WORK_HOLD -> work("hold");
            case WORK_CONTINUE -> work("continue");
            case THROW -> throw new IllegalStateException("matrix hook failed");
            case NULL -> null;
        };
    }

    /** 键按固定顺序放：输入会写进 journal。 */
    private NeedWork work(String mode) {
        Map<String, Json> input = new LinkedHashMap<>();
        input.put("cell", new Json.Str(session));
        input.put("mode", new Json.Str(mode));
        return new NeedWork(workType, new Json.Obj(input));
    }
}
