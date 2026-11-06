package tantalum.shell.boot;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 按顺序执行关闭的每一步。某一步失败只记日志，后面的照样执行；最后抛出第一个失败。 */
final class Shutdown {

    private static final Logger LOG = LoggerFactory.getLogger(Shutdown.class);

    /** 一步，带名字方便看日志。 */
    record Step(String name, AutoCloseable action) {}

    private Shutdown() {}

    static void run(List<Step> steps) throws IOException {
        Exception first = null;
        for (Step step : steps) {
            LOG.debug("shutdown step: {}", step.name());
            try {
                step.action().close();
            } catch (Exception failure) {
                LOG.error("shutdown step '{}' failed; continuing with the rest", step.name(), failure);
                first = first == null ? failure : first;
            }
        }
        switch (first) {
            case null -> { }
            case IOException io -> throw io;
            case RuntimeException runtime -> throw runtime;
            default -> throw new IllegalStateException(first);
        }
    }
}
