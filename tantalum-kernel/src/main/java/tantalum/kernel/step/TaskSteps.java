package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.ReturnTask;
import tantalum.value.command.StartTask;
import tantalum.value.command.StartTimer;
import tantalum.value.command.StopTask;
import tantalum.value.decision.Decision;
import tantalum.value.decision.TaskSettled;
import tantalum.value.decision.TaskStarted;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.TaskAssigned;
import tantalum.value.event.TaskEnded;
import tantalum.value.event.TaskStopRequested;
import tantalum.value.event.TimerFired;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TaskResultBlock;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.EndReason;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;
import tantalum.value.task.OpenTask;
import tantalum.value.task.TaskOrigin;
import tantalum.value.task.TaskOutcome;
import tantalum.value.task.TaskTools;
import tantalum.value.task.Tasks;

/**
 * 任务：一个会话把一件事放给专门为它开的会话去做，自己接着走；那边结束时结果作为一个事件回来。
 *
 * <p>没有新的等待状态。{@code task} 当场有结果，放出它的那一轮不停；任务执行到哪了只在它自己那一轮的状态里。
 * 放出的会话只记“哪些还没回来”（{@link Tasks#open()}），名单里的每一条恰好对应一个生效的 {@link TaskEnded}：
 * 任务那一轮结束（含被停、重启打断）、启动时的核对、设了期限时期限的定时器到，谁先到谁生效，后到的在名单里找不到，什么都不改。
 */
public final class TaskSteps {

    /** 交代的第一行最多留这么多字符，给人和模型认这是哪件事。 */
    static final int BRIEF_CHARS = 120;

    private TaskSteps() {}

