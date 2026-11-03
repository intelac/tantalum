package tantalum.shell.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import tantalum.kernel.compaction.SummaryPrompt;
import tantalum.kernel.step.CompactSteps;
import tantalum.kernel.step.RetrySteps;
import tantalum.shell.model.Reasoning;
import tantalum.shell.trace.Trace.Split;
import tantalum.shell.trace.Trace.Track;
import tantalum.shell.trace.TracePass.Place;
import tantalum.shell.trace.TraceRecord.Attempt.Outcome;
import tantalum.shell.trace.TraceRecord.Attempt;
import tantalum.shell.trace.TraceRecord.Compacted;
import tantalum.shell.trace.TraceRecord.Head;
import tantalum.shell.trace.TraceRecord.ModelRequest.Purpose;
import tantalum.shell.trace.TraceRecord.ModelRequest.Status;
import tantalum.shell.trace.TraceRecord.ModelRequest;
import tantalum.value.command.CallModel;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.CompactionFailed;
import tantalum.value.decision.ContextCompacted;
import tantalum.value.decision.Fact;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunId;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.RequestOptions;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.RunState;

/** 请求和它的尝试（重试归并、状态、用量），以及压缩的结果。 */
final class TraceRequests {

    /** 重试等待中的状态：请求还没定下来。 */
    private static final Set<String> RETRY_WAITS = Set.of(RetrySteps.AWAITING_RETRY, CompactSteps.AWAITING_SUMMARY_RETRY);

    private final TracePass pass;

    TraceRequests(TracePass pass) {
        this.pass = pass;
    }

    /**
     * 发出一次模型调用。用途相同、且运行的重试次数比上一次尝试时大，就是上一个请求的新尝试：重试等待和失败钩子的工作都会加一，
     * 收到回复、压缩采用或未采用都会清零。重试途中的摘要调用用途不同，是新请求。
     */
    void called(CallModel call) {
        RunId run = call.run().run();
        RunState current = pass.state.runs().get(run);
        Track track = pass.track(run);
        Purpose purpose = current.compacting() != null ? Purpose.SUMMARY : Purpose.NORMAL;
        ModelRequest last = waiting(track);
        boolean retry = last != null && purpose == track.lastPurpose && current.retries() > track.lastRetries;
        Split split = pass.system.split(call.prompt(), run, track);
        track.split = split;
        if (purpose == Purpose.SUMMARY) {
            track.summarySplit = split;
        }
        Attempt attempt = new Attempt(call.id().value(), pass.at, null, null, null, Outcome.PENDING, null, null, null);
        Place place;
        if (retry) {
            place = purpose == Purpose.NORMAL ? pass.current(run) : placeOf(last);
            if (purpose == Purpose.NORMAL) {
                pass.settlePending(track, track.step);
            }
        } else {
            if (last != null) {
                pass.put(settled(last, Status.FAILED), false);
            }
            if (purpose == Purpose.NORMAL) {
                track.step++;
                track.pending.clear();
            }
            place = purpose == Purpose.NORMAL ? pass.current(run) : pass.compaction(run);
        }
        pass.system.called(call, split, place);
        if (retry) {
            List<Attempt> attempts = new ArrayList<>(last.attempts());
            attempts.add(attempt);
            pass.put(with(last, last.status(), attempts, last.partial(), last.failure()), true);
        } else {
            String id = "r:" + call.id().value();
            pass.add(new ModelRequest(pass.head(id, run, place, pass.at), ++pass.book.requests, purpose, Status.WAITING,
                    List.of(attempt), 1 + RetrySteps.MAX_RETRIES, null, null, null, 0, null, null, null, null, null, null, null,
                    call.omittedImages()),
                    place, true);
            track.lastRequest = id;
            track.lastPurpose = purpose;
        }
        track.lastRetries = current.retries();
    }

    /** 运行上一个请求还在等重试时返回它，否则为空。 */
    private ModelRequest waiting(Track track) {
        return track.lastRequest != null && pass.book.open.get(track.lastRequest) instanceof ModelRequest request
                && request.status() == Status.WAITING ? request : null;
    }

    private static Place placeOf(ModelRequest request) {
        return new Place(request.head().turn(), request.head().step(), request.head().compaction(), false);
    }

