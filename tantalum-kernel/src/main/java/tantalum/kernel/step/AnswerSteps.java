package tantalum.kernel.step;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.Decision;
import tantalum.value.decision.HookReported;
import tantalum.value.decision.QuestionAsked;
import tantalum.value.decision.QuestionSettled;
import tantalum.value.event.Answered;
import tantalum.value.event.TimerFired;
import tantalum.value.hook.Allow;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.question.Answer;
import tantalum.value.question.AskUser;
import tantalum.value.question.Question;
import tantalum.value.state.AwaitingAnswer;
import tantalum.value.state.RunState;

/**
 * 等用户回答。钩子返回 {@code Ask}、或模型调用 {@code ask_user}，运行就停在 {@link AwaitingAnswer}：没有在途命令，
 * 不计工作轮数，不超时。回答（{@link Answered}）或期限到（{@link TimerFired}）后结算：钩子的问题调 {@code onAnswer} 再回到原处
 * （{@link Resume}），{@code ask_user} 的问题把回答写成工具结果。问题和回答都在这一步的结果里，回放不问任何人。
 */
public final class AnswerSteps {

    public static final String AWAITING_ANSWER = AwaitingAnswer.NAME;
    /** 忽略回答时事实里的“钩子名”。 */
    static final String KERNEL = "kernel";

    private AnswerSteps() {}

    /** 钩子 {@code hook} 在 {@code origin} 问用户。 */
    static void ask(Outcome out, RunState run, Ask ask, String hook, HookPoint origin) {
        enter(out, run, ask.question(), ask.deadline(), origin, null, hook);
    }

    /** 模型调用 {@code ask_user}（工具调用 {@code toolCallId}）问用户。 */
    static void askFromTool(Outcome out, RunState run, Question question, Duration timeout, String toolCallId) {
        enter(out, run, question, timeout, null, toolCallId, AskUser.NAME);
    }

    private static void enter(Outcome out, RunState run, Question question, Duration timeout, HookPoint origin, String toolCallId,
                              String askedBy) {
        Question asked = question;
        CommandId timer = null;
        if (timeout != null) {
            // 期限记成绝对时刻（用事件的时间，内核不读时钟），定时器照旧是命令
            asked = question.withDeadline(out.envelope.at().plus(timeout));
            timer = out.nextCommandId();
            out.emit(new StartTimer(timer, run.ref(), timeout));
        }
        out.report(List.of(new QuestionAsked(run.ref(), askedBy, asked)));
        out.enter(run, new AwaitingAnswer(asked, origin, toolCallId, timer));
    }

    /** 等人时收到回答：是当前问题、回答合法才结算，否则记一条事实、状态不变。 */
    public static Decision answered(StepContext context) {
        Answered event = (Answered) context.envelope().event();
        RunState run = context.run();
        Outcome out = new Outcome(context);
        if (!(run.phase() instanceof AwaitingAnswer waiting)) {
            return ignored(out, run, event, "no question is waiting");
        }
        if (!waiting.question().id().equals(event.question())) {
            return ignored(out, run, event, "not the current question " + waiting.question().id());
        }
        if (!waiting.question().accepts(event.answer())) {
            return ignored(out, run, event, "answer not accepted by the question");
        }
        settle(out, run, waiting, event.answer(), QuestionSettled.Reason.ANSWERED);
        return out.decision();
    }

    /** 不在等人的运行收到回答（晚到、重复）：记一条事实，什么都不变。 */
    public static Decision ignored(StepContext context) {
        RunState run = context.run();
        if (run == null) {
            return Decision.unchanged(context.state());
        }
        return ignored(new Outcome(context), run, (Answered) context.envelope().event(), "run is " + run.phase().name());
    }

    private static Decision ignored(Outcome out, RunState run, Answered event, String why) {
        out.report(List.of(new HookReported(run.ref(), KERNEL, "answer to " + event.question() + " ignored: " + why)));
        return out.decision();
    }

    /** 期限到：是这个问题的定时器才按“没有回答”结算；别的定时器（上一个问题的）忽略。 */
    public static Decision expired(StepContext context) {
        TimerFired event = (TimerFired) context.envelope().event();
        RunState run = context.run();
        if (!(run.phase() instanceof AwaitingAnswer waiting) || !event.command().equals(waiting.timer())) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        settle(out, run, waiting, null, QuestionSettled.Reason.EXPIRED);
        return out.decision();
    }

    /** 取消在等人的运行时记下问题的结局并中止定时器；运行本身由调用方结束。 */
    static void cancelled(Outcome out, RunState run, AwaitingAnswer waiting) {
        out.report(List.of(new QuestionSettled(run.ref(), waiting.question().id(), null, QuestionSettled.Reason.CANCELLED)));
        abortTimer(out, run, waiting);
    }

    private static void settle(Outcome out, RunState run, AwaitingAnswer waiting, Answer answer, QuestionSettled.Reason reason) {
        Question question = waiting.question();
        out.report(List.of(new QuestionSettled(run.ref(), question.id(), answer, reason)));
        if (reason != QuestionSettled.Reason.EXPIRED) {
            abortTimer(out, run, waiting);
        }
        if (waiting.fromTool()) {
            ToolSteps.answerSlot(out, run, waiting.toolCallId(), answer == null ? AskUser.TIMEOUT : AskUser.result(answer));
            return;
        }
        HookPoint origin = waiting.origin();
        HookContext context = out.hookContext(run.ref()).withQuestion(question, answer);
        if (origin == HookPoint.BEFORE_TOOL_CALL && run.tools() != null && run.tools().checked() < run.tools().slots().size()) {
            // 工具调用前问的：回答时把那个调用一起给钩子（审批插件按工具名记“这个会话里一直允许”）
            context = context.withCall(run.tools().slots().get(run.tools().checked()).call());
        }
        HookOutcome hook = out.hooks.run(HookPoint.ON_ANSWER, out.state(), context);
        out.report(hook.facts());
        switch (hook.result()) {
            case NeedWork need -> WorkSteps.start(out, run, need, hook.decidedBy(), origin);
            case Ask again -> ask(out, run, again, hook.decidedBy(), origin);
            case Allow allow -> {
                for (Allow.SessionNote note : allow.notes()) {
                    out.put(out.session(run.ref()).withNote(note.key(), note.value()));
                }
                Resume.at(out, run, origin, null, null);
            }
            case Stop stop -> Resume.at(out, run, origin, null, stop.reason());
        }
    }

    private static void abortTimer(Outcome out, RunState run, AwaitingAnswer waiting) {
        if (waiting.timer() != null) {
            out.emit(new AbortCommands(out.nextCommandId(), run.ref(), Set.of(waiting.timer())));
        }
    }
}
