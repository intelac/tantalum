package tantalum.shell.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tantalum.shell.skill.SkillNote;
import tantalum.shell.trace.Trace.Book;
import tantalum.shell.trace.Trace.Queued;
import tantalum.shell.trace.Trace.Track;
import tantalum.shell.trace.TraceRecord.Head;
import tantalum.shell.trace.TraceRecord.RunHead.Trigger;
import tantalum.shell.trace.TraceRecord.RunHead;
import tantalum.shell.trace.TraceRecord.Source;
import tantalum.shell.trace.TraceRecord.TaskNote;
import tantalum.shell.trace.TraceRecord.UserInput.Mode;
import tantalum.shell.trace.TraceRecord.UserInput;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.assignment.OpenAssignment;
import tantalum.value.decision.ReportSettled;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.command.RunTool;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.CompactionFailed;
import tantalum.value.decision.ContextCompacted;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.decision.HookDecided;
import tantalum.value.decision.HookReported;
import tantalum.value.decision.PhaseChanged;
import tantalum.value.decision.QuestionAsked;
import tantalum.value.decision.QuestionSettled;
import tantalum.value.decision.QueueEdited;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.QueueChanged;
import tantalum.value.event.ResultEvent;
import tantalum.value.event.SkillUse;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.event.WorkDone;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.AttachedBlock;
import tantalum.value.model.Message;
import tantalum.value.state.Awaiting;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * 一次事件的轨迹投影。顺序固定：先处理事件本身（提交、结果），再按内核的事实（钩子、压缩、状态变化），
 * 再按命令（请求、工具、工作、定时器），然后从状态补上被拦下和合成结果的工具，最后收尾结束的运行。
 */
final class TracePass {

    /** 记录所在的组：步或压缩组；{@code pending} 表示请求前的记录，归下一步但还可能改归当前步。 */
    record Place(Integer turn, Integer step, String compaction, boolean pending) {}

    final Book book;
    final EventEnvelope envelope;
    final KernelState before;
    final KernelState state;
    final Decision decision;
    final SessionId session;
    final Instant at;
    final long seq;
    final TraceRequests requests = new TraceRequests(this);
    final TraceSystem system = new TraceSystem(this);
    final TraceTools tools = new TraceTools(this);
    final TraceHooks hooks = new TraceHooks(this);
    private final Map<String, TraceRecord> changed;
    private final Set<RunId> ended = new LinkedHashSet<>();
    /** 这个事件里新建的记录数，用作 {@code pos} 的下标。 */
    private int created;

    TracePass(Book book, EventEnvelope envelope, KernelState before, Decision decision, Map<String, TraceRecord> changed) {
        this.book = book;
        this.envelope = envelope;
        this.before = before;
        this.state = decision.newState();
        this.decision = decision;
        this.changed = changed;
        this.session = envelope.session();
        this.at = envelope.at();
        this.seq = envelope.seq();
    }

    void run() {
        switch (envelope.event()) {
            case UserPrompt prompt -> submission(prompt.text(), prompt.attachments(), prompt.skills(), prompt.attached());
            case ContextAssembled assembled -> system.contextAssembled(assembled);
            case ModelReplied reply -> requests.replied(reply);
            case ModelFailed failure -> requests.failed(failure);
            case ModelInterrupted interrupted -> requests.interrupted(interrupted);
            case ToolFinished finished -> tools.finished(finished);
            case WorkDone done -> hooks.workDone(done);
            case tantalum.value.event.Assignment assigned -> assigned(assigned);
            default -> { }
        }
        List<Fact> facts = decision.facts();
        tasksSettled(facts);
        for (int i = 0; i < facts.size(); i++) {
            Fact fact = facts.get(i);
            if (!fact.run().session().equals(session)) {
                continue;
            }
            switch (fact) {
                case HookDecided decided -> hooks.decided(i, decided);
                case HookReported reported -> hooks.reported(i, reported, i + 1 < facts.size() ? facts.get(i + 1) : null);
                case ContextCompacted compacted -> requests.compacted(compacted.run().run(), compacted);
                case CompactionFailed failed -> requests.compacted(failed.run().run(), failed);
                case PhaseChanged phase -> phaseChanged(phase.run().run());
                case QuestionAsked asked -> hooks.asked(i, asked);
                case QuestionSettled settled -> hooks.settled(settled);
                case QueueEdited edited -> queueEdited(edited);
                default -> { }
            }
        }
        for (Command command : decision.commands()) {
            if (!command.run().session().equals(session)) {
                continue;
            }
            switch (command) {
                case CallModel call -> requests.called(call);
                case RunTool tool -> tools.dispatched(tool);
                case DoWork work -> hooks.work(work);
                case StartTimer timer -> requests.timer(timer);
                default -> { }
            }
        }
        tools.blocked();
        tools.appended();
        ended.forEach(this::finish);
    }

