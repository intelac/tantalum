package tantalum.shell.handler;

import tantalum.value.text.Failures;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.model.AttachmentMedia;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.route.Route;
import tantalum.shell.route.Routes;
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

    /** 一条路线：客户端、它自己的并发上限、等名额最多多久、超长时让它重读列表。 */
    private record Lane(ModelClient client, CallLimiter limiter, Duration waitTimeout, Runnable onOverflow) {}

    /** 按 prompt 里的路线名找；没有选择时是 null，用默认路线。找不到时为 null。 */
    private final Function<String, Lane> lanes;
    /** 每个运行的分片发给谁。必须立即返回。 */
    private final Function<RunRef, ChunkListener> listeners;
    /** 和信封时刻同源，计时才能和事件时刻比较。 */
    private final Clock clock;
    /** 每个运行从谁的附件目录取内容。 */
    private final Function<RunRef, AttachmentMedia> media;

    public ModelHandler(ModelClient client, CallLimiter limiter, Duration waitTimeout, Function<RunRef, ChunkListener> listeners,
                        Clock clock) {
        this(client, limiter, waitTimeout, listeners, clock, run -> AttachmentMedia.NONE);
    }

    public ModelHandler(ModelClient client, CallLimiter limiter, Duration waitTimeout, Function<RunRef, ChunkListener> listeners,
                        Clock clock, Function<RunRef, AttachmentMedia> media) {
        this(client, limiter, waitTimeout, listeners, clock, media, () -> {});
    }

    /** 只有一个客户端（测试）：不管 prompt 选的哪条路线都用它。{@code onOverflow} 在模型报上下文超长时调用。 */
    public ModelHandler(ModelClient client, CallLimiter limiter, Duration waitTimeout, Function<RunRef, ChunkListener> listeners,
                        Clock clock, Function<RunRef, AttachmentMedia> media, Runnable onOverflow) {
        Lane lane = new Lane(client, limiter, waitTimeout, onOverflow);
        this.lanes = route -> lane;
        this.listeners = listeners;
        this.clock = clock;
        this.media = media;
    }

    /**
     * 按路线调用：{@code prompt.model().route()} 指哪条就用哪条的客户端，
     * 每条路线自己的并发上限，一条路线忙不影响别的；等名额最多等这条路线的首块超时。没有选择的 prompt 用默认路线。
     * 超长时只让这一条路线重读列表。
     */
    public ModelHandler(Routes routes, Function<RunRef, ChunkListener> listeners, Clock clock, Function<RunRef, AttachmentMedia> media) {
        Map<String, Lane> byRoute = new HashMap<>();
        for (Route route : routes.all()) {
            byRoute.put(route.name(), new Lane(route.client(), new CallLimiter(route.settings().concurrency()),
                    route.settings().firstChunkTimeout(), () -> routes.refreshSoon(route.name())));
        }
        String fallback = routes.defaultRoute();
        this.lanes = route -> byRoute.get(route == null ? fallback : route);
        this.listeners = listeners;
        this.clock = clock;
        this.media = media;
    }

    @Override
    public Event handle(Command command) {
        CallModel call = (CallModel) command;
        String route = call.prompt().model() == null ? null : call.prompt().model().route();
        Lane lane = lanes.apply(route);
        if (lane == null) {
            // 配置里删掉了这条路线，或者它的密钥变量没设：这次调用失败，不重试
            return new ModelFailed(call.id(), FailureReason.INVALID_REQUEST, false, null, "",
                    "the model route " + route + " is not available", null, null, null);
        }
        ModelClient client = lane.client();
        try {
            CallLimiter.Permit permit = lane.limiter().acquire(lane.waitTimeout());
            try {
                // 等名额的时间是排队，不算进这次调用。
                Instant startedAt = clock.instant();
                RequestOptions options = client.options(call.prompt());
                AtomicReference<Instant> firstToken = new AtomicReference<>();
                ModelOutcome outcome;
                try {
                    outcome = client.call(call.prompt(), media.apply(call.run()), chunks(call.run(), firstToken));
                } catch (AttachmentMedia.Unavailable missing) {
                    // 附件不在了或被改过：这次调用失败，不重试，原因写明附件。别的会话不受影响。
                    LOG.warn("model call {} failed: {}", call.id().value(), missing.getMessage());
                    return new ModelFailed(call.id(), FailureReason.INVALID_REQUEST, false, null, "", missing.getMessage(),
                            startedAt, null, options);
                }
                if (outcome instanceof ModelOutcome.Failed failed && failed.reason() == FailureReason.CONTEXT_OVERFLOW) {
                    lane.onOverflow().run();
                }
                return toEvent(call, outcome, new Timing(startedAt, firstToken.get(), options));
            } finally {
                permit.close();
            }
        } catch (TimeoutException waiting) {
            return new ModelFailed(call.id(), FailureReason.TIMEOUT, true, null, "",
                    "no model capacity within " + lane.waitTimeout(), null, null, null);
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

    /** 事件进 journal 和推送，只记异常类名；完整异常记日志。 */
    @Override
    public Event failed(Command command, Exception failure) {
        LOG.error("model call {} failed outside the client", command.id().value(), failure);
        return new ModelFailed(command.id(), FailureReason.UNKNOWN, false, null, "", Failures.describe(failure), null, null, null);
    }
}
