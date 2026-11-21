package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tantalum.kernel.policy.InputPolicy;
import tantalum.kernel.table.StepContext;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.assignment.AssignmentTools;
import tantalum.value.assignment.Assignments;
import tantalum.value.assignment.OpenAssignment;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.Assign;
import tantalum.value.command.ReturnReport;
import tantalum.value.command.StartTimer;
import tantalum.value.command.StopAssignment;
import tantalum.value.decision.AssignmentStarted;
import tantalum.value.decision.Decision;
import tantalum.value.decision.ReportSettled;
import tantalum.value.event.AssignmentCancelled;
import tantalum.value.event.Input;
import tantalum.value.event.Report;
import tantalum.value.event.Root;
import tantalum.value.event.StopRequested;
import tantalum.value.event.TimerFired;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.id.SessionIds;
import tantalum.value.json.Json;
import tantalum.value.model.AssignmentBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ReportBlock;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.EndReason;
import tantalum.value.state.InboxItem;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;

/**
 * 交代：一个会话把一件事交给另一个会话去做，自己接着走；那边结束时结果作为一条输入（{@link Report}）回来。给模型和人的说法还是“任务”。
 *
 * <p>没有新的等待状态。{@code task} 当场有结果，交代它的那一轮不停；那件事做到哪了只在接它的会话自己那一轮的状态里。
 * 交代的会话只记“哪些还没回来”（{@link Assignments#open()}），名单里的每一条恰好对应一个生效的结果：
 * 那一轮结束（含被停、重启打断）、启动时的核对、设了期限时期限的定时器到，谁先到谁生效，后到的在名单里找不到，什么都不改。
 */
public final class AssignmentSteps {

    /** 交代的第一行最多留这么多字符，给人和模型认这是哪件事。 */
    static final int BRIEF_CHARS = 120;

    private AssignmentSteps() {}

    /**
     * 这一轮工具里的 {@code task}、{@code task_stop}：已经过了钩子，当场填上结果，不发 {@code RunTool}。
     * 名单是在这一步里改的，所以回放放出它的那条 {@code ModelReplied} 就能重新算出来。
     */
    static ToolRound settle(Outcome out, RunState run, ToolRound start) {
        ToolRound round = start;
        for (int i = 0; i < round.slots().size(); i++) {
            ToolSlot slot = round.slots().get(i);
            if (slot.status() != ToolSlot.Status.PENDING || !AssignmentTools.handles(slot.call().name())) {
                continue;
            }
            Result result = AssignmentTools.TASK.equals(slot.call().name()) ? start(out, run, slot.call(), i) : stop(out, run, slot.call());
            round = round.withSlot(i, slot.done(new ToolResultBlock(slot.call().id(), result.text(), result.error(), null)));
        }
        return round;
    }

    private record Result(String text, boolean error) {
        static Result refused(String reason) {
            return new Result(reason, true);
        }
    }

    private static Result start(Outcome out, RunState run, ToolCallBlock call, int index) {
        Map<String, Json> fields = call.arguments() instanceof Json.Obj obj ? obj.fields() : Map.of();
        String prompt = fields.get(AssignmentTools.PROMPT) instanceof Json.Str text ? text.value().strip() : "";
        if (prompt.isEmpty()) {
            return Result.refused("task needs a non-empty \"prompt\": everything the task has to know, it does not see this conversation.");
        }
        Json named = fields.get(AssignmentTools.AGENT);
        if (named != null && !(named instanceof Json.Null) && !(named instanceof Json.Str)) {
            return Result.refused("\"agent\" must be the name of an agent, or left out.");
        }
        String agent = named instanceof Json.Str name && !name.value().isBlank() ? name.value().strip() : null;
        SessionState session = out.session(run.ref());
        Assignments assignments = session.assignments();
        if (!InputPolicy.mayAssign(session.kind())) {
            return Result.refused("A task cannot start tasks. Do this work yourself.");
        }
        if (assignments.open().size() >= out.assignmentLimits.maxOpen()) {
            return Result.refused("Not started: " + assignments.open().size() + " tasks are already running, which is the limit."
                    + " Their results arrive on their own; start this one after one of them has ended, or do the work yourself.");
        }
        if (assignments.unattendedAssignments() >= out.assignmentLimits.maxUnattendedAssignments()) {
            return Result.refused("Not started: " + assignments.unattendedAssignments() + " tasks have been started since the user last spoke,"
                    + " which is the limit. Tell the user where things stand; the count starts over when they reply.");
        }
        String id = AssignmentTools.id(out.envelope.seq(), index);
        SessionId helper = SessionIds.helper(session.id(), id);
        // 期限默认没有；管理员设了才起定时器
        java.time.Duration limit = out.assignmentLimits.deadline();
        CommandId timer = limit == null ? null : out.nextCommandId();
        String brief = brief(prompt);
        Root root = run.cause().root();
        out.put(session.withAssignments(assignments.started(new OpenAssignment(id, helper, agent, brief, out.envelope.at(),
                limit == null ? null : out.envelope.at().plus(limit), timer, root))));
        if (timer != null) {
            out.emit(new StartTimer(timer, run.ref(), limit));
        }
        // 帮手用交代它的这一轮的模型（这一轮还没定下模型时用会话现在的选择）
        out.emit(new Assign(out.nextCommandId(), run.ref(), id, helper, agent, prompt, session.dir(), session.offRecord(),
                run.model() != null ? run.model() : session.model(), root));
        out.report(List.of(new AssignmentStarted(run.ref(), id, helper, agent, brief, call.id())));
        return new Result("Started task " + id + ". It runs on its own while you go on; its result arrives in this conversation"
                + " when it ends. Do not wait for it or ask about it.", false);
    }

