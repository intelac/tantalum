package tantalum.shell.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import tantalum.kernel.compaction.SummaryPrompt;
import tantalum.shell.conversation.TranscriptItem.CompactionMark;
import tantalum.shell.conversation.TranscriptItem.RunStatus;
import tantalum.shell.conversation.TranscriptItem.Text;
import tantalum.shell.conversation.TranscriptItem.Thinking;
import tantalum.shell.conversation.TranscriptItem.ToolStep;
import tantalum.shell.conversation.TranscriptItem.UserMessage;
import tantalum.shell.model.Reasoning;
import tantalum.value.command.Command;
import tantalum.value.command.RunTool;
import tantalum.value.attachment.AttachmentRef;
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
import tantalum.value.model.AttachmentBlock;
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

/** 一次事件的投影，持有这次用到的所有上下文。 */
final class TranscriptPass {
    private final Transcript.Conversation conversation;
    private final EventEnvelope envelope;
    private final KernelState state;
    private final Decision decision;
    private final Map<String, TranscriptItem> changed;
    private final SessionId session;
    private final Instant at;
    /** 这次提交直接开始的运行；排队取出的运行不算。 */
    private RunId startedBySubmission;

    TranscriptPass(Transcript.Conversation conversation, EventEnvelope envelope, Decision decision, Map<String, TranscriptItem> changed) {
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
            case UserPrompt prompt -> submission(prompt.text(), prompt.attachments(), false);
            case FollowUpSubmitted followUp -> submission(followUp.text(), followUp.attachments(), false);
            case SteerSubmitted steer -> submission(steer.text(), steer.attachments(), true);
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
    private void submission(String text, List<AttachmentRef> attachments, boolean steer) {
        String id = TranscriptItem.id(envelope.seq(), 0);
        RunId fresh = RunId.of(session, envelope.seq());
        SessionState current = state.sessions().get(session);
        if (state.runs().containsKey(fresh)) {
            startedBySubmission = fresh;
            put(new UserMessage(id, session, fresh, at, text, attachments, false));
        } else if (steer) {
            put(new UserMessage(id, session, current == null ? null : current.activeRun(), at, text, attachments, false));
        } else {
            put(new UserMessage(id, session, null, at, text, attachments, true));
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
                case AttachmentBlock ignored -> { } // 模型的回复里没有附件，附件只在用户消息里
            }
        }
    }

    private Thinking thinking(String id, RunId run, ReasoningBlock reasoning) {
        return Reasoning.displayText(reasoning)
                .map(text -> new Thinking(id, session, run, at, text, false))
                .orElseGet(() -> new Thinking(id, session, run, at, "", true));
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
        if (step(finished.toolCallId()) instanceof ToolStep step && !step.isFinished()) {
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
        RunStatus existing = get(RunStatus.idOf(run)) instanceof RunStatus found ? found : null;
        RunStatus status = existing != null ? existing : RunStatus.started(session, run, at, phase);
        if (existing == null && !run.equals(startedBySubmission) && !conversation.queued.isEmpty()) {
            UserMessage queued = (UserMessage) get(conversation.queued.pollFirst());
            put(queued.started(run));
        }
        // 同一步里开始又结束的运行（如被拒绝的手动压缩）也要带上结束原因，否则控制台一直显示运行中。
        if (state.runs().get(run) instanceof RunState current && current.phase() instanceof Ended ended) {
            status = status.ended(ended.reason(), ended.detail(), at);
        } else if (existing != null) {
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
                    && !step.isFinished()) {
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
                        && !step.isFinished()) {
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
