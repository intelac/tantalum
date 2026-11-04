package tantalum.shell.model;

import java.io.IOException;
import java.util.OptionalLong;

/** 从模型那边读上下文窗口有多大（compaction-threshold T1）。读不到返回空；网络和协议错误抛出，由调用方记日志、保留旧值。 */
@FunctionalInterface
public interface WindowSource {

    /** 不知道怎么读：用配置的值。脚本化模型和测试用它。 */
    WindowSource NONE = OptionalLong::empty;

    OptionalLong read() throws IOException, InterruptedException;

    /** 对方回答了，但答案用不了（HTTP 错误、不是 JSON）。说明是我们自己写的，不含响应体，可以进日志。 */
    final class Unreadable extends IOException {
        public Unreadable(String reason) {
            super(reason);
        }
    }
}
