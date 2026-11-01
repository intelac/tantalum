package tantalum.shell.loop;

import com.lmax.disruptor.ExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 环的异常处理器。Disruptor 默认的处理器会把消费者线程杀掉而不留任何信号，环填满后所有提交永远失败。
 * 这里记录并置位 {@code broken}，让入口能返回 503；消费者线程继续处理后面的事件。
 */
final class RingFailure<T> implements ExceptionHandler<T> {

    private static final Logger LOG = LoggerFactory.getLogger(RingFailure.class);

    private final String ring;
    private volatile boolean broken;

    RingFailure(String ring) {
        this.ring = ring;
    }

    boolean broken() {
        return broken;
    }

    @Override
    public void handleEventException(Throwable failure, long sequence, T event) {
        broken = true;
        LOG.error("{} ring consumer failed at sequence {}", ring, sequence, failure);
    }

    @Override
    public void handleOnStartException(Throwable failure) {
        broken = true;
        LOG.error("{} ring consumer failed to start", ring, failure);
    }

    @Override
    public void handleOnShutdownException(Throwable failure) {
        LOG.error("{} ring consumer failed to shut down", ring, failure);
    }
}
