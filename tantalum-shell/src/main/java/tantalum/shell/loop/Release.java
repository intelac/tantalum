package tantalum.shell.loop;

import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;

/**
 * cleanroom 处理完一步之后的去处（record-results R3）：结果行落盘后才运行 {@code afterDurable}（放行命令、推送变化），
 * 最后运行 {@code done}（归还放行名额和外部名额）。写入失败时只运行 {@code done}。
 */
@FunctionalInterface
public interface Release {

    void release(EventEnvelope envelope, StepRecord record, Runnable afterDurable, Runnable done);

    /** 不写文件，直接放行。给只测内核和投影、不关心落盘的测试用。 */
    Release IMMEDIATE = (envelope, record, afterDurable, done) -> {
        afterDurable.run();
        done.run();
    };
}
