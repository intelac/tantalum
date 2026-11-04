package tantalum.shell.handler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.model.AttachmentMedia;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.event.Event;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.RunRef;
import tantalum.value.model.FailureReason;
import tantalum.value.model.RequestOptions;

/**
 * 调用模型。分片只交给每个运行的 {@link ChunkListener}，一次调用只产生一个结算事件。
 * 结算事件带开始时刻（拿到名额之后）和首 token 时刻（第一个文字、思考或工具输入分片），在这一处量，客户端只管协议。
 */
public final class ModelHandler implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(ModelHandler.class);

    private final ModelClient client;
    private final CallLimiter limiter;
    private final Duration waitTimeout;
    /** 每个运行的分片发给谁。必须立即返回。 */
    private final Function<RunRef, ChunkListener> listeners;
    /** 和信封时刻同源，计时才能和事件时刻比较。 */
    private final Clock clock;
    /** 每个运行从谁的附件目录取内容。 */
    private final Function<RunRef, AttachmentMedia> media;
    /** 模型报上下文超长时调用：窗口可能变小了，再读一次（compaction-threshold T2）。 */
    private final Runnable onOverflow;

    public ModelHandler(ModelClient client, CallLimiter limiter, Duration waitTimeout, Function<RunRef, ChunkListener> listeners,
                        Clock clock) {
        this(client, limiter, waitTimeout, listeners, clock, run -> AttachmentMedia.NONE);
    }

    public ModelHandler(ModelClient client, CallLimiter limiter, Duration waitTimeout, Function<RunRef, ChunkListener> listeners,
                        Clock clock, Function<RunRef, AttachmentMedia> media) {
        this(client, limiter, waitTimeout, listeners, clock, media, () -> {});
    }

    public ModelHandler(ModelClient client, CallLimiter limiter, Duration waitTimeout, Function<RunRef, ChunkListener> listeners,
                        Clock clock, Function<RunRef, AttachmentMedia> media, Runnable onOverflow) {
        this.client = client;
        this.limiter = limiter;
        this.waitTimeout = waitTimeout;
        this.listeners = listeners;
        this.clock = clock;
        this.media = media;
        this.onOverflow = onOverflow;
    }

    @Override
    public Event handle(Command command) {
        CallModel call = (CallModel) command;
        try {
            CallLimiter.Permit permit = limiter.acquire(waitTimeout);
            try {
                // 等名额的时间是排队，不算进这次调用。
                Instant startedAt = clock.instant();
                RequestOptions options = client.options(call.prompt());
                AtomicReference<Instant> firstToken = new AtomicReference<>();
                ModelOutcome outcome;
                try {
                    outcome = client.call(call.prompt(), media.apply(call.run()), chunks(call.run(), firstToken));
                } catch (AttachmentMedia.Unavailable missing) {
                    // 附件不在了或被改过：这次调用失败，不重试，原因写明附件（需求 4.2）。别的会话不受影响。
                    LOG.warn("model call {} failed: {}", call.id().value(), missing.getMessage());
                    return new ModelFailed(call.id(), FailureReason.INVALID_REQUEST, false, null, "", missing.getMessage(),
                            startedAt, null, options);
                }
                if (outcome instanceof ModelOutcome.Failed failed && failed.reason() == FailureReason.CONTEXT_OVERFLOW) {
                    onOverflow.run();
                }
                return toEvent(call, outcome, new Timing(startedAt, firstToken.get(), options));
            } finally {
                permit.close();
            }
        } catch (TimeoutException waiting) {
            return new ModelFailed(call.id(), FailureReason.TIMEOUT, true, null, "",
                    "no model capacity within " + waitTimeout, null, null, null);
        } catch (InterruptedException cancelled) {
            return new ModelInterrupted(call.id(), "", null, null, null);
        }
    }

    /** 分片只用于显示进度：监听者出错只记日志，不影响这次调用。工具输入不推送，只用来量首 token。 */
    private ChunkListener chunks(RunRef run, AtomicReference<Instant> firstToken) {
        ChunkListener listener = listeners.apply(run);
        return new ChunkListener() {
            @Override public void onText(String text) {
                arrived(firstToken);
                safely(() -> listener.onText(text));
            }
            @Override public void onReasoning(String text) {
                arrived(firstToken);
                safely(() -> listener.onReasoning(text));
            }
            @Override public void onToolInput(String fragment) {
                arrived(firstToken);
            }
        };
    }

    /** 只记第一个分片；之后不再读时钟。 */
    private void arrived(AtomicReference<Instant> firstToken) {
        if (firstToken.get() == null) {
            firstToken.compareAndSet(null, clock.instant());
        }
    }

    private static void safely(Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException failure) {
            LOG.warn("chunk listener failed", failure);
        }
    }

    /** 结算事件里只供展示的三项。 */
    private record Timing(Instant startedAt, Instant firstTokenAt, RequestOptions options) {}

    private static Event toEvent(CallModel call, ModelOutcome outcome, Timing timing) {
        return switch (outcome) {
            case ModelOutcome.Replied reply -> new ModelReplied(call.id(), reply.content(), reply.stop(), reply.actualModel(),
                    reply.usage(), timing.startedAt(), timing.firstTokenAt(), timing.options());
            case ModelOutcome.Failed failed -> new ModelFailed(call.id(), failed.reason(), failed.retryable(), failed.retryAfter(),
                    failed.partialText(), failed.detail(), timing.startedAt(), timing.firstTokenAt(), timing.options());
            case ModelOutcome.Interrupted interrupted -> new ModelInterrupted(call.id(), interrupted.text(),
                    timing.startedAt(), timing.firstTokenAt(), timing.options());
        };
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return new ModelFailed(command.id(), FailureReason.UNKNOWN, false, null, "", failure.toString(), null, null, null);
    }
}
