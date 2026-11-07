package tantalum.shell.trace;

import java.time.Instant;
import java.util.List;
import tantalum.shell.trace.TracePass.Place;
import tantalum.shell.trace.TraceRecord.HookDecision;
import tantalum.shell.trace.TraceRecord.HookDecision.Applied;
import tantalum.shell.trace.TraceRecord.PluginWork;
import tantalum.shell.trace.TraceRecord.PluginWork.After;
import tantalum.shell.trace.TraceRecord.PluginWork.Status;
import tantalum.shell.trace.TraceRecord.Source;
import tantalum.shell.trace.TraceRecord.UserInput.Mode;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.decision.Fact;
import tantalum.value.decision.HookDecided;
import tantalum.value.state.AwaitingAnswer;
import tantalum.value.decision.QuestionSettled;
import tantalum.value.decision.QuestionAsked;
import tantalum.value.decision.HookReported;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.RunId;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.state.RunState;
import tantalum.value.state.ToolRound;

/** 钩子决定和插件工作，以及两者、钩子和工具之间的关联。 */
final class TraceHooks {

    private final TracePass pass;

    TraceHooks(TracePass pass) {
        this.pass = pass;
    }

    /**
     * 钩子记录归哪一组：模型调用前的归下一步，压缩前的归压缩组，工作结果后的跟着发起工作的位置，其余归当前步。
     */
    private Place place(RunId run, HookPoint point) {
        return switch (point) {
            case BEFORE_MODEL_CALL -> pass.pending(run);
            case BEFORE_COMPACT -> pass.compaction(run);
            case ON_WORK_RESULT -> origin(run) == HookPoint.BEFORE_MODEL_CALL ? pass.pending(run) : pass.current(run);
            default -> pass.current(run);
        };
    }

    /** 处理前运行在等的工作是哪个位置发起的。 */
    private HookPoint origin(RunId run) {
        return pass.before.runs().get(run) instanceof RunState earlier ? earlier.workOrigin() : null;
    }

    /** 补丁只在模型调用前生效：这个决定发出了模型调用就是用上了（摘要调用也用同一个补丁），否则未采用。 */
    /** 运行停下来问用户（waiting-state）：一条记录，等人的这段从问到答。归发起位置所在的步；ask_user 归工具调用那一步。 */
    void asked(int index, QuestionAsked fact) {
        RunId run = fact.run().run();
        HookPoint point = pass.state.runs().get(run) instanceof RunState current && current.phase() instanceof AwaitingAnswer waiting
                && waiting.origin() != null ? waiting.origin() : HookPoint.BEFORE_TOOL_CALL;
        Place place = place(run, point);
        pass.add(new TraceRecord.Question(pass.head("q:" + fact.question().id(), run, place, pass.at), fact.question().id(), fact.askedBy(),
                fact.question().title().wire(), TraceRecord.Question.Status.WAITING, null, null), place, true);
    }

    void settled(QuestionSettled fact) {
        if (!(pass.book.open.get("q:" + fact.question()) instanceof TraceRecord.Question open)) {
            return;
        }
        TraceRecord.Question.Status status = switch (fact.reason()) {
            case ANSWERED -> TraceRecord.Question.Status.ANSWERED;
            case EXPIRED -> TraceRecord.Question.Status.EXPIRED;
            case CANCELLED -> TraceRecord.Question.Status.CANCELLED;
        };
        pass.put(new TraceRecord.Question(open.head().ended(pass.at), open.question(), open.askedBy(), open.title(), status,
                fact.answer() == null ? null : fact.answer().choice(), fact.answer() == null ? null : fact.answer().text()), false);
    }

    void decided(int index, HookDecided fact) {
        RunId run = fact.run().run();
        Applied applied = null;
        if (fact.kind() == HookDecided.Kind.PATCH) {
            applied = fact.point() != HookPoint.BEFORE_MODEL_CALL ? Applied.INEFFECTIVE
                    : called(run) ? Applied.APPLIED : Applied.NOT_ADOPTED;
        }
        String tool = fact.toolCall() != null ? pass.tools.idOf(fact.toolCall()) : null;
        if (tool == null && fact.point() == HookPoint.ON_WORK_RESULT && origin(run) == HookPoint.BEFORE_TOOL_CALL) {
            tool = pass.tools.idOf(waitingCall(run));
        }
        String work = null;
        if (fact.kind() == HookDecided.Kind.NEED_WORK && index == decisive(run)) {
            work = doWork(run) instanceof DoWork asked ? "w:" + asked.id().value() : null;
        } else if (fact.point() == HookPoint.ON_WORK_RESULT && pass.envelope.event() instanceof WorkDone done) {
            work = "w:" + done.command().value();
        }
        Place place = place(run, fact.point());
        pass.add(new HookDecision(pass.head("h:" + pass.seq + "." + index, run, place, pass.at).ended(pass.at), fact.hook(), fact.point(),
                HookDecision.of(fact.kind()), fact.reason(), fact.systemChars(), fact.tools(), fact.workType(), applied, tool, work),
                place, false);
    }