    /**
     * 这一轮工具里的 {@code task}、{@code task_stop}：已经过了钩子，当场填上结果，不发 {@code RunTool}。
     * 名单是在这一步里改的，所以回放放出它的那条 {@code ModelReplied} 就能重新算出来。
     */
    static ToolRound settle(Outcome out, RunState run, ToolRound start) {
        ToolRound round = start;
        for (int i = 0; i < round.slots().size(); i++) {
            ToolSlot slot = round.slots().get(i);
            if (slot.status() != ToolSlot.Status.PENDING || !TaskTools.handles(slot.call().name())) {
                continue;
            }
            Result result = TaskTools.TASK.equals(slot.call().name()) ? start(out, run, slot.call(), i) : stop(out, run, slot.call());
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
        String prompt = fields.get(TaskTools.PROMPT) instanceof Json.Str text ? text.value().strip() : "";
        if (prompt.isEmpty()) {
            return Result.refused("task needs a non-empty \"prompt\": everything the task has to know, it does not see this conversation.");
        }
        Json named = fields.get(TaskTools.AGENT);
        if (named != null && !(named instanceof Json.Null) && !(named instanceof Json.Str)) {
            return Result.refused("\"agent\" must be the name of an agent, or left out.");
        }
        String agent = named instanceof Json.Str name && !name.value().isBlank() ? name.value().strip() : null;
        SessionState session = out.session(run.ref());
        Tasks tasks = session.tasks();
        if (tasks.origin() != null) {
            return Result.refused("A task cannot start tasks. Do this work yourself.");
        }
        if (tasks.open().size() >= out.taskLimits.maxOpen()) {
            return Result.refused("Not started: " + tasks.open().size() + " tasks are already running, which is the limit."
                    + " Their results arrive on their own; start this one after one of them has ended, or do the work yourself.");
        }
        if (tasks.unattendedTasks() >= out.taskLimits.maxUnattendedTasks()) {
            return Result.refused("Not started: " + tasks.unattendedTasks() + " tasks have been started since the user last spoke,"
                    + " which is the limit. Tell the user where things stand; the count starts over when they reply.");
        }
        String id = TaskTools.id(out.envelope.seq(), index);
        SessionId child = TaskTools.session(session.id(), id);
        // 期限默认没有；管理员设了才起定时器
        java.time.Duration limit = out.taskLimits.deadline();
        CommandId timer = limit == null ? null : out.nextCommandId();
        String brief = brief(prompt);
        out.put(session.withTasks(tasks.started(new OpenTask(id, child, agent, brief, out.envelope.at(),
                limit == null ? null : out.envelope.at().plus(limit), timer))));
        if (timer != null) {
            out.emit(new StartTimer(timer, run.ref(), limit));
        }
        // 任务用放出它的这一轮的模型（这一轮还没定下模型时用会话现在的选择）
        out.emit(new StartTask(out.nextCommandId(), run.ref(), id, child, agent, prompt, session.dir(), session.offRecord(),
                run.model() != null ? run.model() : session.model()));
        out.report(List.of(new TaskStarted(run.ref(), id, child, agent, brief, call.id())));
        return new Result("Started task " + id + ". It runs on its own while you go on; its result arrives in this conversation"
                + " when it ends. Do not wait for it or ask about it.", false);
    }

    private static Result stop(Outcome out, RunState run, ToolCallBlock call) {
        Map<String, Json> fields = call.arguments() instanceof Json.Obj obj ? obj.fields() : Map.of();
        String id = fields.get(TaskTools.ID) instanceof Json.Str text ? text.value().strip() : "";
        Optional<OpenTask> task = out.session(run.ref()).tasks().find(id);
        if (task.isEmpty()) {
            return Result.refused("No running task \"" + id + "\".");
        }
        out.emit(new StopTask(out.nextCommandId(), run.ref(), id, task.get().session()));
        return new Result("Stop requested for task " + id + ". Its result arrives when it has stopped.", false);
    }

    static String brief(String prompt) {
        String first = prompt.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse("");
        return first.length() <= BRIEF_CHARS ? first : first.substring(0, BRIEF_CHARS) + "…";
    }

    /**
     * 任务到了为它开的会话：建会话、开它唯一的一轮，主人是放出它的那个任务。会话已经有内容就是重复送达，不动。
     */
    public static Decision assigned(StepContext context) {
        TaskAssigned event = (TaskAssigned) context.envelope().event();
        EventEnvelope envelope = context.envelope();
        SessionState existing = context.state().sessions().get(envelope.session());
        // 已经有内容：重复送达。会话是别人的：不是给这个任务的（多用户，各人的会话互不相通）。都不动
        if (existing != null && (!existing.messages().isEmpty() || existing.tasks().origin() != null
                || !existing.principal().equals(envelope.principal()))) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        SessionState session = SessionState.start(envelope.session(), envelope.principal())
                .withTasks(Tasks.NONE.withOrigin(new TaskOrigin(event.parent(), event.task(), event.agent(), event.instructions(), event.tools())));
        if (event.dir() != null) {
            session = session.withDir(event.dir());
        }
        if (event.offRecord()) {
            session = session.withOffRecord();
        }
        out.put(session);
        SubmitSteps.startRun(out, new OwnerRef.OfTask(event.parent(), event.task()), Message.user(event.prompt()), event.model());
        return out.decision();
    }

    /** 为任务开的那一轮结束了：在同一步里把结果交回去。{@code RunEnd.finish} 是运行唯一的出口，所以每个任务的这一轮恰好交一次。 */
    static void returned(Outcome out, RunState run, OwnerRef.OfTask owner, EndReason reason, String detail) {
        TaskOutcome outcome = outcomeOf(reason, detail);
        out.emit(new ReturnTask(out.nextCommandId(), run.ref(), owner.parent(), owner.task(), outcome,
                textOf(outcome, out.session(run.ref()), detail)));
    }

    /** 任务那一轮怎么结束，任务就是什么结局。启动时的核对补发结果时用同一张表。 */
    public static TaskOutcome outcomeOf(EndReason reason, String detail) {
        return switch (reason) {
            case COMPLETED -> TaskOutcome.COMPLETED;
            case FAILED, STOPPED -> TaskOutcome.FAILED;
            case CANCELLED -> CancelSteps.INTERRUPTED_BY_RESTART.equals(detail) ? TaskOutcome.INTERRUPTED : TaskOutcome.STOPPED;
        };
    }

    /** 交回的文字：完成时是最后的回答，否则是结束的详情。 */
    public static String textOf(TaskOutcome outcome, SessionState session, String detail) {
        return outcome == TaskOutcome.COMPLETED ? answers(session) : detail;
    }

    /**
     * 任务这一轮的回答：不带工具调用的助手消息里的文字，按先后接起来。通常只有一条；结束前的钩子让它再答一次时
     * （比如引用核对要它改一处）就有几条，后一条往往只是更正，只带它会把原来的结论丢掉（2026-11-18 真模型测试里发现）。
     * 边调工具边说的话是过程，不带回。
     */
    static String answers(SessionState session) {
        StringBuilder all = new StringBuilder();
        for (Message message : session.messages()) {
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

    /** 放出的任务有了结果。名单里没有它：已经结束过了（重复、迟到的），不动。 */
    public static Decision ended(StepContext context) {
        TaskEnded event = (TaskEnded) context.envelope().event();
        SessionState session = context.state().sessions().get(context.envelope().session());
        // 名单里没有：已经结束过了。不是这个人的会话：结果只能回到放出任务的那个人自己的会话
        if (session == null || !session.principal().equals(context.envelope().principal()) || session.tasks().find(event.task()).isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        OpenTask task = session.tasks().find(event.task()).orElseThrow();
        RunRef ref = settled(out, session, event.task(), event.outcome(), event.text());
        if (task.timer() != null) {
            // 期限的定时器不用再等了：让它停下。它要是已经到了，迟到的那一下在名单里找不到任务，不生效
            out.emit(new AbortCommands(out.nextCommandId(), ref, Set.of(task.timer())));
        }
        return out.decision();
    }

    /**
     * 期限的定时器到了：任务还在名单里就按超时结束，并让它停下；它之后因为被停交回来的结果在名单里找不到，不再生效。
     * 别的没人等的定时器（迟到的重试、已经答过的问题）照旧什么都不改。
     */
    public static Decision timerFired(StepContext context) {
        TimerFired event = (TimerFired) context.envelope().event();
        SessionState session = context.state().sessions().get(context.envelope().session());
        Optional<OpenTask> task = session == null ? Optional.empty()
                : session.tasks().open().stream().filter(open -> event.command().equals(open.timer())).findFirst();
        if (task.isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        RunRef ref = settled(out, session, task.get().id(), TaskOutcome.TIMED_OUT, null);
        out.emit(new StopTask(out.nextCommandId(), ref, task.get().id(), task.get().session()));
        return out.decision();
    }

    /** 用户在网页上停任务：给名单里对应的任务各发一条停止。名单不动，等它们各自交回结果。 */
    public static Decision stopRequested(StepContext context) {
        TaskStopRequested event = (TaskStopRequested) context.envelope().event();
        SessionState session = context.state().sessions().get(context.envelope().session());
        if (session == null || session.lastRun() == null || !session.principal().equals(context.envelope().principal())) {
            return Decision.unchanged(context.state());
        }
        List<OpenTask> stopping = session.tasks().open().stream()
                .filter(task -> event.task() == null || task.id().equals(event.task())).toList();
        if (stopping.isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        RunRef ref = new RunRef(session.principal(), session.id(), session.lastRun());
        for (OpenTask task : stopping) {
            out.emit(new StopTask(out.nextCommandId(), ref, task.id(), task.session()));
        }
        return out.decision();
    }

    /**
     * 从名单去掉这个任务，把结果交给模型：正在跑一轮就进这一轮，模型下次被调用时看到；空闲就开新的一轮，
     * 这是一轮不是人发起的运行，所以数着，到了上限只记进历史、不开运行，等人说话。返回看到结果的那一轮。
     */
    private static RunRef settled(Outcome out, SessionState before, String task, TaskOutcome outcome, String text) {
        SessionState session = before.withTasks(before.tasks().ended(task));
        Message result = new Message(Role.USER, List.of(new TaskResultBlock(task, outcome, text)), false);
        RunId active = session.activeRun();
        RunState running = active == null ? null : out.state().runs().get(active);
        RunRef ref;
        boolean paused = false;
        if (running != null && !running.isEnded()) {
            out.put(session);
            List<Message> steers = new ArrayList<>(running.pendingSteers());
            steers.add(result);
            out.put(running.withPendingSteers(steers));
            ref = running.ref();
        } else if (session.tasks().unattendedRuns() < out.taskLimits.maxUnattendedRuns()) {
            out.put(session.withTasks(session.tasks().ranUnattended()));
            SubmitSteps.startRun(out, new OwnerRef.OfSession(session.id()), result, null);
            ref = new RunRef(session.principal(), session.id(), out.state().sessions().get(session.id()).activeRun());
        } else {
            out.put(session.append(result));
            ref = new RunRef(session.principal(), session.id(), session.lastRun());
            paused = true;
        }
        out.report(List.of(new TaskSettled(ref, task, outcome, paused)));
        return ref;
    }

    /** 人说话了：无人值守的两个计数清零。 */
    static void attended(Outcome out, SessionId id) {
        SessionState session = out.state().sessions().get(id);
        if (session != null && session.tasks().attended() != session.tasks()) {
            out.put(session.withTasks(session.tasks().attended()));
        }
    }
}
