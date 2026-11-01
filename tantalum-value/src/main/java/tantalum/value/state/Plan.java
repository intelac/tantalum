package tantalum.value.state;

import tantalum.value.model.Message;

/**
 * 一次压缩的切分结果。头部是视图最前面的 {@code headMessages} 条消息，其余是保留段；
 * {@code request} 是头部里最新一条用户亲笔消息，没有则为 null。单元数和估算只用于事实和日志。
 */
public record Plan(int headMessages, int headUnits, int keptUnits, long headEstimate, Message request) {

    public Plan {
        if (headMessages <= 0 || headUnits <= 0 || keptUnits < 0 || headEstimate < 0) {
            throw new IllegalArgumentException("plan must describe a non-empty head");
        }
    }
}
