package tantalum.shell.handler;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
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

/** 调用模型。分片只走 {@link Progress}，一次调用只产生一个结算事件。 */
public final class ModelHandler implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(ModelHandler.class);

    /** 接收瞬态进度。必须立即返回。 */
    @FunctionalInterface
    public interface Progress {
        void onChunk(RunRef run, String kind, String text);
    }

    private final ModelClient client;
    private final CallLimiter limiter;
    private final Duration waitTimeout;
    private final Progress progress;

    public ModelHandler(ModelClient client, CallLimiter limiter, Duration waitTimeout, Progress progress) {
        this.client = client;
        this.limiter = limiter;
        this.waitTimeout = waitTimeout;
        this.progress = progress;
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

    private ChunkListener chunks(RunRef run) {
        return new ChunkListener() {
            @Override public void onText(String text) { forward(run, "text", text); }
            @Override public void onReasoning(String text) { forward(run, "reasoning", text); }
        };
    }

    private void forward(RunRef run, String kind, String text) {
        try {
            progress.onChunk(run, kind, text);
        } catch (RuntimeException failure) {
            LOG.warn("progress listener failed", failure);
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
