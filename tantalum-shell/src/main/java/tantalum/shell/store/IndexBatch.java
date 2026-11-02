package tantalum.shell.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/** 一次事务要写的内容。同一会话的多个事件合并成一行。只由一个线程使用。 */
final class IndexBatch {

    static final class Row {
        final SessionId session;
        final Principal principal;
        String title = "";
        Instant lastActivity;
        String runStatus;
        long lastSeq;
        /** 已知落盘到这里，不知道时是 0。写入时只增不减。 */
        long fileOffset;

        Row(SessionId session, Principal principal) {
            this.session = session;
            this.principal = principal;
        }
    }

    record UsageRow(Principal principal, IndexChange.Usage usage) {}

    record SubmissionRow(Principal principal, IndexChange.Submission submission) {}

    final Map<SessionId, Row> sessions = new LinkedHashMap<>();
    final List<UsageRow> usage = new ArrayList<>();
    final List<SubmissionRow> submissions = new ArrayList<>();
    private int events;

    void add(IndexChange change) {
        Row row = sessions.computeIfAbsent(change.session(), id -> new Row(id, change.principal()));
        if (row.title.isEmpty()) {
            row.title = change.title();
        }
        row.lastActivity = change.at();
        row.runStatus = change.runStatus();
        row.lastSeq = change.seq();
        if (change.usage() != null) {
            usage.add(new UsageRow(change.principal(), change.usage()));
        }
        if (change.submission() != null) {
            submissions.add(new SubmissionRow(change.principal(), change.submission()));
        }
        events++;
    }

    int events() {
        return events;
    }

    boolean isEmpty() {
        return events == 0;
    }
}