    // ---- 记录的增改 ----

    /** 新记录的共有字段，{@code pos} 按这个事件里的创建顺序编号。 */
    Head head(String id, RunId run, Place place, Instant when) {
        return new Head(id, seq + "." + created++, run, place.turn(), place.step(), place.compaction(), when, null);
    }

    /** 新建一条记录；请求前的记录同时登记为待定。 */
    void add(TraceRecord record, Place place, boolean open) {
        if (place.pending() && book.runs.get(record.head().run()) instanceof Track track) {
            track.pending.put(record.id(), record);
        }
        put(record, open);
    }

    /** 推送新版本。{@code open} 为 false 表示已经定下来，不再留在内存里（待定表除外）。 */
    void put(TraceRecord record, boolean open) {
        changed.put(record.id(), record);
        if (open) {
            book.open.put(record.id(), record);
        } else {
            book.open.remove(record.id());
        }
        if (book.runs.get(record.head().run()) instanceof Track track && track.pending.containsKey(record.id())) {
            track.pending.put(record.id(), record);
        }
    }

    /** 请求前的记录归下一步。 */
    Place pending(RunId run) {
        Track track = track(run);
        return track.manual ? compaction(run) : new Place(track.turn, track.step + 1, null, true);
    }

    /** 回复之后的记录归当前步；还没有请求时同请求前。 */
    Place current(RunId run) {
        Track track = track(run);
        if (track.manual) {
            return compaction(run);
        }
        return track.step == 0 ? pending(run) : new Place(track.turn, track.step, null, false);
    }

    /** 压缩组，没有时以这个事件开一个。 */
    Place compaction(RunId run) {
        Track track = track(run);
        if (track.compaction == null) {
            track.compaction = "k" + seq;
        }
        return new Place(track.turn, null, track.compaction, false);
    }

    /** 接下来是同一请求的重试：待定的记录改归当前步，重推一次。 */
    void settlePending(Track track, int step) {
        for (TraceRecord record : List.copyOf(track.pending.values())) {
            TraceRecord moved = record.withHead(record.head().inStep(step));
            track.pending.remove(record.id());
            put(moved, book.open.containsKey(record.id()));
        }
    }

    // ---- 运行 ----

    /** 运行的状态，第一次见到时开一轮并建标题记录。 */
    Track track(RunId run) {
        Track existing = book.runs.get(run);
        if (existing != null) {
            return existing;
        }
        dropSettledRuns();
        Track track = new Track();
        track.manual = envelope.event() instanceof CompactRequested;
        track.turn = track.manual ? null : ++book.turns;
        book.runs.put(run, track);
        Trigger trigger = trigger(run, track.manual);
        Place place = track.manual ? compaction(run) : new Place(track.turn, null, null, false);
        add(new RunHead(head(runHeadId(run), run, place, at), trigger, phaseOf(run), null, null), place, true);
        Queued queued = trigger == Trigger.FOLLOW_UP ? startedFromQueue() : null;
        if (queued != null) {
            Place first = pending(run);
            add(new UserInput(head("u:" + queued.seq(), run, first, queued.at()).ended(queued.at()), queued.mode(), queued.text(),
                    Source.USER, queued.attachments(), queued.skills(), queued.attached()), first, false);
        }
        return track;
    }

    /**
     * 这一轮的起因，读运行上记的（{@code RunCause}），不倒推：你说的话开的，是这一步的提交直接开的就是提问，否则是排着的那一条；
     * 交代开的、结果开的、手动压缩各是各的。
     */
    private Trigger trigger(RunId run, boolean manual) {
        RunState started = state.runs().get(run);
        if (manual || started == null) {
            return manual ? Trigger.MANUAL_COMPACTION : Trigger.PROMPT;
        }
        return switch (started.cause().startedBy()) {
            case COMPACTION -> Trigger.MANUAL_COMPACTION;
            case ASSIGNMENT -> Trigger.TASK;
            case REPORT -> Trigger.TASK_RESULT;
            case PROMPT -> envelope.event() instanceof UserPrompt && RunId.of(session, seq).equals(run) ? Trigger.PROMPT : Trigger.FOLLOW_UP;
        };
    }

