package tantalum.kernel.projection;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.hook.PromptPatch;
import tantalum.value.model.Prompt;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;

/** 从状态投影出一次模型调用的 {@code Prompt}。消息取会话的视图（压缩过就是替换段加新消息）。回放时重新计算即可还原，不单独落盘。 */
public final class PromptProjection {

    private PromptProjection() {}

    /** 会话冻结的 system 和工具，后面接上钩子补丁；普通调用和摘要调用都从这里取，两者前缀相同。 */
    public static Prompt project(SessionState session, PromptPatch patch) {
        SessionContext context = session.context();
        List<ToolSpec> tools = new ArrayList<>(context.tools());
        tools.addAll(patch.addTools());
        return new Prompt(context.system() + patch.systemAppend(), tools, session.view());
    }
}
