package tantalum.shell.store;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import tantalum.value.id.Principal;
import tantalum.value.model.TokenUsage;

/** 重放一个会话时要的用量：截至某个序号、该用户所有会话的累计。先等写入线程写完，否则读不到刚产生的用量。 */
public final class UsageLedger {

    private final SessionIndex index;
    private final IndexWriter writer;

    public UsageLedger(SessionIndex index, IndexWriter writer) {
        this.index = index;
        this.writer = writer;
    }

    /** 序号小于 {@code seq} 的累计，按天。不能在 cleanroom 上调用。 */
    public Map<LocalDate, TokenUsage> before(Principal principal, long seq) throws SQLException, InterruptedException {
        writer.flush();
        return index.usageBefore(principal, seq);
    }
}