    /** 开这一轮的那条排着的话：这一步离开收件队列的、你排着的那一条（按编号认，不按先后猜）。 */
    private Queued startedFromQueue() {
        SessionState now = state.sessions().get(session);
        java.util.Set<Long> still = new java.util.HashSet<>();
        if (now != null) {
            now.inbox().forEach(item -> still.add(item.id()));
        }
        for (Queued queued : List.copyOf(book.queued)) {
            if (!still.contains(queued.seq())) {
                book.queued.remove(queued);
                return queued;
            }
        }
        return null;
    }

    /** 新运行开始后，已结束运行留着等迟到结果的记录不再等（内核也不再把迟到的文字放进历史）。 */
    private void dropSettledRuns() {
        book.open.values().removeIf(record -> !book.runs.containsKey(record.head().run()));
    }

    private String phaseOf(RunId run) {
        return state.runs().get(run) instanceof RunState current ? current.phase().name() : null;
    }

    private void phaseChanged(RunId run) {
        track(run);
        if (!(book.open.get(runHeadId(run)) instanceof RunHead head)) {
            return;
        }
        if (state.runs().get(run) instanceof RunState current && current.phase() instanceof Ended end) {
            ended.add(run);
            // 模型认证失败时运行以原始错误结束，同请求一样不返回它。
            String detail = TraceRequests.auth(end.detail()) ? null : end.detail();
            put(new RunHead(head.head().ended(at), head.trigger(), end.name(), end.reason(), detail), false);
        } else {
            put(new RunHead(head.head(), head.trigger(), phaseOf(run), null, null), true);
        }
    }

    /** 结束的运行：在途的请求和工作收尾，待定的记录留在最后一步，丢掉运行的状态。 */
    private void finish(RunId run) {
        requests.runEnded(run);
        hooks.runEnded(run);
        tools.runEnded(run);
        book.runs.remove(run);
    }

    /** 事件所属的、仍在进行的运行。 */
    RunId activeRun() {
        SessionState current = state.sessions().get(session);
        return current == null ? null : current.activeRun();
    }

    /** 结果事件是运行正在等的（内核只处理这种）。 */
    boolean awaited(ResultEvent result) {
        return envelope.run() != null && before.runs().get(envelope.run()) instanceof RunState run
                && run.phase() instanceof Awaiting awaiting && awaiting.commands().contains(result.command());
    }

    /** 这个决定给会话新追加的消息。 */
    List<Message> appended() {
        SessionState after = state.sessions().get(session);
        if (after == null) {
            return List.of();
        }
        SessionState earlier = before.sessions().get(session);
        int from = earlier == null ? 0 : earlier.messages().size();
        return after.messages().subList(Math.min(from, after.messages().size()), after.messages().size());
    }

    // ---- 用户 ----

    /**
     * 你说的一句话：直接开了一轮记成提问；并进正在跑的这一轮记成插话；排着的到它那一轮开始时再记（排着的那条在收件队列里的编号就是这个序号）。
     */
    private void submission(String text, List<AttachmentRef> attachments, List<SkillUse> named, List<AttachedBlock> attached) {
        List<SkillNote> skills = SkillNote.of(named);
        RunId fresh = RunId.of(session, seq);
        SessionState after = state.sessions().get(session);
        tantalum.value.state.InboxItem waiting = after == null ? null
                : after.inbox().stream().filter(item -> item.id() == seq).findFirst().orElse(null);
        if (state.runs().containsKey(fresh) && !before.runs().containsKey(fresh)) {
            track(fresh);
            user(fresh, Mode.PROMPT, text, Source.USER, attachments, skills, attached);
        } else if (waiting != null && waiting.join() && activeRun() != null) {
            user(activeRun(), Mode.STEER, text, Source.USER, attachments, skills);
        } else if (waiting != null) {
            book.queued.addLast(new Queued(seq, at, text, Mode.FOLLOW_UP, attachments, skills, attached));
        }
    }

