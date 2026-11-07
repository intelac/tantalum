package tantalum.shell.loop;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.Submitted;
import tantalum.value.id.SubmissionId;

/**
 * 提交的回执（one-line-journal J4）：提交那一步落盘后完成，接口这时才回“已接受”。没写进去（写失败、进程停止、
 * 根本没交给内核）时以失败完成。完成后从表里删掉，所以表里只有还在途的提交。
 */
public final class Receipts {

    /** 没有落盘就走完了管线。 */
    public static final class NotSaved extends RuntimeException {
        NotSaved(SubmissionId submission) {
            super("submission " + submission.value() + " was not saved");
        }
    }

    private final Map<SubmissionId, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    /** 发布之前登记，免得事件落盘比登记还早。 */
    CompletableFuture<Void> expect(SubmissionId submission) {
        return pending.computeIfAbsent(submission, ignored -> new CompletableFuture<>());
    }

    /** 重复提交：原提交还在途时等它的回执，否则它早已落盘（去重表里的记录来自索引或已放行的提交）。 */
    CompletableFuture<Void> of(SubmissionId submission) {
        CompletableFuture<Void> found = pending.get(submission);
        return found != null ? found : CompletableFuture.completedFuture(null);
    }

    /** 发布失败，事件不会到内核：等着这张回执的（重复提交）也得到失败，不会永远等（review-fixes 需求 1.2）。 */
    void fail(SubmissionId submission) {
        CompletableFuture<Void> receipt = pending.remove(submission);
        if (receipt != null) {
            receipt.completeExceptionally(new NotSaved(submission));
        }
    }

    /** 关机或管线停住：之后不会再有落盘，在途的回执全部以失败完成（需求 1.3）。 */
    public void failAll() {
        for (SubmissionId submission : List.copyOf(pending.keySet())) {
            fail(submission);
        }
    }

    /** 这一步已落盘。 */
    public void durable(EventEnvelope envelope) {
        if (envelope.event() instanceof Submitted submitted) {
            CompletableFuture<Void> receipt = pending.remove(submitted.submission());
            if (receipt != null) {
                receipt.complete(null);
            }
        }
    }

    /** 这个事件走完了管线。已经落盘的回执不受影响；没落盘的以失败完成。 */
    public void processed(EventEnvelope envelope) {
        if (envelope.event() instanceof Submitted submitted) {
            CompletableFuture<Void> receipt = pending.remove(submitted.submission());
            if (receipt != null) {
                receipt.completeExceptionally(new NotSaved(submitted.submission()));
            }
        }
    }

    int size() {
        return pending.size();
    }
}
