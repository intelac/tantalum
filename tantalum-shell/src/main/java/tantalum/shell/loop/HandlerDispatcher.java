package tantalum.shell.loop;

import com.lmax.disruptor.EventHandler;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.handler.Handler;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.Command;
import tantalum.value.event.Event;

/** 输出环的消费者，只分发：每条命令一个虚拟线程，慢 Handler 不占消费者线程。 */
public final class HandlerDispatcher implements EventHandler<OutputRing.Slot> {

    private static final Logger LOG = LoggerFactory.getLogger(HandlerDispatcher.class);

    private final Map<Class<? extends Command>, Handler> handlers;
    private final InFlightCommands inFlight;
    private final ResultPublisher results;
    /** 关闭中不再启动新的 Handler：这些运行在文件里没结束，下次启动时以“因重启中断”收尾，不会重复调用。 */
    private volatile boolean closing;

    public HandlerDispatcher(Map<Class<? extends Command>, Handler> handlers, InFlightCommands inFlight, ResultPublisher results) {
        this.handlers = Map.copyOf(handlers);
        this.inFlight = inFlight;
        this.results = results;
    }

    @Override
    public void onEvent(OutputRing.Slot slot, long sequence, boolean endOfBatch) {
        Command command = slot.command;
        slot.command = null;
        if (command instanceof AbortCommands abort) {
            inFlight.abort(abort.commands());
            return;
        }
        if (closing) {
            LOG.info("closing: not starting {} for run {}", command.getClass().getSimpleName(), command.run().run().value());
            return;
        }
        Handler handler = handlers.get(command.getClass());
        if (handler == null) {
            results.reportDropped(command, "no handler for " + command.getClass().getSimpleName());
            return;
        }
        Thread thread = Thread.ofVirtual().unstarted(() -> run(handler, command));
        inFlight.register(command.id(), thread);
        thread.start();
    }

    public void close() {
        closing = true;
    }

    private void run(Handler handler, Command command) {
        try {
            Event result = execute(handler, command);
            if (inFlight.isShutdownAbort(command.id())) {
                LOG.info("not recording the result of {} interrupted by shutdown; restart closes run {}",
                        command.getClass().getSimpleName(), command.run().run().value());
            } else if (result != null) {
                results.publish(command, result);
            }
        } catch (Throwable failure) {
            LOG.error("handler failure path failed for command {}", command.id().value(), failure);
            results.reportDropped(command, failure.toString());
        } finally {
            inFlight.complete(command.id());
        }
    }

    /** {@code Error}（栈溢出、断言）也要变成结果，否则运行只能等到超时甚至永远挂住。 */
    private static Event execute(Handler handler, Command command) {
        try {
            return handler.handle(command);
        } catch (Exception failure) {
            return handler.failed(command, failure);
        } catch (Error failure) {
            return handler.failed(command, new IllegalStateException(failure.toString(), failure));
        }
    }
}
