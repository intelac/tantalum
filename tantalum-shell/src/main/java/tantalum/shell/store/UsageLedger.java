package tantalum.shell.store;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import tantalum.value.id.Principal;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.PrincipalState;

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

    /** 从 {@code seq} 开始按序号往后走的累计，重放一个会话时用，不用每个事件查一次。 */
    public Cursor cursor(Principal principal, long seq) throws SQLException, InterruptedException {
        writer.flush();
        return new Cursor(new PrincipalState(principal, index.usageBefore(principal, seq)), index.usageFrom(principal, seq));
    }

    /** 只能往后走。 */
    public static final class Cursor {
        private final List<IndexChange.Usage> rows;
        private PrincipalState total;
        private int next;

        Cursor(PrincipalState start, List<IndexChange.Usage> rows) {
            this.total = start;
            this.rows = rows;
        }

        /** 序号小于 {@code seq} 的累计。 */
        public PrincipalState before(long seq) {
            while (next < rows.size() && rows.get(next).seq() < seq) {
                total = total.add(rows.get(next).day(), rows.get(next).amount());
                next++;
            }
            return total;
        }
    }
}
