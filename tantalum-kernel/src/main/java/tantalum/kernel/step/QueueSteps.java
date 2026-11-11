package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import tantalum.kernel.table.StepContext;
import tantalum.value.decision.Decision;
import tantalum.value.decision.QueueEdited;
import tantalum.value.event.QueueChanged;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.SkillBlock;
import tantalum.value.state.Queue;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 改排队的提问：换文字、移除、转成插话。 */
public final class QueueSteps {

    private QueueSteps() {}

    /**
     * 找到了就改，没找到什么都不动；两种情况都报一条 {@link QueueEdited}。没有活动运行时队列一定是空的
     * （运行结束时立刻取下一条），不报事实。带附件的、带点名技能内容的提问不能改文字：
     * 改了文字，附上的内容就对不上了。移除和转成插话照常，技能内容跟着整条消息走。
     */
    public static Decision changed(StepContext context) {
        QueueChanged event = (QueueChanged) context.envelope().event();
        RunState run = context.run();
        if (run == null || run.isEnded()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        SessionState session = out.session(run.ref());
        int at = Queue.locate(session.followUps(), event.index(), Message.user(event.expected(), event.attachments()));
        if (event.action() == QueueChanged.Action.EDIT && (!event.attachments().isEmpty()
                || at >= 0 && session.followUps().get(at).content().stream().anyMatch(SkillBlock.class::isInstance))) {
            at = -1;
        }
        if (at >= 0) {
            List<Message> queued = new ArrayList<>(session.followUps());
            List<ModelChoice> models = new ArrayList<>(session.followUpModels());
            switch (event.action()) {
                case EDIT -> queued.set(at, Message.user(event.text()));
                case REMOVE -> {
                    queued.remove(at);
                    models.remove(at);
                }
                case STEER -> {
                    List<Message> steers = new ArrayList<>(run.pendingSteers());
                    steers.add(queued.remove(at));
                    models.remove(at);
                    out.put(run.withPendingSteers(steers));
                }
            }
            out.put(session.withFollowUps(queued, models));
        }
        out.report(List.of(new QueueEdited(run.ref(), event.action(), at)));
        return out.decision();
    }
}
