package tantalum.shell.net;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** 截获 {@code tantalum.shell} 下的日志，检查联网日志里没有路径、正文和凭据（web-tools NFR Security）。 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger = (Logger) LoggerFactory.getLogger("tantalum.shell");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture() {
        appender.start();
        logger.addAppender(appender);
    }

    /** 每条日志格式化后的文字，含异常的消息。 */
    public List<String> lines() {
        synchronized (appender.list) {
            return appender.list.stream()
                    .map(event -> event.getLevel() + " " + event.getFormattedMessage()
                            + (event.getThrowableProxy() == null ? "" : " " + event.getThrowableProxy().getMessage()))
                    .toList();
        }
    }

    public String all() {
        return String.join("\n", lines());
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