    private static Result stop(Outcome out, RunState run, ToolCallBlock call) {
        Map<String, Json> fields = call.arguments() instanceof Json.Obj obj ? obj.fields() : Map.of();
        String id = fields.get(AssignmentTools.ID) instanceof Json.Str text ? text.value().strip() : "";
        Optional<OpenAssignment> open = out.session(run.ref()).assignments().find(id);
        if (open.isEmpty()) {
            return Result.refused("No running task \"" + id + "\".");
        }
        out.emit(new StopAssignment(out.nextCommandId(), run.ref(), id, open.get().session()));
        return new Result("Stop requested for task " + id + ". Its result arrives when it has stopped.", false);
    }

    static String brief(String prompt) {
        String first = prompt.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse("");
        return first.length() <= BRIEF_CHARS ? first : first.substring(0, BRIEF_CHARS) + "…";
    }

    /** 为一次交代开的那一轮结束了：在同一步里把结果交回去。{@code RunEnd.finish} 是运行唯一的出口，所以每次交代的这一轮恰好交一次。 */
    static void returned(Outcome out, RunState run, EndReason reason, String detail) {
        SessionState session = out.session(run.ref());
        String assignment = run.cause().owes();
        AssignmentOutcome outcome = outcomeOf(reason, detail);
        out.emit(new ReturnReport(out.nextCommandId(), run.ref(), session.helper().issuer(), assignment, outcome,
                textOf(outcome, session, assignment, detail), run.cause().root()));
    }

    /** 那一轮怎么结束，这次交代就是什么结局。启动时的核对补发结果时用同一张表。 */
    public static AssignmentOutcome outcomeOf(EndReason reason, String detail) {
        return switch (reason) {
            case COMPLETED -> AssignmentOutcome.COMPLETED;
            case FAILED, STOPPED -> AssignmentOutcome.FAILED;
            case CANCELLED -> CancelSteps.INTERRUPTED_BY_RESTART.equals(detail) ? AssignmentOutcome.INTERRUPTED : AssignmentOutcome.STOPPED;
        };
    }

    /** 交回的文字：完成时是为这次交代开的那一轮最后的回答，否则是结束的详情。 */
    public static String textOf(AssignmentOutcome outcome, SessionState session, String assignment, String detail) {
        return outcome == AssignmentOutcome.COMPLETED ? answers(session, assignment) : detail;
    }

