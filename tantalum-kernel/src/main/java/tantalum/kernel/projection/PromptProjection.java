package tantalum.kernel.projection;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.hook.PromptPatch;
import tantalum.value.model.Prompt;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 从状态投影出一次模型调用的 {@code Prompt}。消息取会话的视图（压缩过就是替换段加新消息）。回放时重新计算即可还原，不单独落盘。 */
public final class PromptProjection {

    private PromptProjection() {}

    public static Prompt project(RunState run, SessionState session, PromptPatch patch) {
        List<ToolSpec> tools = new ArrayList<>(run.toolSpecs());
        tools.addAll(patch.addTools());
        return new Prompt(run.system() + patch.systemAppend(), tools, session.view());
    }
}
