package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.hook.HookPoint;
import tantalum.kernel.projection.ContextDiff;
import tantalum.kernel.projection.PromptProjection;
import tantalum.kernel.projection.UsageProjection;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.decision.Decision;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.Awaiting;
import tantalum.value.state.Compacting;
import tantalum.value.state.EndReason;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;
import tantalum.value.state.UsageAnchor;

/** 提交、组装上下文和模型调用的处理函数。 */
public final class ModelSteps {

    static final String AWAITING_CONTEXT = "AwaitingContext";
    static final String AWAITING_MODEL = "AwaitingModel";
    /** COMPLETED 的详情：回复因输出上限被截断。 */
    public static final String TRUNCATED = "reply truncated";
    /** 所有上下文更新消息的开头，压缩时靠它认出这不是用户写的。 */
    public static final String CONTEXT_UPDATE_PREFIX = "[Context update, not written by the user.";
    /** 只发变化的更新消息：这些内容叠加在之前给过的之上。 */
    static final String CONTEXT_CHANGES = CONTEXT_UPDATE_PREFIX + " These changes apply on top of what you were given earlier.]\n\n";
    public static final String CONTEXT_UPDATE ="[Context update, not written by the user. This replaces the shared knowledge, "
            + "project instructions and memory index given earlier.]\n\n";

    private ModelSteps() {}

    public static Decision userPrompt(StepContext context) {
        UserPrompt event = (UserPrompt) context.envelope().event();
        return submit(context, event.owner(), event.text());
    }

    public static Decision followUp(StepContext context) {
        FollowUpSubmitted event = (FollowUpSubmitted) context.envelope().event();
        return submit(context, new OwnerRef.OfSession(context.envelope().session()), event.text());
    }

    private static Decision submit(StepContext context, OwnerRef owner, String text) {
        Outcome out = new Outcome(context);
        if (context.run() == null || context.run().ended()) {
            startRun(out, owner, text);
        } else {
            SessionState session = out.session(context.run().ref());
            List<String> queued = new ArrayList<>(session.followUps());
            queued.add(text);
            out.state = out.state.with(session.withFollowUps(queued));
        }
        return out.decision();
    }

    public static Decision steer(StepContext context) {
        SteerSubmitted event = (SteerSubmitted) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        if (run == null || run.ended()) {
            startRun(out, new OwnerRef.OfSession(context.envelope().session()), event.text());
        } else {
            List<Message> steers = new ArrayList<>(run.pendingSteers());
            steers.add(Message.user(event.text()));
            out.put(run.withPendingSteers(steers));
        }
        return out.decision();
    }

    /** 新运行：追加用户消息，请求组装上下文。运行 ID 由事件序号算出，回放时相同。 */
    static void startRun(Outcome out, OwnerRef owner, String text) {
        EventEnvelope envelope = out.envelope;
        RunRef ref = new RunRef(envelope.principal(), envelope.session(), RunId.of(envelope.session(), envelope.seq()));
        SessionState session = out.state.sessions().get(envelope.session());
        if (session == null) {
            session = SessionState.start(envelope.session(), envelope.principal());
        }
        out.state = out.state.with(session.append(Message.user(text)).withActiveRun(ref.run()));

        CommandId id = out.nextCommandId();
        out.emit(new AssembleContext(id, ref));
        RunState run = RunState.start(ref, owner);
        out.put(run);
        out.await(run, AWAITING_CONTEXT, Set.of(id));
    }

    public static Decision contextAssembled(StepContext context) {
        ContextAssembled event = (ContextAssembled) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        // 压缩刚把上下文清空：这次组装之后的调用不再判断（规则 3.1）。
        SessionState before = out.session(run.ref());
        boolean justCompacted = before.context() == null && before.compaction() != null;
        SessionContext frozen = freezeContext(out, run, event);
        run = run.withContext(frozen.system(), frozen.tools());
        out.put(run);
        callModel(out, run, null, !justCompacted);
        return out.decision();
    }

    /**
     * 所有模型相同：会话的 system 和工具固定为第一次组装的结果，历史只追加。
     * 之后可变内容（记忆索引等）有变化时，追加一条用户消息告诉模型，新工具要等新会话。
     */
    private static SessionContext freezeContext(Outcome out, RunState run, ContextAssembled event) {
        SessionState session = out.session(run.ref());
        SessionContext frozen = session.context();
        if (frozen == null) {
            frozen = new SessionContext(event.system(), event.tools(), event.dynamic(), event.compaction(), event.sections());
            out.state = out.state.with(session.withContext(frozen));
            return frozen;
        }
        if (event.sections() != null && frozen.sections() != null) {
            String changes = ContextDiff.of(frozen.sections(), event.sections());
            if (!changes.isEmpty()) {
                frozen = frozen.withDynamic(event.dynamic(), event.sections());
                out.state = out.state.with(session.append(Message.user(CONTEXT_CHANGES + changes)).withContext(frozen));
            }
            return frozen;
        }
        if (event.dynamic() != null && !event.dynamic().equals(frozen.dynamic())) {
            String body = event.dynamic().isEmpty() ? "(now empty)" : event.dynamic();
            frozen = frozen.withDynamic(event.dynamic(), event.sections());
            out.state = out.state.with(session.append(Message.user(CONTEXT_UPDATE + body)).withContext(frozen));
        }
        return frozen;
    }

