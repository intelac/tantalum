package tantalum.kernel.compaction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tantalum.kernel.compaction.Units.Unit;
import tantalum.kernel.step.ModelSteps;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.Compacting.Trigger;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.Plan;
import tantalum.value.state.SessionState;

/** 规则 1、2、4、5、6 的纯函数：要不要压、切在哪、压缩后的视图、给摘要调用的头部。 */
public final class Compactor {

    /** 发给摘要调用的头部；{@code dropped} 表示最早的单元因为放不下被丢了。 */
    public record Head(List<Message> messages, boolean dropped) {

        /** 除了上一次的摘要什么都不剩：这时没有东西可总结。 */
        public boolean nothingToSummarize() {
            return dropped && (messages.isEmpty() || messages.size() == 1 && SummaryPrompt.isSummary(messages.getFirst()));
        }
    }

    private Compactor() {}

    /**
     * 规则 1、2、4。{@code system} 和 {@code tools} 只在没有锚点时参与估算。
     * 返回空表示不压缩：会话没有设置、自动触发但没超阈值、或没有可压缩的头部。
     */
    public static Optional<Plan> plan(SessionState session, String system, List<ToolSpec> tools, Trigger trigger) {
        CompactionSettings settings = session.context() == null ? null : session.context().compaction();
        if (settings == null) {
            return Optional.empty();
        }
        List<Message> view = session.view();
        if (trigger == Trigger.AUTO && size(session, system, tools) <= settings.threshold()) {
            return Optional.empty();
        }
        List<Unit> units = Units.split(view);
        if (units.size() < 2) {
            return Optional.empty();
        }
        long budget = trigger == Trigger.AUTO ? settings.keepBudget() : 0;
        int firstKept = units.size() - 1;
        long kept = estimate(view, units.get(firstKept));
        while (firstKept > 0) {
            long next = estimate(view, units.get(firstKept - 1));
            if (kept + next > budget) {
                break;
            }
            kept += next;
            firstKept--;
        }
        if (firstKept == 0) {
            return Optional.empty();
        }
        int headMessages = units.get(firstKept).from();
        List<Message> head = view.subList(0, headMessages);
        // 规则 5：当前请求是视图里最新的用户亲笔消息；保留段里已有更新的，头部的旧消息就只进摘要。
        boolean requestKept = view.subList(headMessages, view.size()).stream().anyMatch(Compactor::isUserText);
        return Optional.of(new Plan(headMessages, firstKept, units.size() - firstKept, TokenEstimate.ofMessages(head),
                requestKept ? null : request(head)));
    }

    /** 认为有进展的最小收益：500 token，阈值很小时按比例缩小。初始值，没有参考依据。 */
    static long minGain(CompactionSettings settings) {
        return Math.min(500, settings.threshold() / 16);
    }

    /** 摘要最多能省下头部除去重复请求的部分；太少就不值得调模型。 */
    public static boolean summaryCanHelp(Plan plan, CompactionSettings settings) {
        long request = plan.request() == null ? 0 : TokenEstimate.ofMessage(plan.request());
        return plan.headEstimate() - request >= minGain(settings);
    }

    /** 只裁剪的视图：去推理块、截工具结果，不总结。 */
    public static List<Message> trimmed(List<Message> view, CompactionSettings settings) {
        return Trimming.trimAll(view, settings.maxKeptResultBytes());
    }

    /** 裁剪能带来明显进展吗。去掉一点推理块不算，否则每次失败都会白白丢掉 thinking。 */
    public static boolean trimHelps(List<Message> view, CompactionSettings settings) {
        return TokenEstimate.ofMessages(view) - TokenEstimate.ofMessages(trimmed(view, settings)) >= minGain(settings);
    }

    /** 手动压缩有没有可做的。 */
    public static boolean worthCompacting(SessionState session, String system, List<ToolSpec> tools) {
        return plan(session, system, tools, Trigger.MANUAL)
                .map(plan -> summaryCanHelp(plan, session.context().compaction()) || trimHelps(session.view(), session.context().compaction()))
                .orElse(false);
    }

