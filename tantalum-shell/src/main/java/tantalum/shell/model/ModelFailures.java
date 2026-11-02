package tantalum.shell.model;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import tantalum.shell.json.JsonText;
import tantalum.value.model.FailureReason;
import tantalum.value.model.ToolCallBlock;

/** 两个供应商共用的失败分类和流结束判断。客户端只负责各自的协议：请求怎么组、事件怎么解析。 */
final class ModelFailures {

    private ModelFailures() {}

    /** HTTP 错误：先认超长，再按状态码分为限流、服务端错误和请求无效。{@code retryAfter} 是响应头原文，可为 null。 */
    static ModelOutcome.Failed http(int status, String message, String retryAfter, String partial, OverflowRules overflow) {
        String detail = "HTTP " + status + ": " + message;
        Duration wait = retryAfter == null ? null : seconds(retryAfter);
        if (overflow.matches(status, message)) {
            return new ModelOutcome.Failed(FailureReason.CONTEXT_OVERFLOW, false, null, partial, detail);
        }
        if (status == 429) {
            return new ModelOutcome.Failed(FailureReason.RATE_LIMITED, true, wait, partial, detail);
        }
        if (status >= 500) {
            return new ModelOutcome.Failed(FailureReason.SERVER_ERROR, true, wait, partial, detail);
        }
        return new ModelOutcome.Failed(FailureReason.INVALID_REQUEST, false, null, partial, detail);
    }

    static ModelOutcome.Failed timeout(String partial) {
        return new ModelOutcome.Failed(FailureReason.TIMEOUT, true, null, partial, "model stream timed out");
    }

    static ModelOutcome.Failed connection(String partial, String detail) {
        return new ModelOutcome.Failed(FailureReason.CONNECTION_ERROR, true, null, partial, detail);
    }

    /**
     * 流读完以后先看这一步：被中断、看门狗到时，或者没等到结束标记。都不是时返回 null，由客户端组装回复。
     * {@code complete} 表示收到了协议里的结束事件。
     */
    static ModelOutcome afterStream(String partial, StreamWatchdog watchdog, boolean complete) {
        if (Thread.currentThread().isInterrupted()) {
            return new ModelOutcome.Interrupted(partial);
        }
        if (watchdog.expired()) {
            return timeout(partial);
        }
        if (!complete) {
            return connection(partial, "stream ended early");
        }
        return null;
    }

    /** 线程被中断，或者底层 I/O 因中断而失败。读超时也是 {@link InterruptedIOException}，但它不是中断。 */
    static boolean isInterrupt(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException
                    || cause instanceof InterruptedIOException && !(cause instanceof SocketTimeoutException)) {
                return true;
            }
        }
        return false;
    }

    /** 工具调用块。参数原文为空时按空对象解析，解析不了时 {@code arguments} 为 null，原文照样保留。 */
    static ToolCallBlock toolCall(String id, String name, String raw) {
        return new ToolCallBlock(id, name, raw, JsonText.parseOrNull(raw.isBlank() ? "{}" : raw));
    }

    /** {@code Retry-After} 只认秒数；HTTP 日期格式按没有处理。 */
    static Duration seconds(String value) {
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException notSeconds) {
            return null;
        }
    }
}
