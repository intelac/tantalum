package tantalum.shell.trace;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tantalum.shell.trace.Trace.Book;
import tantalum.shell.trace.Trace.Queued;
import tantalum.shell.trace.Trace.Track;
import tantalum.shell.trace.TraceRecord.Head;
import tantalum.shell.trace.TraceRecord.RunHead;
import tantalum.shell.trace.TraceRecord.RunHead.Trigger;
import tantalum.shell.trace.TraceRecord.Source;
import tantalum.shell.trace.TraceRecord.UserInput;
import tantalum.shell.trace.TraceRecord.UserInput.Mode;
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
import tantalum.value.event.CompactRequested;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ResultEvent;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.Submitted;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.event.WorkDone;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
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
            case UserPrompt prompt -> submission(prompt.text(), Mode.PROMPT);
            case FollowUpSubmitted followUp -> submission(followUp.text(), Mode.FOLLOW_UP);
            case SteerSubmitted steer -> submission(steer.text(), Mode.STEER);
            case ContextAssembled assembled -> system.contextAssembled(assembled);
            case ModelReplied reply -> requests.replied(reply);
            case ModelFailed failure -> requests.failed(failure);
            case ModelInterrupted interrupted -> requests.interrupted(interrupted);
            case ToolFinished finished -> tools.finished(finished);
            case WorkDone done -> hooks.workDone(done);
            default -> { }
        }
        List<Fact> facts = decision.facts();
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
        Trigger trigger = track.manual ? Trigger.MANUAL_COMPACTION
                : envelope.event() instanceof Submitted ? Trigger.PROMPT : Trigger.FOLLOW_UP;
        Place place = track.manual ? compaction(run) : new Place(track.turn, null, null, false);
        add(new RunHead(head(runHeadId(run), run, place, at), trigger, phaseOf(run), null, null), place, true);
        if (trigger == Trigger.FOLLOW_UP && !book.queued.isEmpty()) {
            Queued queued = book.queued.pollFirst();
            Place first = pending(run);
            add(new UserInput(head("u:" + queued.seq(), run, first, queued.at()).ended(queued.at()), queued.mode(), queued.text(),
                    Source.USER), first, false);
        }
        return track;
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

    /** 用户提交：直接开始运行、插进当前运行，或排队到运行开始时再记。 */
    private void submission(String text, Mode mode) {
        RunId fresh = RunId.of(session, seq);
        SessionState earlier = before.sessions().get(session);
        SessionState after = state.sessions().get(session);
        if (state.runs().containsKey(fresh) && !before.runs().containsKey(fresh)) {
            track(fresh);
            user(fresh, mode, text, Source.USER);
        } else if (mode == Mode.STEER && activeRun() != null) {
            user(activeRun(), mode, text, Source.USER);
        } else if (after != null && after.followUps().size() > (earlier == null ? 0 : earlier.followUps().size())) {
            book.queued.addLast(new Queued(seq, at, text, mode));
        }
    }

    void user(RunId run, Mode mode, String text, Source source) {
        Place place = pending(run);
        add(new UserInput(head("u:" + seq, run, place, at).ended(at), mode, text, source), place, false);
    }

    /** 运行标题的 ID，和对话页运行状态条目的 ID 相同。 */
    static String runHeadId(RunId run) {
        return "run:" + run.value();
    }
}
