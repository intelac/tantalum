package tantalum.kernel.step;

import tantalum.kernel.table.StepContext;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.SessionCreated;
import tantalum.value.state.SessionState;

/** 建会话。种类、帮手的出身、目录、是不是不进记忆在这一步定下，之后不变。 */
public final class SessionSteps {

    private SessionSteps() {}

    /** 会话已经存在：重复送达，或者这个编号已经是别人的，都不动。 */
    public static Decision created(StepContext context) {
        SessionCreated event = (SessionCreated) context.envelope().event();
        EventEnvelope envelope = context.envelope();
        if (context.state().sessions().containsKey(envelope.session())) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        out.put(SessionState.start(envelope.session(), envelope.principal(), event.kind(), event.helper(), event.dir(), event.offRecord()));
        return out.decision();
    }
}
