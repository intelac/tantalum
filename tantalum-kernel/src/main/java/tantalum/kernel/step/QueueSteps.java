package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import tantalum.kernel.table.StepContext;
import tantalum.value.decision.Decision;
import tantalum.value.decision.QueueEdited;
import tantalum.value.event.QueueChanged;
import tantalum.value.model.Message;
import tantalum.value.model.SkillBlock;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.event.Input;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.state.InboxItem;

/** 改收件队列里你自己排的一条。 */
public final class QueueSteps {

    private QueueSteps() {}

    /**
     * 按编号找：找到了就改，没找到什么都不动；两种情况都报一条 {@link QueueEdited}。只能动你自己排的、还没并进哪一轮的那几条。
     * 没有活动运行时队列一定是空的（运行结束时立刻取下一条），不报事实。带附件的、带点名技能内容的不能改文字：
     * 改了文字，附上的内容就对不上了。拿掉和改成并进这一轮照常，技能内容跟着整条消息走。
     */
    public static Decision changed(StepContext context) {
        QueueChanged event = (QueueChanged) context.envelope().event();
        RunState run = context.run();
        if (run == null || run.isEnded()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        SessionState session = out.session(run.ref());
        List<InboxItem> inbox = new ArrayList<>(session.inbox());
        int at = -1;
        for (int i = 0; i < inbox.size(); i++) {
            InboxItem item = inbox.get(i);
            if (item.id() == event.item() && item.kind() == Input.Kind.PROMPT && !item.join()) {
                at = i;
            }
        }
        if (at >= 0 && event.action() == QueueChanged.Action.EDIT && inbox.get(at).message().content().stream()
                .anyMatch(block -> block instanceof AttachmentBlock || block instanceof SkillBlock)) {
            at = -1;
        }
        if (at >= 0) {
            InboxItem item = inbox.get(at);
            switch (event.action()) {
                case EDIT -> inbox.set(at, item.withMessage(Message.user(event.text())));
                case REMOVE -> inbox.remove(at);
                case JOIN -> {
                    // 并进去的不带模型选择，排到队尾：并入的先后照“改成并入”的先后，和直接插话的一样
                    inbox.remove(at);
                    inbox.add(new InboxItem(item.id(), item.kind(), item.root(), item.message(), null, true, null));
                }
            }
            out.put(session.withInbox(inbox));
        }
        out.report(List.of(new QueueEdited(run.ref(), event.action(), at >= 0 ? event.item() : -1)));
        return out.decision();
    }
}