    /**
     * 你排的一条被改了（按编号）。排着的到运行开始时才记进 trace，所以改文字和移除只改这里记着的队列；
     * 改成并进这一轮的当场记成当前运行的一条插话。
     */
    private void queueEdited(QueueEdited edited) {
        List<Queued> queue = new ArrayList<>(book.queued);
        int index = -1;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).seq() == edited.item()) {
                index = i;
            }
        }
        if (index < 0) {
            return;
        }
        Queued queued = queue.remove(index);
        switch (edited.action()) {
            case EDIT -> {
                if (envelope.event() instanceof QueueChanged changed) {
                    queue.add(index, new Queued(queued.seq(), queued.at(), changed.text(), queued.mode(), queued.attachments(), queued.skills(),
                            queued.attached()));
                } else {
                    queue.add(index, queued);
                }
            }
            case REMOVE -> { }
            case JOIN -> user(edited.run().run(), Mode.STEER, queued.text(), Source.USER, queued.attachments(), queued.skills());
        }
        book.queued.clear();
        book.queued.addAll(queue);
    }

    void user(RunId run, Mode mode, String text, Source source, List<AttachmentRef> attachments) {
        user(run, mode, text, source, attachments, List.of());
    }

    void user(RunId run, Mode mode, String text, Source source, List<AttachmentRef> attachments, List<SkillNote> skills) {
        user(run, mode, text, source, attachments, skills, List.of());
    }

    void user(RunId run, Mode mode, String text, Source source, List<AttachmentRef> attachments, List<SkillNote> skills,
              List<AttachedBlock> attached) {
        Place place = pending(run);
        add(new UserInput(head("u:" + seq, run, place, at).ended(at), mode, text, source, attachments, skills, attached), place, false);
    }

    // ---- 任务 ----

    /** 交代到了帮手会话：开它那一轮，交代的内容是这一轮的起因。重复送达的不开运行，不记。 */
    private void assigned(tantalum.value.event.Assignment assigned) {
        RunId fresh = RunId.of(session, seq);
        SessionState helper = state.sessions().get(session);
        if (state.runs().containsKey(fresh) && !before.runs().containsKey(fresh)) {
            track(fresh);
            task(fresh, TaskNote.Side.ASSIGNED, assigned.id(), assigned.issuer(), helper == null || helper.helper() == null ? null
                    : helper.helper().agent(), null, assigned.prompt());
        }
    }

    /**
     * 放出的任务有了结果。看的是内核报的事实，不是事件的种类：结果可能是任务交回来的（{@code TaskEnded}），也可能是期限到了
     * （{@code TimerFired}），两种都是同一个事实，后到的那个什么都不改、也没有事实。空闲时它开了一轮，正在跑时进了那一轮。
     * 到了自动继续的上限时没有运行可挂，不记（结果在历史里，人说话后的那一轮看得到）。
     */
    private void tasksSettled(List<Fact> facts) {
        for (Fact fact : facts) {
            if (!(fact instanceof ReportSettled settled) || settled.paused() || !settled.run().session().equals(session)) {
                continue;
            }
            OpenAssignment open = openTask(before, settled.assignment());
            if (open == null) {
                continue;
            }
            RunId run = settled.run().run();
            track(run);
            String text = envelope.event() instanceof tantalum.value.event.Report report && report.assignment().equals(settled.assignment())
                    ? report.text() : null;
            task(run, TaskNote.Side.RESULT, settled.assignment(), open.session(), open.agent(), settled.outcome(), text);
        }
    }

    private OpenAssignment openTask(KernelState in, String task) {
        SessionState state = in.sessions().get(session);
        return state == null ? null : state.assignments().open().stream().filter(open -> open.id().equals(task)).findFirst().orElse(null);
    }

    private void task(RunId run, TaskNote.Side side, String task, SessionId other, String agent, AssignmentOutcome outcome, String text) {
        Place place = pending(run);
        add(new TaskNote(head("k:" + seq, run, place, at).ended(at), side, task, other.value(), agent, outcome, text), place, false);
    }

    /** 运行标题的 ID，和对话页运行状态条目的 ID 相同。 */
    static String runHeadId(RunId run) {
        return "run:" + run.value();
    }
}