    /** 压缩后重新组装 system 和工具（规则 5），新的记忆索引和 MCP 工具在此生效。 */
    static void reassemble(Outcome out, RunState run) {
        CommandId id = out.nextCommandId();
        out.emit(new AssembleContext(id, run.ref()));
        out.await(run, AWAITING_CONTEXT, Set.of(id));
    }

    static void callModel(Outcome out, RunState pending, WorkDone work) {
        callModel(out, pending, work, true);
    }

    /**
     * 调用模型前经过 {@code beforeModelCall}；待处理的 {@code steer} 在这里并入消息。
     * 放行后先看要不要自动压缩（规则 2）；{@code compactionAllowed} 为 false 是压缩刚结束后的那次调用，不再判断（规则 3.1）。
     */
    static void callModel(Outcome out, RunState pending, WorkDone work, boolean compactionAllowed) {
        RunState run = pending;
        for (Message steer : run.pendingSteers()) {
            out.append(run.ref(), steer);
        }
        run = run.withPendingSteers(List.of());
        out.put(run);

        HookOutcome hook = out.hooks.run(HookPoint.BEFORE_MODEL_CALL, out.state,
                out.hookContext(run.ref()).withWork(work));
        out.report(hook.facts());
        switch (hook.result()) {
            case Stop stop -> RunEnd.finish(out, run, EndReason.STOPPED, stop.reason());
            case NeedWork need -> WorkSteps.start(out, run, need, HookPoint.BEFORE_MODEL_CALL);
            case Allow allow -> {
                if (compactionAllowed) {
                    run = CompactSteps.tryStart(out, run, allow.patch(), Compacting.Trigger.AUTO, null, null);
                    if (run == null) {
                        return;
                    }
                }
                Prompt prompt = PromptProjection.project(run, out.session(run.ref()), allow.patch());
                CommandId id = out.nextCommandId();
                out.emit(new CallModel(id, run.ref(), prompt));
                out.await(run, AWAITING_MODEL, Set.of(id));
            }
        }
    }

    public static Decision modelReplied(StepContext context) {
        ModelReplied event = (ModelReplied) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run().withRetries(0).withOverflowRecovered(false);
        out.put(run);
        out.append(run.ref(), new Message(Role.ASSISTANT, event.content(), false));
        out.state = UsageProjection.apply(out.state, run.ref().principal(), context.envelope().at(), event.usage());
        anchor(out, run, event.usage());

        List<ToolCallBlock> calls = event.content().stream()
                .filter(ToolCallBlock.class::isInstance).map(ToolCallBlock.class::cast).toList();
        if (calls.isEmpty()) {
            // 回复因输出上限被截断：运行照常完成，但带标记，界面据此提示（决定 D5）。
            RunEnd.beforeEnd(out, run, null, event.stop() == StopReason.MAX_TOKENS ? TRUNCATED : null);
        } else {
            ToolSteps.start(out, run, calls);
        }
        return out.decision();
    }

    /** 被中断的回复。用户已看到的文本只在该运行仍是历史末尾时追加（会话已开新运行就只留在 journal）；
     * 中断的是运行正在等待的那次调用而运行还没结束（不是 {@code Cancel} 引起），运行以 CANCELLED 结束。 */
    public static Decision modelInterrupted(StepContext context) {
        ModelInterrupted event = (ModelInterrupted) context.envelope().event();
        RunState run = context.run();
        SessionState session = context.state().sessions().get(context.envelope().session());
        if (run == null || session == null) {
            return Decision.unchanged(context.state());
        }
        boolean awaited = run.phase() instanceof Awaiting awaiting && awaiting.commands().contains(event.command());
        boolean historyOpen = awaited || (run.ended() && session.isLatestRun(run.ref().run()));
        if (!historyOpen) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        // 被中断的是摘要调用时，半截摘要不是回复，不进历史。
        if (!event.text().isEmpty() && run.compacting() == null) {
            out.append(run.ref(), new Message(Role.ASSISTANT, List.of(new TextBlock(event.text())), true));
        }
        if (awaited) {
            RunEnd.finish(out, run.withCompacting(null), EndReason.CANCELLED, "model call interrupted");
        }
        return out.decision();
    }

    /** 规则 1 的锚点：有实测用量就记下 prompt 加输出和此刻的消息数，没有就清空，之后全部估算。 */
    private static void anchor(Outcome out, RunState run, TokenUsage usage) {
        SessionState session = out.session(run.ref());
        UsageAnchor anchor = usage == null ? null
                : new UsageAnchor(usage.promptTotal() + usage.output(), session.messages().size());
        out.state = out.state.with(session.withAnchor(anchor));
    }
}
