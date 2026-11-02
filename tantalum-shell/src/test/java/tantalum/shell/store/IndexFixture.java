package tantalum.shell.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFiles;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.decision.Decision;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;

/** 用真实内核走事件，写真实文件，再把变化交给可替换的监听器。和线上 cleanroom 的顺序相同：先落盘，再处理。 */
final class IndexFixture {

    interface Step {
        void onStep(EventEnvelope envelope, KernelState before, KernelState after);
    }

    final SessionFiles files;
    final Kernel kernel;
    final List<EventEnvelope> events = new ArrayList<>();
    private final Step listener;
    private final Map<Principal, Long> sequences = new HashMap<>();
    private KernelState state = KernelState.empty();
    private int requests;

    IndexFixture(SessionPaths paths, SessionFiles.DurableListener durable, Step listener) {
        this(paths, durable, listener, kernel());
    }

    IndexFixture(SessionPaths paths, SessionFiles.DurableListener durable, Step listener, Kernel kernel) {
        this.kernel = kernel;
        this.files = new SessionFiles(paths, EventCodec.withBuiltins(), 16, durable);
        this.listener = listener;
    }

    static Kernel kernel() {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        return new Kernel(table, new HookChain(List.of()), (tool, args) -> false, 10);
    }

    Decision send(Principal principal, SessionId session, RunId run, Instant at, Event event) throws Exception {
        long seq = sequences.merge(principal, 1L, Long::sum);
        EventEnvelope envelope = new EventEnvelope(seq, at, principal, session, run, event);
        files.append(principal, session, List.of(envelope));
        events.add(envelope);
        KernelState before = state;
        Decision decision = kernel.step(before, envelope);
        state = decision.newState();
        listener.onStep(envelope, before, state);
        return decision;
    }

    /** 提交并走到等模型回复，返回 CallModel 的命令 ID。 */
    Pending prompt(Principal principal, SessionId session, Instant at, String text) throws Exception {
        String request = "req-" + requests++;
        Decision submitted = send(principal, session, null, at,
                new UserPrompt(new SubmissionId("sub-" + request), new RequestId(request), new OwnerRef.OfSession(session), text));
        AssembleContext assemble = (AssembleContext) submitted.commands().getFirst();
        RunId run = assemble.run().run();
        Decision assembled = send(principal, session, run, at, new ContextAssembled(assemble.id(), "s", List.of(), null, List.of()));
        CallModel call = (CallModel) assembled.commands().getFirst();
        return new Pending(principal, session, run, call.id());
    }

    void reply(Pending pending, Instant at, TokenUsage usage) throws Exception {
        send(pending.principal, pending.session, pending.run, at,
                new ModelReplied(pending.command, List.of(new TextBlock("ok")), StopReason.END_TURN, "m", usage));
    }

    KernelState state() {
        return state;
    }

    record Pending(Principal principal, SessionId session, RunId run, CommandId command) {}
}
