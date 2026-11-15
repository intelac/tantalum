package tantalum.shell.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.trace.RunRow;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/** 一次事务要写的内容。同一会话的多个事件合并成一行。只由一个线程使用。 */
final class IndexBatch {

    static final class Row {
        final SessionId session;
        final Principal principal;
        String title = "";
        Instant lastActivity;
        String runStatus;
        RunId activeRun;
        Instant answerDeadline;
        CommandId answerTimer;
        /** 会话的工作目录；不知道时为 null。 */
        String dir;
        /** 这一批里最后一次改名；没改为 null，空串表示恢复自动标题。 */
        String rename;
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

    record TextRow(Principal principal, SessionId session, long seq, IndexChange.Text text) {}

    final Map<SessionId, Row> sessions = new LinkedHashMap<>();
    final List<UsageRow> usage = new ArrayList<>();
    final List<SubmissionRow> submissions = new ArrayList<>();
    final List<TextRow> texts = new ArrayList<>();
    /** 同一运行在一批里只留最后一次。 */
    final Map<RunId, RunRow> runs = new LinkedHashMap<>();
    private int events;

    void add(IndexChange change) {
        Row row = sessions.computeIfAbsent(change.session(), id -> new Row(id, change.principal()));
        if (row.title.isEmpty()) {
            row.title = change.title();
        }
        if (change.rename() == null) {
            row.lastActivity = change.at();
        } else {
            row.rename = change.rename();
        }
        if (change.dir() != null) {
            row.dir = change.dir();
        }
        row.runStatus = change.runStatus();
        row.activeRun = change.activeRun();
        row.answerDeadline = change.answerDeadline();
        row.answerTimer = change.answerTimer();
        row.lastSeq = change.seq();
        if (change.usage() != null) {
            usage.add(new UsageRow(change.principal(), change.usage()));
        }
        if (change.submission() != null) {
            submissions.add(new SubmissionRow(change.principal(), change.submission()));
        }
        if (change.text() != null) {
            texts.add(new TextRow(change.principal(), change.session(), change.seq(), change.text()));
        }
        events++;
    }

    void add(RunRow run) {
        runs.put(run.run(), run);
        events++;
    }

    int events() {
        return events;
    }

    boolean isEmpty() {
        return events == 0;
    }
}