    /** 压缩的结果在压缩组里，之后压缩组结束（手动压缩整个运行都是一组）。 */
    void compacted(RunId run, Fact fact) {
        Place place = pass.compaction(run);
        Head head = pass.head("k:" + pass.seq, run, place, pass.at).ended(pass.at);
        String summary = fact instanceof ContextCompacted ok && !ok.trimOnly() && pass.envelope.event() instanceof ModelReplied reply
                ? SummaryPrompt.text(reply.content()) : null;
        Compacted record = switch (fact) {
            case ContextCompacted ok -> new Compacted(head, ok.trigger(), true, ok.trimOnly(), ok.estimateBefore(), ok.estimateAfter(),
                    ok.usage(), null, summary);
            case CompactionFailed failed -> new Compacted(head, failed.trigger(), false, false, 0, 0, null, failed.reason(), null);
            default -> throw new IllegalArgumentException(fact.type());
        };
        pass.add(record, place, false);
        Track track = pass.track(run);
        if (!track.manual) {
            track.compaction = null;
        }
    }

    void replied(ModelReplied reply) {
        ModelRequest request = inFlight(reply.command());
        if (request == null) {
            return;
        }
        if (reply.usage() != null) {
            pass.book.total = pass.book.total.plus(reply.usage());
        }
        Attempt attempt = ended(request.last(), reply.startedAt(), reply.firstTokenAt(), Outcome.REPLIED, null, null);
        List<ContentBlock> content = reply.content();
        String text = content.stream().filter(TextBlock.class::isInstance).map(block -> ((TextBlock) block).text())
                .collect(Collectors.collectingAndThen(Collectors.joining("\n\n"), joined -> joined.isEmpty() ? null : joined));
        String thinking = content.stream().filter(ReasoningBlock.class::isInstance)
                .flatMap(block -> Reasoning.displayText((ReasoningBlock) block).stream())
                .collect(Collectors.collectingAndThen(Collectors.joining("\n\n"), joined -> joined.isEmpty() ? null : joined));
        int calls = (int) content.stream().filter(ToolCallBlock.class::isInstance).count();
        RequestOptions options = reply.options();
        // 重试成功后请求是完成：之前失败的原因和部分文字只留在各次尝试里，不改变请求现在的状态。
        ModelRequest done = new ModelRequest(request.head().ended(pass.at), request.number(), request.purpose(), Status.DONE,
                replaced(request, attempt), request.maxAttempts(), options == null ? null : options.provider(), reply.actualModel(),
                reply.stop(), calls, reply.usage(), pass.book.total, text, thinking, null, options, null, request.omittedImages());
        pass.put(done, false);
        if (request.purpose() == Purpose.NORMAL) {
            pass.tools.created(reply, done);
        }
    }

    /** 认证失败只给 {@code auth}，不返回原始错误（有的服务会回显打了码的密钥）。还要重试时请求仍在等待。 */
    void failed(ModelFailed failure) {
        ModelRequest request = inFlight(failure.command());
        if (request == null) {
            return;
        }
        String detail = failure.detail();
        boolean auth = auth(detail);
        Attempt attempt = ended(request.last(), failure.startedAt(), failure.firstTokenAt(), Outcome.FAILED, failure.reason(),
                auth ? null : detail);
        String partial = failure.partialText() == null || failure.partialText().isEmpty() ? request.partial() : failure.partialText();
        ModelRequest updated = with(request, Status.WAITING, replaced(request, attempt), partial, auth ? "auth" : failure.reason().name(),
                failure.options());
        boolean retrying = pass.state.runs().get(request.head().run()) instanceof RunState run && !run.isEnded()
                && (RETRY_WAITS.contains(run.phase().name()) || run.workOrigin() == HookPoint.ON_FAILURE);
        pass.put(retrying ? updated : settled(updated, Status.FAILED), retrying);
    }

    /** 被中断：已收到的部分文字可看。运行先被取消、结果后到时，请求已标为中断，这里只补上文字。 */
    void interrupted(ModelInterrupted interrupted) {
        String command = interrupted.command().value();
        ModelRequest request = pass.book.open.values().stream().filter(ModelRequest.class::isInstance).map(ModelRequest.class::cast)
                .filter(open -> open.last().call().equals(command))
                .filter(open -> open.last().outcome() == Outcome.PENDING || open.status() == Status.INTERRUPTED)
                .findFirst().orElse(null);
        if (request == null) {
            return;
        }
        Attempt last = request.last();
        Attempt attempt = new Attempt(last.call(), last.issuedAt(), interrupted.startedAt(), interrupted.firstTokenAt(),
                last.endedAt() == null ? pass.at : last.endedAt(), Outcome.INTERRUPTED, null, null, null);
        String partial = interrupted.text().isEmpty() ? request.partial() : interrupted.text();
        ModelRequest updated = with(request, Status.INTERRUPTED, replaced(request, attempt), partial, request.failure(),
                interrupted.options());
        pass.put(updated.withHead(request.head().end() == null ? request.head().ended(pass.at) : request.head()), false);
    }

