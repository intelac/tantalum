package tantalum.shell.conversation;

import tantalum.value.id.Principal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.kernel.Kernel;
import tantalum.kernel.compaction.SummaryPrompt;
import tantalum.shell.conversation.TranscriptItem.CompactionMark;
import tantalum.shell.conversation.TranscriptItem.RunStatus;
import tantalum.shell.loop.Projection;
import tantalum.shell.conversation.TranscriptItem.Text;
import tantalum.shell.conversation.TranscriptItem.Thinking;
import tantalum.shell.conversation.TranscriptItem.ToolStep;
import tantalum.shell.conversation.TranscriptItem.UserMessage;
import tantalum.value.command.Command;
import tantalum.value.command.RunTool;
import tantalum.value.decision.CompactionFailed;
import tantalum.value.decision.ContextCompacted;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.decision.PhaseChanged;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.state.ToolSlot;

/**
 * 事件到对话条目的增量投影，按会话保存。输入是内核处理完一个事件后的 {@code (envelope, decision)}，
 * 实时和回放走同一个函数，所以结果相同。只改内存，不做 I/O。
 */
public final class Transcript implements Projection {

    /** 一个会话的条目和为了增量更新记住的少量位置。 */
    private static final class Conversation {
        final Map<String, TranscriptItem> items = new LinkedHashMap<>();
        /** 排队中的用户消息 ID，运行开始时按先后取出。 */
        final Deque<String> queued = new ArrayDeque<>();
        /** 工具调用 ID 到步骤条目 ID。 */
        final Map<String, String> stepByCall = new HashMap<>();
        /** 已看过的会话消息数，用来发现这次新追加的消息。 */
        int seenMessages;
        /** 这个会话最后投影过的事件序号；快照带上它，客户端据此和 SSE 推送合并。 */
        long lastSeq = -1;
    }

    /** 一个会话的全部条目和它们截至的事件序号：序号更大的 SSE 推送比快照新，反之快照更新。 */
    public record Snapshot(long seq, List<TranscriptItem> items) {}

    /** 一个会话的完整内部状态，可以写进快照，恢复后接着处理事件和没中断时相同。 */
    public record Part(List<TranscriptItem> items, List<String> queued, Map<String, String> stepByCall, int seenMessages, long lastSeq) {

        public Part {
            items = List.copyOf(items);
            queued = List.copyOf(queued);
            stepByCall = Map.copyOf(stepByCall);
        }
    }

    private final Map<SessionId, Conversation> sessions = new ConcurrentHashMap<>();

    /** 没有该会话时返回空的状态。 */
    @Override
    public Part export(SessionId session) {
        Conversation conversation = sessions.get(session);
        if (conversation == null) {
            return new Part(List.of(), List.of(), Map.of(), 0, -1);
        }
        synchronized (conversation) {
            return new Part(List.copyOf(conversation.items.values()), List.copyOf(conversation.queued), conversation.stepByCall,
                    conversation.seenMessages, conversation.lastSeq);
        }
    }

    @Override
    public void restore(SessionId session, Part part) {
        Conversation conversation = new Conversation();
        part.items().forEach(item -> conversation.items.put(item.id(), item));
        conversation.queued.addAll(part.queued());
        conversation.stepByCall.putAll(part.stepByCall());
        conversation.seenMessages = part.seenMessages();
        conversation.lastSeq = part.lastSeq();
        sessions.put(session, conversation);
    }

    @Override
    public void remove(SessionId session) {
        sessions.remove(session);
    }

    /** 没有该会话时返回空列表。 */
    public List<TranscriptItem> items(SessionId session) {
        return snapshot(session).items();
    }

    /** 条目和序号在同一把锁里读出，所以彼此一致。没有该会话时序号为 -1。 */
    public Snapshot snapshot(SessionId session) {
        Conversation conversation = sessions.get(session);
        if (conversation == null) {
            return new Snapshot(-1, List.of());
        }
        synchronized (conversation) {
            return new Snapshot(conversation.lastSeq, List.copyOf(conversation.items.values()));
        }
    }

    public Set<SessionId> sessions() {
        return Set.copyOf(sessions.keySet());
    }

    /** 内核处理完一个事件后调用。返回这次变化的条目，按变化先后排列；同一条目改了几次只返回最终版本。 */
    @Override
    public List<TranscriptItem> apply(EventEnvelope envelope, Decision decision) {
        Conversation conversation = sessions.computeIfAbsent(envelope.session(), key -> new Conversation());
        Map<String, TranscriptItem> changed = new LinkedHashMap<>();
        synchronized (conversation) {
            new Pass(conversation, envelope, decision, changed).run();
            conversation.lastSeq = envelope.seq();
        }
        return List.copyOf(changed.values());
    }

