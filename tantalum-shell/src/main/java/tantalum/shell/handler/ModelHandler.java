package tantalum.shell.handler;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/** 调用模型。分片只交给每个运行的 {@link ChunkListener}，一次调用只产生一个结算事件。 */
public final class ModelHandler implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(ModelHandler.class);

    private final ModelClient client;
    private final CallLimiter limiter;
    private final Duration waitTimeout;
    /** 每个运行的分片发给谁。必须立即返回。 */
    private final Function<RunRef, ChunkListener> listeners;

    public ModelHandler(ModelClient client, CallLimiter limiter, Duration waitTimeout, Function<RunRef, ChunkListener> listeners) {
        this.client = client;
        this.limiter = limiter;
        this.waitTimeout = waitTimeout;
        this.listeners = listeners;
    }

    @Override
    public Event handle(Command command) {
        CallModel call = (CallModel) command;
        try {
            CallLimiter.Permit permit = limiter.acquire(waitTimeout);
            try {
                return toEvent(call, client.call(call.prompt(), chunks(call.run())));
            } finally {
                permit.close();
            }
        } catch (TimeoutException waiting) {
            return new ModelFailed(call.id(), FailureReason.TIMEOUT, true, null, "",
                    "no model capacity within " + waitTimeout);
        } catch (InterruptedException cancelled) {
            return new ModelInterrupted(call.id(), "");
        }
    }

    /** 分片只用于显示进度：监听者出错只记日志，不影响这次调用。 */
    private ChunkListener chunks(RunRef run) {
        ChunkListener listener = listeners.apply(run);
        return new ChunkListener() {
            @Override public void onText(String text) { safely(() -> listener.onText(text)); }
            @Override public void onReasoning(String text) { safely(() -> listener.onReasoning(text)); }
        };
    }

    private static void safely(Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException failure) {
            LOG.warn("chunk listener failed", failure);
        }
    }

    private static Event toEvent(CallModel call, ModelOutcome outcome) {
        return switch (outcome) {
            case ModelOutcome.Replied reply ->
                    new ModelReplied(call.id(), reply.content(), reply.stop(), reply.actualModel(), reply.usage());
            case ModelOutcome.Failed failed -> new ModelFailed(call.id(), failed.reason(), failed.retryable(),
                    failed.retryAfter(), failed.partialText(), failed.detail());
            case ModelOutcome.Interrupted interrupted -> new ModelInterrupted(call.id(), interrupted.text());
        };
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return new ModelFailed(command.id(), FailureReason.UNKNOWN, false, null, "", failure.toString());
    }
}
