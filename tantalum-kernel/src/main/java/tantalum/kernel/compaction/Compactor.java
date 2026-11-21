package tantalum.kernel.compaction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tantalum.kernel.attachment.ImageBudget;
import tantalum.kernel.compaction.Units.Unit;
import tantalum.kernel.projection.ContextUpdate;
import tantalum.value.json.Json;
import tantalum.value.model.AttachedBlock;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.SkillBlock;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.Compacting.Trigger;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.Plan;
import tantalum.value.state.SessionState;
import tantalum.value.state.UsageAnchor;
import tantalum.value.todo.TodoList;

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
        TokenEstimate estimate = TokenEstimate.of(settings);
        long budget = trigger == Trigger.AUTO ? settings.keepBudget() : 0;
        int firstKept = units.size() - 1;
        long kept = estimate(estimate, view, units.get(firstKept));
        while (firstKept > 0) {
            long next = estimate(estimate, view, units.get(firstKept - 1));
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
        boolean requestKept = view.subList(headMessages, view.size()).stream().anyMatch(Compactor::isRequest);
        return Optional.of(new Plan(headMessages, firstKept, units.size() - firstKept, estimate.ofMessages(head),
                requestKept ? null : request(head)));
    }

    /** 认为有进展的最小收益：500 token，阈值很小时按比例缩小。初始值，没有参考依据。 */
    static long minGain(CompactionSettings settings) {
        return Math.min(500, settings.threshold() / 16);
    }

    /** 摘要最多能省下头部除去重复请求的部分；太少就不值得调模型。 */
    public static boolean summaryCanHelp(Plan plan, CompactionSettings settings) {
        long request = plan.request() == null ? 0 : TokenEstimate.of(settings).ofMessage(plan.request());
        return plan.headEstimate() - request >= minGain(settings);
    }

    /** 只裁剪的视图：去推理块、截工具结果，不总结。 */
    public static List<Message> trimmed(List<Message> view, CompactionSettings settings) {
        return Trimming.trimAll(view, settings.maxKeptResultBytes());
    }

    /** 裁剪能带来明显进展吗。去掉一点推理块不算，否则每次失败都会白白丢掉 thinking。 */
    public static boolean trimHelps(List<Message> view, CompactionSettings settings) {
        TokenEstimate estimate = TokenEstimate.of(settings);
        return estimate.ofMessages(view) - estimate.ofMessages(trimmed(view, settings)) >= minGain(settings);
    }

    /** 手动压缩有没有可做的。 */
    public static boolean worthCompacting(SessionState session, String system, List<ToolSpec> tools) {
        return plan(session, system, tools, Trigger.MANUAL)
                .map(plan -> summaryCanHelp(plan, session.context().compaction()) || trimHelps(session.view(), session.context().compaction()))
                .orElse(false);
    }

    /** 规则 1：有锚点用实测加新增估算，否则全部估算。省略过的图片按省略说明算，和实际发送的一致。 */
    public static long size(SessionState session, String system, List<ToolSpec> tools) {
        CompactionSettings settings = session.context() == null ? null : session.context().compaction();
        TokenEstimate estimate = settings == null ? TokenEstimate.LEGACY : TokenEstimate.of(settings);
        if (session.anchor() != null) {
            // 锚点从头数；每次压缩都清掉锚点，窗口只在压缩时移动，所以锚点总在窗口里
            List<Message> messages = session.messages();
            int from = Math.min(Math.max(session.anchor().messageCount(), session.windowStart()), session.historySize()) - session.windowStart();
            return session.anchor().tokens()
                    + estimate.ofMessages(ImageBudget.omit(messages.subList(from, messages.size()), session.omittedImages()));
        }
        return estimate.ofText(system) + estimate.ofTools(tools)
                + estimate.ofMessages(ImageBudget.omit(session.view(), session.omittedImages()));
    }

    /** 校准最多把估算缩到四分之一：再小多半是模型报的用量不对。 */
    static final double MIN_SCALE = 0.25;

    /**
     * 用模型报的实测用量校准的估算，只用来判断摘要调用放不放得下。系数是按一种文字定的（本机 1.75 按中文），工具定义多是英文 JSON，
     * 实际约 3.4 字节一个 token：估算约是实际的两倍，窗口小的模型（9k 以下）上摘要调用光固定部分就“放不下”，自动压缩整轮放弃
     * （agent-eval 的 todo-compact 场景发现的）。
     * 做法：锚点是上次回复的实测（那时的 system、工具和视图，加上回复）；用现在的系数估同一段内容，估得比实测多就按比例缩小，
     * 最多缩到 {@value #MIN_SCALE}。估得比实测少时照旧（只往少估的方向校准会有放不下的风险，不做）；没有锚点时照旧。
     * 锚点之后又加的消息在视图末尾，所以锚点那一刻的视图是现在的视图去掉末尾那几条。
     */
    public static TokenEstimate calibrated(SessionState session, String system, List<ToolSpec> tools) {
        CompactionSettings settings = session.context() == null ? null : session.context().compaction();
        TokenEstimate base = settings == null ? TokenEstimate.LEGACY : TokenEstimate.of(settings);
        UsageAnchor anchor = session.anchor();
        if (anchor == null || anchor.tokens() <= 0) {
            return base;
        }
        List<Message> view = session.view();
        int after = Math.max(0, session.historySize() - Math.min(anchor.messageCount(), session.historySize()));
        List<Message> atAnchor = view.subList(0, Math.max(0, view.size() - after));
        long estimated = base.ofText(system) + base.ofTools(tools)
                + base.ofMessages(ImageBudget.omit(atAnchor, session.omittedImages()));
        if (estimated <= anchor.tokens()) {
            return base;
        }
        return base.scaled(Math.max(MIN_SCALE, (double) anchor.tokens() / estimated));
    }

    /** 上下文现在占了多少，以及系统提示、工具定义、消息各占多少；三项之和等于 {@code used}。 */
    public record Occupancy(long used, long system, long tools, long messages) {}

    /**
     * 给页面看的占用明细。{@code used} 就是 {@link #size}（压缩判断用的那个数）；三项各自估算，再按比例摊到 {@code used} 上，
     * 消息取余数。没有锚点（还没调过模型，或者模型没报用量）时没有实测可摊，返回空。
     */
    public static Optional<Occupancy> occupancy(SessionState session, String system, List<ToolSpec> tools) {
        if (session.anchor() == null) {
            return Optional.empty();
        }
        CompactionSettings settings = session.context() == null ? null : session.context().compaction();
        TokenEstimate estimate = settings == null ? TokenEstimate.LEGACY : TokenEstimate.of(settings);
        long used = size(session, system, tools);
        long ofSystem = estimate.ofText(system);
        long ofTools = estimate.ofTools(tools);
        long total = ofSystem + ofTools + estimate.ofMessages(ImageBudget.omit(session.view(), session.omittedImages()));
        if (total <= 0) {
            return Optional.of(new Occupancy(used, 0, 0, used));
        }
        long systemShare = (long) ((double) used * ofSystem / total);
        long toolsShare = (long) ((double) used * ofTools / total);
        return Optional.of(new Occupancy(used, systemShare, toolsShare, used - systemShare - toolsShare));
    }

    /** 规则 5：摘要、当前请求原文、处理过的保留段。 */
    public static List<Message> replacement(Plan plan, List<Message> view, String summary, CompactionSettings settings) {
        List<Message> out = new ArrayList<>();
        List<Message> head = view.subList(0, plan.headMessages());
        out.add(SummaryPrompt.summaryMessage(summary, filesTouched(head), latestTodos(head).orElse(null)));
        if (plan.request() != null) {
            out.add(plan.request());
        }
        out.addAll(Trimming.trimAll(view.subList(plan.headMessages(), view.size()), settings.maxKeptResultBytes()));
        return List.copyOf(out);
    }

    /**
     * 规则 6.2、6.3：头部去推理块、截工具结果；连同 system、工具和指令仍超过模型能收的量（窗口减预留）时从最早的单元丢起。
     * 用的不是用户选的压缩点：压缩点只决定什么时候压缩，摘要调用能收多少由模型定。
     * {@code fixedEstimate} 是 system、工具和指令的估算。
     */
    public static Head headForSummary(Plan plan, List<Message> view, CompactionSettings settings, long fixedEstimate) {
        return headForSummary(plan, view, settings, fixedEstimate, TokenEstimate.of(settings));
    }

    /** 同上，用给定的估算（摘要调用用 {@link #calibrated} 校准过的）。 */
    public static Head headForSummary(Plan plan, List<Message> view, CompactionSettings settings, long fixedEstimate,
                                      TokenEstimate estimate) {
        List<Message> head = Trimming.trimAll(view.subList(0, plan.headMessages()), settings.maxSummarizedResultBytes());
        boolean dropped = false;
        boolean hasSummary = !head.isEmpty() && SummaryPrompt.isSummary(head.getFirst());
        int floor = hasSummary ? 1 : 0;
        while (head.size() > floor && fixedEstimate + estimate.ofMessages(head) > settings.capacity()) {
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

    /**
     * 被压掉的部分里最后一张待办清单（todo-tool 需求 4）：倒着找最后一次成功的 {@code todo_write}（参数合格、结果不是错误），
     * 找不到再看上一个摘要附的那张。错误的调用不算——那次清单没有换。
     */
    static Optional<TodoList> latestTodos(List<Message> head) {
        Set<String> failed = new java.util.HashSet<>();
        for (Message message : head) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolResultBlock result && result.error()) {
                    failed.add(result.toolCallId());
                }
            }
        }
        for (int i = head.size() - 1; i >= 0; i--) {
            List<ContentBlock> content = head.get(i).content();
            for (int j = content.size() - 1; j >= 0; j--) {
                if (content.get(j) instanceof ToolCallBlock call && call.name().equals(TodoList.TOOL) && !failed.contains(call.id())) {
                    Optional<TodoList> list = TodoList.tryParse(call.arguments());
                    if (list.isPresent()) {
                        return list;
                    }
                }
            }
            Optional<TodoList> earlier = SummaryPrompt.todosIn(head.get(i));
            if (earlier.isPresent()) {
                return earlier;
            }
        }
        return Optional.empty();
    }

    private static long estimate(TokenEstimate estimate, List<Message> view, Unit unit) {
        return estimate.ofMessages(view.subList(unit.from(), unit.to()));
    }

    /** 头部里最新一条用户亲笔的文字消息：不是工具结果、不是上下文更新、不是摘要。 */
    static Message request(List<Message> head) {
        for (int i = head.size() - 1; i >= 0; i--) {
            Message message = head.get(i);
            if (isRequest(message)) {
                return message;
            }
        }
        return null;
    }

    /**
     * 规则 5 原样留下的“最近一次请求”：你亲笔的话，或者帮手收到的那条交代（它要做的全部内容都在里面，只留摘要会丢掉，delivery 审查发现）。
     * 交代不是你说的话，所以不算 {@link #isUserText}。
     */
    static boolean isRequest(Message message) {
        return isUserText(message) || message.role() == Role.USER && message.content().size() == 1
                && message.content().getFirst() instanceof tantalum.value.model.AssignmentBlock;
    }

    /**
     * 用户亲笔的消息：第一块是文字，其余只能是文字、附件或点名的技能内容（带图片、文件、技能提问也算），不是上下文更新，也不是摘要。
     * 压缩后留下的“最近一次提问”是整条消息，点名的技能内容跟着它一起留下。
     * 插件经 {@code HookMessages} 用同一条规则。
     */
    public static boolean isUserText(Message message) {
        if (message.role() != Role.USER || message.content().isEmpty() || !(message.content().getFirst() instanceof TextBlock)) {
            return false;
        }
        for (ContentBlock block : message.content()) {
            if (!(block instanceof TextBlock) && !(block instanceof AttachmentBlock) && !(block instanceof SkillBlock)
                    && !(block instanceof AttachedBlock)) {
                return false;
            }
        }
        String text = ((TextBlock) message.content().getFirst()).text();
        return !ContextUpdate.isUpdate(text) && !text.startsWith(SummaryPrompt.SUMMARY_PREFIX);
    }
}