    /** 一次事件的投影，持有这次用到的所有上下文。 */
    private static final class Pass {
        private final Conversation conversation;
        private final EventEnvelope envelope;
        private final KernelState state;
        private final Decision decision;
        private final Map<String, TranscriptItem> changed;
        private final SessionId session;
        private final Instant at;
        /** 这次提交直接开始的运行；排队取出的运行不算。 */
        private RunId startedBySubmission;

        Pass(Conversation conversation, EventEnvelope envelope, Decision decision, Map<String, TranscriptItem> changed) {
            this.conversation = conversation;
            this.envelope = envelope;
            this.state = decision.newState();
            this.decision = decision;
            this.changed = changed;
            this.session = envelope.session();
            this.at = envelope.at();
        }

        void run() {
            Event event = envelope.event();
            switch (event) {
                case UserPrompt prompt -> submission(prompt.text(), false);
                case FollowUpSubmitted followUp -> submission(followUp.text(), false);
                case SteerSubmitted steer -> submission(steer.text(), true);
                case ModelReplied reply -> reply(reply);
                case ModelInterrupted interrupted -> interrupted(interrupted);
                case ModelFailed failed -> failed(failed);
                case ToolFinished finished -> toolFinished(finished);
                default -> { }
            }
            for (Command command : decision.commands()) {
                if (command instanceof RunTool tool && tool.run().session().equals(session)) {
                    dispatched(tool);
                }
            }
            for (Fact fact : decision.facts()) {
                if (fact instanceof PhaseChanged phase && phase.run().session().equals(session)) {
                    phaseChanged(phase.run().run(), phase.phase());
                }
            }
            compaction();
            blockedSlots();
            appendedResults();
        }

        /** 这个决定里属于本会话的压缩事实，没有则为 null。 */
        private Fact compactionFact() {
            for (Fact fact : decision.facts()) {
                if ((fact instanceof ContextCompacted || fact instanceof CompactionFailed) && fact.run().session().equals(session)) {
                    return fact;
                }
            }
            return null;
        }

        /** 压缩成功或失败各一个条目；摘要调用的回复不是对话文本，在 {@link #reply} 里跳过。 */
        private void compaction() {
            Fact fact = compactionFact();
            String id = TranscriptItem.id(envelope.seq(), 0);
            switch (fact) {
                case ContextCompacted ok -> {
                    // 只裁剪时没有摘要，哪怕这个事件是一次没采用的摘要回复。
                    String summary = !ok.trimOnly() && envelope.event() instanceof ModelReplied reply ? SummaryPrompt.text(reply.content()) : "";
                    put(new CompactionMark(id, session, ok.run().run(), at, ok.trigger(), summary, ok.estimateBefore(),
                            ok.estimateAfter(), ok.usage(), null));
                }
                case CompactionFailed failed -> put(new CompactionMark(id, session, failed.run().run(), at, failed.trigger(),
                        null, 0, 0, null, failed.reason()));
                case null, default -> { }
            }
        }

        private void put(TranscriptItem item) {
            conversation.items.put(item.id(), item);
            changed.put(item.id(), item);
        }

        private TranscriptItem get(String id) {
            return conversation.items.get(id);
        }

        /** 用户提交：直接开始运行、插进当前运行，或排队。 */
        private void submission(String text, boolean steer) {
            String id = TranscriptItem.id(envelope.seq(), 0);
            RunId fresh = RunId.of(session, envelope.seq());
            SessionState current = state.sessions().get(session);
            if (state.runs().containsKey(fresh)) {
                startedBySubmission = fresh;
                put(new UserMessage(id, session, fresh, at, text, false));
            } else if (steer) {
                put(new UserMessage(id, session, current == null ? null : current.activeRun(), at, text, false));
            } else {
                put(new UserMessage(id, session, null, at, text, true));
                conversation.queued.addLast(id);
            }
        }

        private void reply(ModelReplied reply) {
            RunId run = runOfEvent();
            if (run == null || compactionFact() != null) {
                return;
            }
            List<ContentBlock> content = reply.content();
            boolean truncated = reply.stop() == StopReason.MAX_TOKENS && content.stream().noneMatch(ToolCallBlock.class::isInstance);
            for (int i = 0; i < content.size(); i++) {
                String id = TranscriptItem.id(envelope.seq(), i);
                switch (content.get(i)) {
                    case ReasoningBlock reasoning -> put(thinking(id, run, reasoning));
                    case TextBlock text -> put(new Text(id, session, run, at, text.text(), false, truncated));
                    case ToolCallBlock call -> {
                        put(ToolStep.waiting(id, session, run, at, call));
                        conversation.stepByCall.put(call.id(), id);
                    }
                    case ToolResultBlock ignored -> { }
                }
            }
        }

