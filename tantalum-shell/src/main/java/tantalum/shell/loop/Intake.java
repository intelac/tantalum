package tantalum.shell.loop;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import tantalum.kernel.policy.InputPolicy;
import tantalum.shell.profile.SessionProfile;
import tantalum.shell.profile.SessionProfile.Action;
import tantalum.value.event.AssignmentCancelled;
import tantalum.value.event.Delivery;
import tantalum.value.event.Input;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.SessionCreated;
import tantalum.value.event.StopRequested;
import tantalum.value.id.SessionIds;
import tantalum.value.state.SessionKind;
import tantalum.kernel.compaction.Compactor;
import tantalum.shell.monitor.Probes;
import tantalum.shell.monitor.Stage;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.event.Answered;
import tantalum.value.event.Cancel;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.QueueChanged;
import tantalum.value.event.SessionRenamed;
import tantalum.value.event.SkillUse;
import tantalum.value.event.Submitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.AttachedBlock;
import tantalum.value.model.ModelChoice;
import tantalum.value.question.Answer;
import tantalum.value.state.SessionState;

/**
 * 进到会话里的一切的入口：你的提交（说话、回答、压缩、改名、改队列、叫停、撤下、取消），和会话之间的送达（交代、结果、
 * 启动时补发的）。这里查：会话是不是这个人的；这种会话能不能做这个操作（{@link SessionProfile}）、收不收这种输入
 * （{@link InputPolicy}）；新会话先记到这个人名下，再发它的创建事件。内核会按记录再验一次。
 *
 * <p>你的提交去重、占名额之后发布到输入环；发布不成就全部退回。提交带一张回执，那一步落盘后完成（{@link Receipts}）。
 * 事件处理完后由 {@link #processed} 归还名额。会话之间的送达不占你的名额，也不按请求号去重（内核按编号去重）。
 */
public final class Intake {

    /**
     * 提交的结果。{@code status} 对应 HTTP 状态。{@code saved} 在提交那一步落盘后完成，
     * 接口等它再回“已接受”；被拒绝的提交没有回执，为 null。
     */
    public record Submission(int status, SubmissionId submission, boolean duplicate, CompletableFuture<Void> saved) {

        static Submission rejected(int status) {
            return new Submission(status, null, false, null);
        }
    }

    /** 新会话 ID 第一次出现时认领它，返回 HTTP 状态：200 归本人，404 已属别人，503 记不下来。 */
    public interface Claims {
        int claim(Principal principal, SessionId session);
    }

    /** 会话的当前状态，不在内存时从文件读；不存在或不是本人的为空。 */
    public interface Sessions {
        Optional<SessionState> find(Principal principal, SessionId session);
    }

    /**
     * 不在内存的会话是哪一种：查索引，不读会话文件（每次提交都读一遍文件太贵）。这个人的、写过事件的会话返回种类，
     * 其余（没有、只认领过、是别人的）为 null。索引可能晚一步：晚了的那几种情况内核还会按记录再挡一次。
     */
    public interface Kinds {
        SessionKind kind(Principal principal, SessionId session);
    }

    private final InputRing input;
    private final ExternalGate gate;
    private final SubmissionLedger ledger;
    private final StateView view;
    private final Residency residency;
    private final Claims claims;
    private final Sessions sessions;
    private final Kinds kinds;
    private final Clock clock;
    private final Receipts receipts;
    /** 需要开新运行、还没处理完的提交：处理完时多归还一个活跃运行名额。 */
    private final Set<SubmissionId> newRuns = ConcurrentHashMap.newKeySet();

