package tantalum.local.wire;

import java.util.Objects;

/** 服务器→电脑：不要这次调用的结果了（取消或超时），不等回复。电脑上结束它；是命令时结束整个进程树。 */
public record Cancel(String id) implements Message {

    public Cancel {
        Objects.requireNonNull(id, "id");
    }
}
