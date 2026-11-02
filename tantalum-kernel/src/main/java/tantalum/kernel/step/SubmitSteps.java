package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.AssembleContext;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.model.Message;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 提交的处理函数：空闲时开新运行，运行中排队或插话。 */
public final class SubmitSteps {

    private SubmitSteps() {}

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
        if (context.run() == null || context.run().isEnded()) {
            startRun(out, owner, text);
        } else {
            SessionState session = out.session(context.run().ref());
            List<String> queued = new ArrayList<>(session.followUps());
            queued.add(text);
            out.put(session.withFollowUps(queued));
        }
        return out.decision();
    }

    public static Decision steer(StepContext context) {
        SteerSubmitted event = (SteerSubmitted) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        if (run == null || run.isEnded()) {
            startRun(out, new OwnerRef.OfSession(context.envelope().session()), event.text());
        } else {
            List<Message> steers = new ArrayList<>(run.pendingSteers());
            steers.add(Message.user(event.text()));
            out.put(run.withPendingSteers(steers));
        }
        return out.decision();
    }

    /** 新运行：追加用户消息，请求组装上下文。 */
    static void startRun(Outcome out, OwnerRef owner, String text) {
        EventEnvelope envelope = out.envelope;
        SessionState session = out.state().sessions().get(envelope.session());
        if (session == null) {
            session = SessionState.start(envelope.session(), envelope.principal());
        }
        RunState run = newRun(out, session.append(Message.user(text)), owner);
        CommandId id = out.nextCommandId();
        out.emit(new AssembleContext(id, run.ref()));
        out.await(run, ContextSteps.AWAITING_CONTEXT, Set.of(id));
    }

    /** 在会话上登记一个新运行并写回两者。运行 ID 由事件序号算出，回放时相同。 */
    static RunState newRun(Outcome out, SessionState session, OwnerRef owner) {
        EventEnvelope envelope = out.envelope;
        RunRef ref = new RunRef(envelope.principal(), envelope.session(), RunId.of(envelope.session(), envelope.seq()));
        out.put(session.startRun(ref.run()));
        RunState run = RunState.start(ref, owner);
        out.put(run);
        return run;
    }
}