    public Intake(InputRing input, ExternalGate gate, SubmissionLedger ledger, StateView view, Residency residency,
                  Claims claims, Sessions sessions, Kinds kinds, Clock clock, Receipts receipts) {
        this.kinds = kinds;
        this.receipts = receipts;
        this.input = input;
        this.gate = gate;
        this.ledger = ledger;
        this.view = view;
        this.residency = residency;
        this.claims = claims;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * 往会话里上传附件前的归属检查，规则同提交：别人的会话返回 404；新会话 ID 第一次出现时认领，归本人。
     * 返回 HTTP 状态。
     */
    public int access(Principal principal, SessionId session) {
        if (residency.failure(session) != null) {
            return 500;
        }
        // 帮手那种样子的编号留给帮手：不能靠上传附件来占（同提交）
        if (SessionIds.helperShaped(session)) {
            return 404;
        }
        Known known = known(principal, session);
        if (known.foreign()) {
            return 404;
        }
        if (known.kind() != null) {
            return SessionProfile.of(known.kind()).allows(Action.UPLOAD) ? 200 : 409;
        }
        return claims.claim(principal, session);
    }

    /** 你说的一句话，排队（{@link Delivery#QUEUE}）或并进正在跑的这一轮（{@link Delivery#JOIN}）。载入失败的会话到重启前都返回 500。 */
    public Submission submit(Principal principal, SessionId session, RequestId request, Delivery delivery, String text) {
        return submit(principal, session, request, delivery, text, List.of(), null, null, List.of(), List.of(), false);
    }

    /**
     * 你说的一句话，带全部可选的东西。附件引用已由调用方按本人目录里的元数据组成并查过限制；{@code model} 已查过，可以为 null
     * （用会话现在的），并进这一轮的不换模型、不带它。{@code skills} 是调用方在文字里找到并已经读好的点名技能；
     * {@code attached} 是插件在提交时附的内容（memory-plugin）。
     * {@code dir}（调用方从本人登记的工作区查到的路径）和 {@code offRecord}（不进记忆）只在会话还不存在时用：它们是会话建的时候定下的。
     */
    public Submission submit(Principal principal, SessionId session, RequestId request, Delivery delivery, String text,
                             List<AttachmentRef> attachments, ModelChoice model, String dir, List<SkillUse> skills,
                             List<AttachedBlock> attached, boolean offRecord) {
        if (residency.failure(session) != null) {
            return Submission.rejected(500);
        }
        // 帮手那种样子的编号留给帮手，人不能用它开会话：否则可以先占住一个编号，让别人的交代送不进去。要在认领之前：被拒的请求不能把编号占下
        if (SessionIds.helperShaped(session)) {
            return Submission.rejected(409);
        }
        Known known = known(principal, session);
        if (known.foreign()) {
            return Submission.rejected(404);
        }
        SessionCreated created = null;
        if (known.kind() != null) {
            if (!SessionProfile.of(known.kind()).allows(Action.PROMPT)) {
                return Submission.rejected(409);
            }
        } else {
            int owner = claims.claim(principal, session);
            if (owner != 200) {
                return Submission.rejected(owner);
            }
            created = new SessionCreated(SessionIds.mainShaped(session) ? SessionKind.MAIN : SessionKind.REGULAR, dir, offRecord, null);
        }
        boolean needsNewRun = known.resident() == null || known.resident().activeRun() == null;
        ModelChoice chosen = delivery == Delivery.JOIN ? null : model;
        return publish(principal, session, request, needsNewRun, created,
                submission -> new UserPrompt(submission, request, text, attachments, chosen, skills, attached, delivery));
    }

    /**
     * 会话之间的送达：交代、结果、启动时补发的结果。{@code create} 不为 null 时会话还不存在就先建它（帮手会话），两条挨着进环。
     * 返回 HTTP 状态：200 放进了环；404 会话不是这个人的（或者不存在又没说怎么建）；409 这种会话不收这种输入；429 环满了，调用方等一会儿再送。
     */
    public int deliver(Principal principal, SessionId session, SessionCreated create, Input delivered) {
        Known known = known(principal, session);
        SessionKind kind;
        boolean creating = false;
        if (known.foreign()) {
            return 404;
        } else if (known.kind() != null) {
            kind = known.kind();
        } else if (create == null) {
            return 404;
        } else {
            if (claims.claim(principal, session) != 200) {
                return 404;
            }
            kind = create.kind();
            creating = true;
        }
        if (!InputPolicy.of(kind, delivered.kind(), false).accepted()) {
            return 409;
        }
        if (creating && !publishCreation(principal, session, create)) {
            return 429;
        }
        EventEnvelope envelope = envelope(principal, session, null, delivered);
        Probes.event(input.probe(), Stage.INTAKE, envelope, null);
        return input.tryPublish(envelope) ? 200 : 429;
    }

    /**
     * 会话之间送的不是输入的那几样（delivery）：叫停（{@code AssignmentCancelled}）、“送到了”“不收”、启动时的重送。
     * 只查会话是这个人的、已经有了；不过输入表、不占名额（内核按自己的状态处理）。返回 200 放进了环，404 会话不是这个人的或还没有，429 环满。
     */
    public int handOver(Principal principal, SessionId session, tantalum.value.event.Event event) {
        Known known = known(principal, session);
        if (known.foreign() || known.kind() == null) {
            return 404;
        }
        EventEnvelope envelope = envelope(principal, session, null, event);
        Probes.event(input.probe(), Stage.INTAKE, envelope, null);
        return input.tryPublish(envelope) ? 200 : 429;
    }

    /** 一个会话在入口看来的样子：{@code resident} 是在内存的状态（不在为 null）；{@code kind} 为 null 表示还没建。 */
    private record Known(boolean foreign, SessionState resident, SessionKind kind) {}

    private Known known(Principal principal, SessionId session) {
        SessionState resident = view.current().sessions().get(session);
        if (resident != null) {
            return new Known(!resident.principal().equals(principal), resident, resident.kind());
        }
        return new Known(false, null, kinds.kind(principal, session));
    }

    /** 启动时给上一个进程没结束的运行收尾。返回 HTTP 状态：200 放进了环，404 不是这个人的会话，429 环满了。 */
    public int interrupt(Principal principal, RunRef run) {
        SessionState existing = view.current().sessions().get(run.session());
        if (existing != null && !existing.principal().equals(principal)) {
            return 404;
        }
        return input.tryPublish(envelope(principal, run.session(), run.run(), new RunInterrupted())) ? 200 : 429;
    }

    /**
     * 回答运行问的问题。和提交一样：别人的会话 404、落盘后才回“已接受”；没有活动运行回 409，
     * 调用方当作“问题已经不在”。问题是不是当前的、回答合不合法，由内核按记录再验一次，不合法的只记事实。
     */
    public Submission answer(Principal principal, SessionId session, RequestId request, String question, Answer answer) {
        if (residency.failure(session) != null) {
            return Submission.rejected(500);
        }
        Optional<SessionState> found = sessions.find(principal, session);
        if (found.isEmpty()) {
            return Submission.rejected(404);
        }
        if (found.get().activeRun() == null || !SessionProfile.of(found.get().kind()).allows(Action.ANSWER)) {
            return Submission.rejected(409);
        }
        return publish(principal, session, request, false, null, submission -> new Answered(submission, request, question, answer));
    }

    /**
     * 手动压缩。会话不存在或不是该用户的 → 404；正在运行、没有压缩设置或没有可压缩的内容 → 409。
     * 通过后发布 {@code CompactRequested}，内核在空闲会话上开一个特殊运行。{@code instructions} 可为 null。
     */
    public Submission compact(Principal principal, SessionId session, RequestId request, String instructions) {
        Optional<SessionState> found = sessions.find(principal, session);
        if (found.isEmpty()) {
            return Submission.rejected(404);
        }
        SessionState existing = found.get();
        if (!SessionProfile.of(existing.kind()).allows(Action.COMPACT) || existing.activeRun() != null || existing.context() == null || existing.context().compaction() == null
                || !Compactor.worthCompacting(existing, existing.context().system(), existing.context().tools())) {
            return Submission.rejected(409);
        }
        return publish(principal, session, request, true, null, submission -> new CompactRequested(submission, request, instructions));
    }

    /**
     * 给会话改名。会话不存在或不是该用户的 → 404。运行中也可以改：内核照记，不影响运行。
     * {@code title} 由调用方去掉首尾空白、限制长度；空串表示恢复自动标题。
     */
    public Submission rename(Principal principal, SessionId session, RequestId request, String title) {
        Submission refused = refused(principal, session, Action.RENAME);
        if (refused != null) {
            return refused;
        }
        return publish(principal, session, request, false, null, submission -> new SessionRenamed(submission, request, title));
    }

    /**
     * 改收件队列里你排的一条（见 {@link QueueChanged}），按条目的编号。会话不存在或不是该用户的 → 404。那一条还在不在由内核判断，
     * 不在就什么都不改。{@code text} 只在改文字时有。
     */
    public Submission changeQueue(Principal principal, SessionId session, RequestId request, QueueChanged.Action action, long item, String text) {
        Submission refused = refused(principal, session, Action.QUEUE);
        if (refused != null) {
            return refused;
        }
        return publish(principal, session, request, false, null, submission -> new QueueChanged(submission, request, action, item, text));
    }

    /** 会话不存在或不是这个人的 → 404；这种会话不能做这个操作 → 409；能做为 null。 */
    private Submission refused(Principal principal, SessionId session, Action action) {
        Optional<SessionState> found = sessions.find(principal, session);
        if (found.isEmpty()) {
            return Submission.rejected(404);
        }
        return SessionProfile.of(found.get().kind()).allows(action) ? null : Submission.rejected(409);
    }

    /** 同 {@link #refused}，给不走回执的那几个操作：返回 HTTP 状态，能做为 200。 */
    private int allowed(Principal principal, SessionId session, Action action) {
        Submission refused = refused(principal, session, action);
        return refused == null ? 200 : refused.status();
    }

    /**
     * 叫停这个会话交代出去的事，{@code assignment} 为 null 是全部。返回 HTTP 状态；会话不存在或不是该用户的 → 404。
     * 和取消一样不去重、不占活跃运行名额；名单里没有它时内核什么都不改。
     */
    public int stopAssignments(Principal principal, SessionId session, String assignment) {
        int allowed = allowed(principal, session, Action.STOP_ASSIGNMENTS);
        if (allowed != 200) {
            return allowed;
        }
        ExternalGate.Verdict verdict = gate.admit(principal, false);
        if (verdict != ExternalGate.Verdict.ADMITTED) {
            return status(verdict);
        }
        EventEnvelope stop = envelope(principal, session, null,
                new StopRequested(new RequestId(UUID.randomUUID().toString()), assignment));
        Probes.event(input.probe(), Stage.INTAKE, stop, null);
        if (!input.tryPublish(stop)) {
            gate.release(principal);
            return 429;
        }
        return 200;
    }

    /**
     * 把会话里的几段文字撤下（{@link tantalum.value.event.TextWithdrawn}）。返回 HTTP 状态；会话不存在或不是该用户的 → 404。
     * 不去重（重复送来内核什么都不改）、不占活跃运行名额；运行中也可以，内核只换字。{@code by} 记着是谁定的。
     */
    public int withdraw(Principal principal, SessionId session, List<tantalum.value.event.TextWithdrawn.Swap> swaps,
                        tantalum.value.event.TextWithdrawn.By by) {
        int allowed = allowed(principal, session, Action.WITHDRAW);
        if (allowed != 200) {
            return allowed;
        }
        ExternalGate.Verdict verdict = gate.admit(principal, false);
        if (verdict != ExternalGate.Verdict.ADMITTED) {
            return status(verdict);
        }
        EventEnvelope withdrawn = envelope(principal, session, null,
                new tantalum.value.event.TextWithdrawn(new RequestId(UUID.randomUUID().toString()), swaps, by));
        Probes.event(input.probe(), Stage.INTAKE, withdrawn, null);
        if (!input.tryPublish(withdrawn)) {
            gate.release(principal);
            return 429;
        }
        return 200;
    }

    /** 返回 HTTP 状态。取消不去重，也不占活跃运行名额。 */
    public int cancel(Principal principal, RunRef run) {
        ExternalGate.Verdict verdict = gate.admit(principal, false);
        if (verdict != ExternalGate.Verdict.ADMITTED) {
            return status(verdict);
        }
        EventEnvelope cancel = envelope(principal, run.session(), run.run(), new Cancel(new RequestId(UUID.randomUUID().toString())));
        Probes.event(input.probe(), Stage.INTAKE, cancel, null);
        if (!input.tryPublish(cancel)) {
            gate.release(principal);
            return 429;
        }
        return 200;
    }

    /** 事件走完管线后调用：外部事件归还名额。 */
    public void processed(EventEnvelope envelope) {
        switch (envelope.event()) {
            case Submitted submitted -> release(envelope.principal(), submitted.submission());
            case Cancel cancel -> gate.release(envelope.principal());
            case StopRequested stop -> gate.release(envelope.principal());
            case tantalum.value.event.TextWithdrawn withdrawn -> gate.release(envelope.principal());
            default -> { }
        }
    }

    /**
     * 回执和认领在同一个临界区里登记，所以重复提交要么看不到认领、要么拿到的是同一张还在途的回执；
     * 原提交被拒、没进环或中途抛异常时回执以失败完成，名额、新运行计数和认领全部退回。
     */
    private Submission publish(Principal principal, SessionId session, RequestId request, boolean needsNewRun, SessionCreated created,
                               Function<SubmissionId, Event> event) {
        SubmissionLedger.Claim claim;
        try {
            claim = ledger.claim(principal, request, receipts::expect);
        } catch (UncheckedIOException indexUnavailable) {
            return Submission.rejected(503);
        }
        if (!claim.isNew()) {
            if (gate.halted()) {
                return Submission.rejected(503);
            }
            return new Submission(200, claim.submission(), true, receipts.of(claim.submission()));
        }
        CompletableFuture<Void> saved = receipts.of(claim.submission());
        boolean admitted = false;
        try {
            ExternalGate.Verdict verdict = gate.admit(principal, needsNewRun);
            if (verdict != ExternalGate.Verdict.ADMITTED) {
                abandon(principal, request, claim.submission(), false);
                return Submission.rejected(status(verdict));
            }
            admitted = true;
            if (needsNewRun) {
                newRuns.add(claim.submission());
            }
            // 新会话：创建事件和这次提交挨着进环（同一个人的事件按进环的顺序处理）。建了而提交没进去时会话留着是空的，再提交一次照常
            if (created != null && !publishCreation(principal, session, created)) {
                abandon(principal, request, claim.submission(), true);
                return Submission.rejected(429);
            }
            EventEnvelope submitted = envelope(principal, session, null, event.apply(claim.submission()));
            Probes.event(input.probe(), Stage.INTAKE, submitted, null);
            if (!input.tryPublish(submitted)) {
                abandon(principal, request, claim.submission(), true);
                return Submission.rejected(429);
            }
            return new Submission(200, claim.submission(), false, saved);
        } catch (RuntimeException failure) {
            abandon(principal, request, claim.submission(), admitted);
            throw failure;
        }
    }

    /** 提交没能进入管线：回执失败、名额和认领退回。 */
    private void abandon(Principal principal, RequestId request, SubmissionId submission, boolean admitted) {
        receipts.fail(submission);
        if (admitted) {
            release(principal, submission);
        }
        ledger.release(principal, request);
    }

    private void release(Principal principal, SubmissionId submission) {
        gate.release(principal);
        if (newRuns.remove(submission)) {
            gate.releaseNewRun();
        }
    }

    /** 建会话的事件和别的一样经过入口：监视里看得到它。 */
    private boolean publishCreation(Principal principal, SessionId session, SessionCreated created) {
        EventEnvelope creation = envelope(principal, session, null, created);
        Probes.event(input.probe(), Stage.INTAKE, creation, null);
        return input.tryPublish(creation);
    }

    private EventEnvelope envelope(Principal principal, SessionId session, RunId run, Event event) {
        return new EventEnvelope(EventEnvelope.UNASSIGNED, clock.instant(), principal, session, run, event);
    }

    private static int status(ExternalGate.Verdict verdict) {
        return verdict == ExternalGate.Verdict.HALTED ? 503 : 429;
    }
}
