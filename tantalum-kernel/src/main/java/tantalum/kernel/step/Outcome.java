package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.projection.UsageProjection;
import tantalum.kernel.table.StepContext;
import tantalum.kernel.tool.ToolSafety;
import tantalum.value.command.Command;
import tantalum.value.command.Confirm;
import tantalum.value.command.Send;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.decision.PhaseChanged;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.SessionMessage;
import tantalum.value.hook.HookContext;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.model.Message;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.Awaiting;
import tantalum.value.state.Inbox;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunPhase;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 一次 step 内累积的结果。只活在一次纯函数调用里，不会逃逸到外面。 */
final class Outcome {

    final EventEnvelope envelope;
    final HookChain hooks;
    final ToolSafety toolSafety;
    final int maxParallelTools;
    final int maxWorkRounds;
    private KernelState state;
    private final List<Command> commands = new ArrayList<>();
    private final List<Fact> facts = new ArrayList<>();
    private int nextCommand;

    Outcome(StepContext context) {
        this.state = context.state();
        this.envelope = context.envelope();
        this.hooks = context.hooks();
        this.toolSafety = context.toolSafety();
        this.maxParallelTools = context.maxParallelTools();
        this.maxWorkRounds = context.maxWorkRounds();
    }

    KernelState state() {
        return state;
    }

    CommandId nextCommandId() {
        return CommandIds.of(envelope.session(), envelope.seq(), nextCommand++);
    }

    void emit(Command command) {
        commands.add(command);
    }

    void report(List<Fact> reported) {
        facts.addAll(reported);
    }

    void put(RunState run) {
        state = state.with(run);
    }

    void put(SessionState session) {
        state = state.with(session);
    }

    /** 用户状态只有用量累计这一种写法，日期取事件时间。 */
    void addUsage(RunRef ref, TokenUsage usage) {
        state = UsageProjection.apply(state, ref.principal(), envelope.at(), usage);
    }

    /** 让运行进入新状态并写回，状态名变化时产生事实。 */
    RunState enter(RunState run, RunPhase phase) {
        RunState next = run.withPhase(phase);
        put(next);
        if (!phase.name().equals(run.phase().name())) {
            facts.add(new PhaseChanged(next.ref(), phase.name()));
        }
        return next;
    }

    /** 进入等待，{@link #enter} 已写回运行，调用方不必先 {@code put}。 */
    RunState await(RunState run, String resumeAt, Set<CommandId> awaited) {
        return enter(run, new Awaiting(awaited, resumeAt));
    }

    RunState run(RunRef ref) {
        return state.runs().get(ref.run());
    }

    SessionState session(RunRef ref) {
        return state.sessions().get(ref.session());
    }

    void append(RunRef ref, Message message) {
        put(session(ref).append(message));
    }

    HookContext hookContext(RunRef ref) {
        return HookContext.of(ref, envelope.at());
    }

    Decision decision() {
        mail();
        return new Decision(state, commands, facts);
    }

    /**
     * 会话之间的话不丢、不重：收到一句就回一个确认并记下编号；这一步送出去的每一句都记在这个会话的信箱里，等对方确认。
     * 放在这里而不是各个步骤里：哪一步送话、哪条路径收话都一样，不会漏。
     */
    private void mail() {
        SessionState session = state.sessions().get(envelope.session());
        Inbox before = session == null ? null : session.inbox();
        Inbox inbox = before;
        if (envelope.event() instanceof SessionMessage message && message.from().principal().equals(envelope.principal())) {
            RunRef here = new RunRef(envelope.principal(), envelope.session(), RunId.of(envelope.session(), envelope.seq()));
            commands.add(new Confirm(nextCommandId(), here, message.from().session(), message.id()));
            if (inbox != null) {
                inbox = inbox.saw(message.id());
            }
        }
        if (inbox != null) {
            for (Command command : commands) {
                if (command instanceof Send send) {
                    inbox = inbox.sent(send.to(), send.message());
                }
            }
            if (!inbox.equals(before)) {
                state = state.with(session.withInbox(inbox));
            }
        }
    }
}