    /** 规则 1：有锚点用实测加新增估算，否则全部估算。 */
    public static long size(SessionState session, String system, List<ToolSpec> tools) {
        if (session.anchor() != null) {
            List<Message> messages = session.messages();
            int from = Math.min(session.anchor().messageCount(), messages.size());
            return session.anchor().tokens() + TokenEstimate.ofMessages(messages.subList(from, messages.size()));
        }
        return TokenEstimate.ofText(system) + TokenEstimate.ofTools(tools) + TokenEstimate.ofMessages(session.view());
    }

    /** 规则 5：摘要、当前请求原文、处理过的保留段。 */
    public static List<Message> replacement(Plan plan, List<Message> view, String summary, CompactionSettings settings) {
        List<Message> out = new ArrayList<>();
        out.add(SummaryPrompt.summaryMessage(summary, filesTouched(view.subList(0, plan.headMessages()))));
        if (plan.request() != null) {
            out.add(plan.request());
        }
        out.addAll(Trimming.trimAll(view.subList(plan.headMessages(), view.size()), settings.maxKeptResultBytes()));
        return List.copyOf(out);
    }

    /**
     * 规则 6.2、6.3：头部去推理块、截工具结果；连同 system、工具和指令仍超过阈值时从最早的单元丢起。
     * {@code fixedEstimate} 是 system、工具和指令的估算。
     */
    public static Head headForSummary(Plan plan, List<Message> view, CompactionSettings settings, long fixedEstimate) {
        List<Message> head = Trimming.trimAll(view.subList(0, plan.headMessages()), settings.maxSummarizedResultBytes());
        boolean dropped = false;
        boolean hasSummary = !head.isEmpty() && SummaryPrompt.isSummary(head.getFirst());
        int floor = hasSummary ? 1 : 0;
        while (head.size() > floor && fixedEstimate + TokenEstimate.ofMessages(head) > settings.threshold()) {
            List<Unit> units = Units.split(head);
            Unit victim = units.get(floor);
            List<Message> rest = new ArrayList<>(head.subList(0, victim.from()));
            rest.addAll(head.subList(victim.to(), head.size()));
            head = rest;
            dropped = true;
        }
        return new Head(List.copyOf(head), dropped);
    }

    /** 旧摘要里的文件加上头部工具调用的 {@code path} 参数，保持先后、不重复。 */
    static List<String> filesTouched(List<Message> head) {
        Set<String> files = new LinkedHashSet<>();
        for (Message message : head) {
            files.addAll(SummaryPrompt.filesIn(message));
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolCallBlock call && call.arguments() instanceof Json.Obj arguments
                        && arguments.fields().get("path") instanceof Json.Str path) {
                    files.add(path.value());
                }
            }
        }
        return SummaryPrompt.capFiles(new ArrayList<>(files));
    }

    private static long estimate(List<Message> view, Unit unit) {
        return TokenEstimate.ofMessages(view.subList(unit.from(), unit.to()));
    }

    /** 头部里最新一条用户亲笔的文字消息：不是工具结果、不是上下文更新、不是摘要。 */
    static Message request(List<Message> head) {
        for (int i = head.size() - 1; i >= 0; i--) {
            Message message = head.get(i);
            if (isUserText(message)) {
                return message;
            }
        }
        return null;
    }

    static boolean isUserText(Message message) {
        if (message.role() != Role.USER || message.content().isEmpty()) {
            return false;
        }
        for (ContentBlock block : message.content()) {
            if (!(block instanceof TextBlock)) {
                return false;
            }
        }
        String text = ((TextBlock) message.content().getFirst()).text();
        return !text.startsWith(ModelSteps.CONTEXT_UPDATE_PREFIX) && !text.startsWith(SummaryPrompt.SUMMARY_PREFIX);
    }
}