        /** 供应商把思考文本放在不同位置：Claude 在对象的 {@code thinking} 字段，OpenAI 兼容接口是字符串。 */
        private Thinking thinking(String id, RunId run, ReasoningBlock reasoning) {
            return switch (reasoning.raw()) {
                case Json.Str text -> new Thinking(id, session, run, at, text.value(), false);
                case Json.Obj object when object.fields().get("thinking") instanceof Json.Str text ->
                        new Thinking(id, session, run, at, text.value(), false);
                default -> new Thinking(id, session, run, at, "", true);
            };
        }

        private void interrupted(ModelInterrupted interrupted) {
            RunId run = runOfEvent();
            if (run != null && !interrupted.text().isEmpty()) {
                put(new Text(TranscriptItem.id(envelope.seq(), 0), session, run, at, interrupted.text(), true, false));
            }
        }

        /** 摘要调用失败不算运行失败：运行照常继续，原因在压缩条目里。 */
        private void failed(ModelFailed failed) {
            RunId run = runOfEvent();
            boolean summaryCall = run != null && state.runs().get(run) instanceof RunState current && current.compacting() != null;
            if (run != null && !summaryCall && compactionFact() == null && get(RunStatus.idOf(run)) instanceof RunStatus status) {
                put(status.failedWith(failed.reason(), at));
            }
        }

        private void toolFinished(ToolFinished finished) {
            if (step(finished.toolCallId()) instanceof ToolStep step && !step.finished()) {
                put(step.finished(new ToolResultBlock(finished.toolCallId(), finished.text(), finished.error(), null), at));
            }
        }

        /** 同一事件发出的调用是一组并行调用，组名取事件序号。 */
        private void dispatched(RunTool tool) {
            if (step(tool.call().id()) instanceof ToolStep step) {
                put(step.running("g" + envelope.seq(), at));
            }
        }

        private void phaseChanged(RunId run, String phase) {
            RunStatus status = get(RunStatus.idOf(run)) instanceof RunStatus existing ? existing : null;
            if (status == null) {
                status = RunStatus.started(session, run, at, phase);
                if (!run.equals(startedBySubmission) && !conversation.queued.isEmpty()) {
                    UserMessage queued = (UserMessage) get(conversation.queued.pollFirst());
                    put(queued.started(run));
                }
            } else if (state.runs().get(run) instanceof RunState current && current.phase() instanceof Ended ended) {
                status = status.ended(ended.reason(), ended.detail(), at);
            } else {
                status = status.inPhase(phase, at);
            }
            put(status);
        }

        /** 被钩子拦截或参数无效的调用在工具轮里直接带着结果，没有自己的事件。 */
        private void blockedSlots() {
            RunId run = runOfEvent();
            if (run == null || !(state.runs().get(run) instanceof RunState current) || current.tools() == null) {
                return;
            }
            for (ToolSlot slot : current.tools().slots()) {
                if (slot.status() == ToolSlot.Status.BLOCKED && step(slot.call().id()) instanceof ToolStep step
                        && !step.finished()) {
                    put(step.blocked(slot.result(), at));
                }
            }
        }

        /** 工具轮结束时结果被追加成一条消息。还没结束的步骤只剩两种：整轮都被拦截，或运行提前结束时的合成结果。 */
        private void appendedResults() {
            SessionState current = state.sessions().get(session);
            if (current == null) {
                return;
            }
            List<Message> messages = current.messages();
            for (int i = conversation.seenMessages; i < messages.size(); i++) {
                Message message = messages.get(i);
                if (message.role() != Role.USER) {
                    continue;
                }
                for (ContentBlock block : message.content()) {
                    if (block instanceof ToolResultBlock result && step(result.toolCallId()) instanceof ToolStep step
                            && !step.finished()) {
                        put(result.code() != null ? step.finished(result, at) : step.blocked(result, at));
                    }
                }
            }
            conversation.seenMessages = messages.size();
        }

        private TranscriptItem step(String callId) {
            String id = conversation.stepByCall.get(callId);
            return id == null ? null : get(id);
        }

        /** 事件所属的运行：结果事件自带；提交事件看它开始的运行；其余取会话当前运行。 */
        private RunId runOfEvent() {
            if (envelope.run() != null) {
                return envelope.run();
            }
            if (startedBySubmission != null) {
                return startedBySubmission;
            }
            SessionState current = state.sessions().get(session);
            return current == null ? null : current.activeRun();
        }
    }

    /** 回放：每个事件先过内核再投影，和实时路径用同一个 {@link #apply}。返回折叠出的状态。 */
    public KernelState replay(Kernel kernel, List<EventEnvelope> events) {
        KernelState state = KernelState.empty();
        Map<Principal, Long> previous = new HashMap<>();
        for (EventEnvelope envelope : events) {
            Long last = previous.put(envelope.principal(), envelope.seq());
            if (last != null && envelope.seq() <= last) {
                throw new IllegalArgumentException("event out of order at seq " + envelope.seq());
            }
            Decision decision = kernel.step(state, envelope);
            state = decision.newState();
            apply(envelope, decision);
        }
        return state;
    }
}