    /**
     * 钩子抛了异常。事实里没有位置：链因此拦截时紧跟着一条同名钩子的 STOP，位置取它；否则不知道位置，
     * 这个决定发出了正常调用就归下一步（多半是模型调用前），否则归当前步。
     */
    void reported(int index, HookReported fact, Fact next) {
        RunId run = fact.run().run();
        HookPoint point = next instanceof HookDecided decided && decided.kind() == HookDecided.Kind.STOP
                && decided.hook().equals(fact.hook()) && decided.run().equals(fact.run()) ? decided.point() : null;
        Place place = point != null ? place(run, point) : normalCall(run) ? pass.pending(run) : pass.current(run);
        pass.add(new HookDecision(pass.head("h:" + pass.seq + "." + index, run, place, pass.at).ended(pass.at), fact.hook(), point,
                HookDecision.Result.ERROR, fact.detail(), 0, List.of(), null, null, null, null), place, false);
    }

    /**
     * 发出的工作。内核在发 {@code DoWork} 的同一个决定里进入等待、不再跑钩子，所以这个决定里最后一条要求工作的事实就是发起它的钩子
     * （压缩前要工作按拒绝处理，不算）。完成后回到的位置是运行的 {@code workOrigin}。
     */
    void work(DoWork work) {
        RunId run = work.run().run();
        int index = decisive(run);
        HookDecided asked = index < 0 ? null : (HookDecided) pass.decision.facts().get(index);
        HookPoint resumeAt = pass.state.runs().get(run) instanceof RunState current ? current.workOrigin() : null;
        Place place = resumeAt == HookPoint.BEFORE_MODEL_CALL ? pass.pending(run) : pass.current(run);
        pass.add(new PluginWork(pass.head("w:" + work.id().value(), run, place, pass.at), work.id().value(), work.workType(),
                asked == null ? null : asked.hook(), asked == null ? null : asked.point(), index < 0 ? null : "h:" + pass.seq + "." + index,
                resumeAt, work.input(), Status.RUNNING, null, null, null, null), place, true);
    }

    /** 工作完成。带续写文字且内核把它追加给了模型时，另记一条插件续写的用户记录。 */
    void workDone(WorkDone done) {
        if (!pass.awaited(done) || !(pass.book.open.get("w:" + done.command().value()) instanceof PluginWork work)) {
            return;
        }
        RunId run = work.head().run();
        boolean ended = pass.state.runs().get(run) instanceof RunState current && current.isEnded();
        pass.put(with(work, done.failed() ? Status.FAILED : Status.DONE, done.result(), done.continueText(),
                ended ? After.END : After.CONTINUE, done.startedAt()).withHead(work.head().ended(pass.at)), false);
        if (done.continueText() != null && pass.appended().contains(Message.user(done.continueText()))) {
            pass.user(run, Mode.CONTINUE, done.continueText(), work.hook() == null ? Source.UNKNOWN : Source.plugin(work.hook()),
                    List.of());
        }
    }

    /** 运行在工作途中结束（取消、重启）。 */
    void runEnded(RunId run) {
        for (TraceRecord record : List.copyOf(pass.book.open.values())) {
            if (record instanceof PluginWork work && work.head().run().equals(run) && work.status() == Status.RUNNING) {
                pass.put(with(work, Status.INTERRUPTED, null, null, After.END, null).withHead(work.head().ended(pass.at)), false);
            }
        }
    }

    private static PluginWork with(PluginWork work, Status status, Json result, String continueText, After after,
                                   Instant startedAt) {
        return new PluginWork(work.head(), work.command(), work.workType(), work.hook(), work.point(), work.decision(), work.resumeAt(),
                work.input(), status, result, continueText, after, startedAt);
    }

    /** 这个决定里发起工作的那条事实的下标；没有发工作时为 -1。 */
    private int decisive(RunId run) {
        if (doWork(run) == null) {
            return -1;
        }
        List<Fact> facts = pass.decision.facts();
        for (int i = facts.size() - 1; i >= 0; i--) {
            if (facts.get(i) instanceof HookDecided decided && decided.kind() == HookDecided.Kind.NEED_WORK
                    && decided.point() != HookPoint.BEFORE_COMPACT && decided.run().run().equals(run)) {
                return i;
            }
        }
        return -1;
    }

    private DoWork doWork(RunId run) {
        for (Command command : pass.decision.commands()) {
            if (command instanceof DoWork work && work.run().run().equals(run)) {
                return work;
            }
        }
        return null;
    }

    private boolean called(RunId run) {
        return pass.decision.commands().stream().anyMatch(command -> command instanceof CallModel && command.run().run().equals(run));
    }

    private boolean normalCall(RunId run) {
        return called(run) && pass.state.runs().get(run) instanceof RunState current && current.compacting() == null;
    }

    /** 工具调用前发起的工作：正在等的是工具轮里第 {@code checked} 个调用。 */
    private String waitingCall(RunId run) {
        if (pass.before.runs().get(run) instanceof RunState earlier && earlier.tools() instanceof ToolRound round
                && round.checked() < round.slots().size()) {
            return round.slots().get(round.checked()).call().id();
        }
        return null;
    }
}
