package tantalum.kernel.hook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tantalum.value.decision.Fact;
import tantalum.value.decision.HookDecided;
import tantalum.value.decision.HookReported;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.PromptPatch;
import tantalum.value.id.RunRef;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.HookTextBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionContext.HookState;
import tantalum.value.state.SessionContext.HookState.HookTool;
import tantalum.value.state.SessionState;

/**
 * 模型调用前钩子放行带的补丁怎么到模型（hook-patch）：不拼进 system 和工具列表，而是追加一条 {@link Role#SYSTEM} 消息。
 * 说明变了才追加（每个钩子一块，按注册顺序）；钩子加的工具定义接在会话工具列表后面、永远 deferred，用 {@link ToolChangeBlock}
 * 告诉模型多了、少了哪些。会话的 system、已发出的消息一个字不动，历史里的思考签名才继续有效。纯函数，回放结果相同。
 */
public final class HookPatches {

    /** 追加消息、更新上下文后的会话，以及要上报的事实。没变化时 {@code session} 就是传入的那一个。 */
    public record Applied(SessionState session, List<Fact> facts) {
        public Applied {
            facts = List.copyOf(facts);
        }
    }

    private HookPatches() {}

    /**
     * {@code facts} 是这次 {@code beforeModelCall} 遍历报的事实：按其中的 {@link HookDecided}（注册顺序、补丁长度）把合并后的说明
     * 切回每个钩子的那一段。会话还没冻结上下文时（理论上不会）原样返回。
     */
    public static Applied apply(SessionState session, List<Fact> facts, PromptPatch patch, RunRef run) {
        SessionContext context = session.context();
        if (context == null) {
            return new Applied(session, List.of());
        }
        HookState state = context.hookState();
        List<ContentBlock> blocks = new ArrayList<>();

        // 按钩子比较：只有说明变了的钩子重发自己那一块；一个钩子的说明变空不发（review-fixes 需求 7.1）
        List<HookTextBlock> now = patch.systemAppend().isEmpty() ? List.of() : split(patch.systemAppend(), facts);
        for (HookTextBlock piece : now) {
            boolean same = state.texts().stream().anyMatch(old -> old.hook().equals(piece.hook()) && old.text().equals(piece.text()));
            if (!same) {
                blocks.add(piece);
            }
        }

        // 和服务器上已有的工具重名的不算：那些工具在全集里，开不开由插件页决定，钩子加不了也撤不了
        Set<String> universe = new LinkedHashSet<>(context.tools().stream().map(ToolSpec::name).toList());
        List<String> desired = new ArrayList<>(new LinkedHashSet<>(patch.addTools().stream().map(ToolSpec::name)
                .filter(name -> !universe.contains(name)).toList()));
        List<String> added = desired.stream().filter(name -> !state.active().contains(name)).toList();
        List<String> removed = state.active().stream().filter(name -> !desired.contains(name)).toList();
        List<HookTool> tools = new ArrayList<>(state.tools());
        Set<String> known = new LinkedHashSet<>(tools.stream().map(tool -> tool.tool().name()).toList());
        for (ToolSpec tool : patch.addTools()) {
            if (!universe.contains(tool.name()) && known.add(tool.name())) {
                tools.add(new HookTool(adder(tool.name(), facts), tool.deferred(false)));
            }
        }
        if (!added.isEmpty() || !removed.isEmpty()) {
            blocks.add(new ToolChangeBlock(added, removed));
        }

        HookState next = new HookState(now, tools, desired);
        if (blocks.isEmpty()) {
            return new Applied(next.equals(state) ? session : session.withContext(context.withHooks(next)), List.of());
        }
        if (!session.messages().isEmpty() && session.messages().getLast().role() == Role.ASSISTANT) {
            // 模型调用前最后一条总是用户消息或已有的 system 更新；万一不是，system 消息放在这里会被拒绝，宁可不加
            return new Applied(session, List.of(new HookReported(run, "hook-patch",
                    "patch skipped: the last message is from the assistant")));
        }
        return new Applied(session.append(new Message(Role.SYSTEM, blocks, false)).withContext(context.withHooks(next)), List.of());
    }

    /** 按事实里各钩子的补丁长度切；长度对不上就整段算一块、来源记 "?"，不丢文字。 */
    private static List<HookTextBlock> split(String text, List<Fact> facts) {
        List<HookTextBlock> blocks = new ArrayList<>();
        int offset = 0;
        for (HookDecided decided : patches(facts)) {
            if (decided.systemChars() == 0) {
                continue;
            }
            if (offset + decided.systemChars() > text.length()) {
                return List.of(new HookTextBlock("?", text));
            }
            blocks.add(new HookTextBlock(decided.hook(), text.substring(offset, offset + decided.systemChars())));
            offset += decided.systemChars();
        }
        if (offset != text.length()) {
            return List.of(new HookTextBlock("?", text));
        }
        return blocks;
    }

    private static String adder(String tool, List<Fact> facts) {
        return patches(facts).stream().filter(decided -> decided.tools().contains(tool)).map(HookDecided::hook).findFirst().orElse("?");
    }

    private static List<HookDecided> patches(List<Fact> facts) {
        return facts.stream().filter(HookDecided.class::isInstance).map(HookDecided.class::cast)
                .filter(decided -> decided.kind() == HookDecided.Kind.PATCH && decided.point() == HookPoint.BEFORE_MODEL_CALL).toList();
    }
}