    /** 失败后等待重试的时间，记在刚失败的那次尝试上。 */
    void timer(StartTimer timer) {
        Track track = pass.book.runs.get(timer.run().run());
        ModelRequest request = track == null ? null : waiting(track);
        if (request == null) {
            return;
        }
        Attempt last = request.last();
        Attempt attempt = new Attempt(last.call(), last.issuedAt(), last.startedAt(), last.firstTokenAt(), last.endedAt(), last.outcome(),
                last.reason(), last.detail(), timer.delay());
        pass.put(with(request, request.status(), replaced(request, attempt), request.partial(), request.failure()), true);
    }

    /** 运行结束：正在调用的请求标为中断（留着等迟到的部分文字），等重试的请求标为失败。 */
    void runEnded(RunId run) {
        for (TraceRecord record : List.copyOf(pass.book.open.values())) {
            if (!(record instanceof ModelRequest request) || !request.head().run().equals(run) || request.status() != Status.WAITING) {
                continue;
            }
            if (request.last().outcome() == Outcome.PENDING) {
                Attempt last = request.last();
                Attempt attempt = new Attempt(last.call(), last.issuedAt(), null, null, pass.at, Outcome.INTERRUPTED, null, null, null);
                ModelRequest interrupted = with(request, Status.INTERRUPTED, replaced(request, attempt), request.partial(),
                        request.failure());
                pass.put(interrupted.withHead(request.head().ended(pass.at)), true);
            } else {
                pass.put(settled(request, Status.FAILED), false);
            }
        }
    }

    /** 认证失败（HTTP 401、403）。原始错误可能回显打了码的密钥，接口里不出现。 */
    static boolean auth(String detail) {
        return detail != null && (detail.startsWith("HTTP 401") || detail.startsWith("HTTP 403"));
    }

    /** 正在等这个命令结果的请求。 */
    private ModelRequest inFlight(CommandId command) {
        for (TraceRecord record : pass.book.open.values()) {
            if (record instanceof ModelRequest request && request.last().outcome() == Outcome.PENDING
                    && request.last().call().equals(command.value())) {
                return request;
            }
        }
        return null;
    }

    private Attempt ended(Attempt last, Instant startedAt, Instant firstTokenAt, Outcome outcome, FailureReason reason, String detail) {
        return new Attempt(last.call(), last.issuedAt(), startedAt, firstTokenAt, pass.at, outcome, reason, detail, null);
    }

    private static List<Attempt> replaced(ModelRequest request, Attempt last) {
        List<Attempt> attempts = new ArrayList<>(request.attempts());
        attempts.set(attempts.size() - 1, last);
        return attempts;
    }

    /** 定下来的失败：结束时刻取最后一次尝试结束的时刻，之后只是在等，不是在调用。 */
    private static ModelRequest settled(ModelRequest request, Status status) {
        Instant end = request.last().endedAt();
        return with(request, status, request.attempts(), request.partial(), request.failure()).withHead(request.head().ended(end));
    }

    private static ModelRequest with(ModelRequest request, Status status, List<Attempt> attempts, String partial, String failure) {
        return with(request, status, attempts, partial, failure, null);
    }

    /** {@code options} 为空时保留原来的选项（重试前那次尝试的）。 */
    private static ModelRequest with(ModelRequest request, Status status, List<Attempt> attempts, String partial, String failure,
                                     RequestOptions options) {
        RequestOptions kept = options == null ? request.options() : options;
        return new ModelRequest(request.head(), request.number(), request.purpose(), status, List.copyOf(attempts), request.maxAttempts(),
                kept == null ? null : kept.provider(), request.actualModel(), request.stop(), request.toolCalls(), request.usage(),
                request.total(), request.text(), request.thinking(), partial, kept, failure, request.omittedImages());
    }
}