    /**
     * 这次交代的回答：从交代那条消息往后，不带工具调用的助手消息里的文字，按先后接起来。通常只有一条；结束前的钩子让它再答一次时
     * （比如引用核对要它改一处）就有几条，后一条往往只是更正，只带它会把原来的结论丢掉（2026-11-18 真模型测试里发现）。
     * 边调工具边说的话是过程，不带回。在模型看得到的内容（{@link SessionState#view()}）里找：压缩过的话，交代那条可能已经在摘要里，
     * 那时从头收（摘要是用户消息，不收），压缩之前说的只在摘要里。
     */
    static String answers(SessionState session, String assignment) {
        List<Message> messages = session.view();
        int from = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).content().stream().anyMatch(block -> block instanceof AssignmentBlock given && given.assignment().equals(assignment))) {
                from = i + 1;
                break;
            }
        }
        StringBuilder all = new StringBuilder();
        for (Message message : messages.subList(from, messages.size())) {
            if (message.role() != Role.ASSISTANT || message.content().stream().anyMatch(ToolCallBlock.class::isInstance)) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (ContentBlock block : message.content()) {
                if (block instanceof TextBlock part) {
                    text.append(part.text());
                }
            }
            if (!text.toString().isBlank()) {
                all.append(all.isEmpty() ? "" : "\n\n").append(text.toString().strip());
            }
        }
        return all.isEmpty() ? null : all.toString();
    }

    /** 交代出去的事有了结果。名单里没有它：已经结束过了（重复、迟到的），不动，返回 false。 */
    static boolean reported(Outcome out, SessionState session, Report report) {
        Optional<OpenAssignment> open = session.assignments().find(report.assignment());
        if (open.isEmpty()) {
            return false;
        }
        RunRef ref = settle(out, session, open.get(), report.outcome(), report.text(), report.root());
        if (open.get().timer() != null) {
            // 期限的定时器不用再等了：让它停下。它要是已经到了，迟到的那一下在名单里找不到，不生效
            out.emit(new AbortCommands(out.nextCommandId(), ref, Set.of(open.get().timer())));
        }
        return true;
    }

    /**
     * 期限的定时器到了：交代还在名单里就按超时结束，并让它停下；它之后因为被停交回来的结果在名单里找不到，不再生效。
     * 别的没人等的定时器（迟到的重试、已经答过的问题）照旧什么都不改。
     */
    public static Decision timerFired(StepContext context) {
        TimerFired event = (TimerFired) context.envelope().event();
        SessionState session = context.state().sessions().get(context.envelope().session());
        Optional<OpenAssignment> open = session == null ? Optional.empty()
                : session.assignments().open().stream().filter(each -> event.command().equals(each.timer())).findFirst();
        if (open.isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        RunRef ref = settle(out, session, open.get(), AssignmentOutcome.TIMED_OUT, null, open.get().root());
        out.emit(new StopAssignment(out.nextCommandId(), ref, open.get().id(), open.get().session()));
        return out.decision();
    }

    /** 用户在网页上叫停：给名单里对应的交代各发一条停止。名单不动，等它们各自交回结果。 */
    public static Decision stopRequested(StepContext context) {
        StopRequested event = (StopRequested) context.envelope().event();
        SessionState session = context.state().sessions().get(context.envelope().session());
        if (session == null || session.lastRun() == null || !session.principal().equals(context.envelope().principal())) {
            return Decision.unchanged(context.state());
        }
        List<OpenAssignment> stopping = session.assignments().open().stream()
                .filter(each -> event.assignment() == null || each.id().equals(event.assignment())).toList();
        if (stopping.isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        RunRef ref = new RunRef(session.principal(), session.id(), session.lastRun());
        for (OpenAssignment each : stopping) {
            out.emit(new StopAssignment(out.nextCommandId(), ref, each.id(), each.session()));
        }
        return out.decision();
    }

    /** 交代的一方不要它了：正在跑的那一轮就是为它开的，才结束这一轮（像被撤销一样，结果照常交回）；否则不动。 */
    public static Decision cancelled(StepContext context) {
        AssignmentCancelled event = (AssignmentCancelled) context.envelope().event();
        RunState run = context.run();
        if (run == null || run.isEnded() || !event.assignment().equals(run.cause().owes())) {
            return Decision.unchanged(context.state());
        }
        return CancelSteps.cancel(context);
    }

    /**
     * 从名单去掉这次交代，把结果交给模型：正在跑一轮就并进这一轮，模型下次被调用时看到；空闲就为它开新的一轮，
     * 这是一轮不是人发起的运行，所以数着，到了上限只记进历史、不开运行，等人说话。返回看到结果的那一轮。
     * 结果的 {@code root} 不能比交代时的高。
     */
    private static RunRef settle(Outcome out, SessionState before, OpenAssignment open, AssignmentOutcome outcome, String text, Root reported) {
        SessionState session = before.withAssignments(before.assignments().ended(open.id()));
        Message result = new Message(Role.USER, List.of(new ReportBlock(open.id(), outcome, text)), false);
        Root root = Root.lower(open.root(), reported);
        RunRef ref;
        boolean paused = false;
        if (InboxSteps.busy(out, session)) {
            out.put(session.enqueue(new InboxItem(out.envelope.seq(), Input.Kind.REPORT, root, result, null, true, open.id())));
            ref = out.state().runs().get(session.activeRun()).ref();
        } else if (session.assignments().unattendedRuns() < out.assignmentLimits.maxUnattendedRuns()) {
            out.put(session.enqueue(new InboxItem(out.envelope.seq(), Input.Kind.REPORT, root, result, null, false, open.id())));
            InboxSteps.next(out, session.id());
            ref = new RunRef(session.principal(), session.id(), out.state().sessions().get(session.id()).activeRun());
        } else {
            out.put(session.append(result));
            ref = new RunRef(session.principal(), session.id(), session.lastRun());
            paused = true;
        }
        out.report(List.of(new ReportSettled(ref, open.id(), outcome, paused)));
        return ref;
    }
}
