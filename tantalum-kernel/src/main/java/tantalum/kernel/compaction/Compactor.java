package tantalum.kernel.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tantalum.kernel.compaction.Units.Unit;
import tantalum.kernel.step.ModelSteps;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.Compacting.Trigger;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.Plan;
import tantalum.value.state.SessionState;

/** 规则 1、2、4、5、6 的纯函数：要不要压、切在哪、压缩后的视图、给摘要调用的头部。 */
public final class Compactor {

    /** 发给摘要调用的头部；{@code dropped} 表示最早的单元因为放不下被丢了。 */
    public record Head(List<Message> messages, boolean dropped) {}

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
        long budget = trigger == Trigger.OVERFLOW ? 0 : settings.keepBudget();
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
        out.add(SummaryPrompt.summaryMessage(summary));
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
        while (!head.isEmpty() && fixedEstimate + TokenEstimate.ofMessages(head) > settings.threshold()) {
            List<Unit> units = Units.split(head);
            head = head.subList(units.getFirst().to(), head.size());
            dropped = true;
        }
        return new Head(List.copyOf(head), dropped);
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
        return !text.startsWith(ModelSteps.CONTEXT_UPDATE) && !text.startsWith(SummaryPrompt.SUMMARY_PREFIX);
    }
}
