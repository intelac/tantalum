package tantalum.shell.store;

import tantalum.shell.profile.SessionProfile;
import tantalum.value.assignment.OpenAssignment;
import tantalum.value.state.SessionKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.loop.AnswerDeadline;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.TokenUsage;

/**
 * 可重建的索引库：会话元数据、去重和用量。事实来源是会话文件，这里丢了可以从文件重建。
 * 同一批的三张表在一个事务里，所以文件位置不会走在内容前面。
 */
public final class SessionIndex implements AutoCloseable {

    public static final String FILE = "index.db";
    /** 5：用量表多了折算后的量（qagent-align 需求 3）。6：内容搜索的文字表（session-find-pin R3）。7：运行表（run-tree 需求 3）。13：运行表里的起因多了任务的两种，旧的要重算（subagent 需求 10.5）。版本不同时删掉索引，从会话文件重建。 */
    static final int VERSION = 16;
    private static final Logger LOG = LoggerFactory.getLogger(SessionIndex.class);

    /**
     * 一个会话的元数据。只被认领、还没写过事件时 {@code lastActivity} 为 null；没有未结束的运行时 {@code activeRun} 为 null。
     * {@code title} 是第一句的开头；{@code renamed} 是用户改的名字，没改为 null。{@code dir} 是工作目录，没有为 null。
     * {@code kind} 是会话的种类；列不列进侧栏、搜不搜得到照 {@code SessionProfile}。{@code issuer} 只有帮手会话有：把事交代给它的会话。
     */
    public record Session(SessionId session, Principal principal, String title, Instant lastActivity, String runStatus,
                          RunId activeRun, long fileOffset, String dir, String renamed, boolean offRecord, SessionKind kind, SessionId issuer) {

        /** 侧栏显示的名字：改过的优先。 */
        public String shownTitle() {
            return renamed == null ? title : renamed;
        }
    }

    private final Connection connection;

    private SessionIndex(Connection connection) {
        this.connection = connection;
    }

    /** 文件损坏或版本不符时删掉重建，记警告；内容由 {@link IndexCatchUp} 从会话文件补回。 */
    public static SessionIndex open(Path dataDir) throws IOException, SQLException {
        Path file = dataDir.resolve(FILE);
        try {
            return openFile(file);
        } catch (SQLException | IllegalStateException unusable) {
            LOG.warn("index {} is unusable and will be rebuilt from session files: {}", file, unusable.getMessage());
            Files.deleteIfExists(file);
            Files.deleteIfExists(dataDir.resolve(FILE + "-wal"));
            Files.deleteIfExists(dataDir.resolve(FILE + "-shm"));
            return openFile(file);
        }
    }

    private static SessionIndex openFile(Path file) throws IOException, SQLException {
        if (!Files.exists(file)) {
            PrivateFiles.createFile(file);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            prepare(statement);
            return new SessionIndex(connection);
        } catch (SQLException | IllegalStateException failure) {
            connection.close();
            throw failure;
        }
    }

    private static void prepare(Statement statement) throws SQLException {
        int version;
        try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            version = result.getInt(1);
        }
        boolean empty;
        try (ResultSet result = statement.executeQuery("SELECT count(*) FROM sqlite_master")) {
            empty = result.getInt(1) == 0;
        }
        if (!empty && version != VERSION) {
            // 版本不符（等人阶段加了两列，2 → 3）：open() 删掉文件重建，内容由 IndexCatchUp 从会话文件补回
            throw new IllegalStateException("index version " + version + " is not " + VERSION);
        }
        try (ResultSet result = statement.executeQuery("PRAGMA quick_check(1)")) {
            String verdict = result.getString(1);
            if (!"ok".equals(verdict)) {
                throw new IllegalStateException("quick_check: " + verdict);
            }
        }
        // 进程崩溃不丢已提交的事务；断电可能丢最后几个，启动时会从文件补齐。
        statement.execute("PRAGMA journal_mode=WAL");
        statement.execute("PRAGMA synchronous=NORMAL");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                  session       TEXT PRIMARY KEY,
                  principal     TEXT NOT NULL,
                  title         TEXT NOT NULL,
                  last_activity TEXT,
                  run_status    TEXT NOT NULL,
                  active_run    TEXT,
                  file_offset   INTEGER NOT NULL,
                  answer_deadline TEXT,
                  answer_timer    TEXT,
                  dir             TEXT,
                  renamed         TEXT,
                  off_record      INTEGER NOT NULL DEFAULT 0,
                  kind            TEXT NOT NULL DEFAULT 'REGULAR',
                  issuer          TEXT,
                  assignments     TEXT NOT NULL DEFAULT '',
                  outbox          INTEGER NOT NULL DEFAULT 0
                )""");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS submissions (
                  principal  TEXT NOT NULL,
                  request_id TEXT NOT NULL,
                  submission TEXT NOT NULL,
                  PRIMARY KEY (principal, request_id)
                ) WITHOUT ROWID""");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS usage (
                  principal   TEXT NOT NULL,
                  seq         INTEGER NOT NULL,
                  day         TEXT NOT NULL,
                  input       INTEGER NOT NULL,
                  output      INTEGER NOT NULL,
                  cache_read  INTEGER NOT NULL,
                  cache_write INTEGER NOT NULL,
                  counted     INTEGER NOT NULL,
                  PRIMARY KEY (principal, seq)
                ) WITHOUT ROWID""");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS texts (
                  session   TEXT NOT NULL,
                  seq       INTEGER NOT NULL,
                  principal TEXT NOT NULL,
                  role      TEXT NOT NULL,
                  text      TEXT NOT NULL,
                  PRIMARY KEY (session, seq)
                ) WITHOUT ROWID""");
        statement.execute("CREATE INDEX IF NOT EXISTS texts_by_principal ON texts (principal)");
        RunTable.create(statement);
        statement.execute("PRAGMA user_version=" + VERSION);
    }

    /** 认领会话 ID。已被别的用户占有时返回 false。这里是“同一新会话 ID 只属于一个用户”的唯一裁决点。 */
    public synchronized boolean claim(SessionId session, Principal principal) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO sessions (session, principal, title, run_status, file_offset)
                VALUES (?, ?, '', 'idle', 0)""")) {
            insert.setString(1, session.value());
            insert.setString(2, principal.value());
            insert.executeUpdate();
        }
        return session(session).orElseThrow().principal().equals(principal);
    }

    /** 重复写同一批不改变结果，所以重放可以重叠。归属不同的行不写入。 */
    synchronized void write(IndexBatch batch) throws SQLException {
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            writeSessions(batch);
            writeUsage(batch);
            writeSubmissions(batch);
            writeTexts(batch);
            writeSwaps(batch);
            RunTable.write(connection, batch.runs.values());
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    private void writeSessions(IndexBatch batch) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO sessions (session, principal, title, last_activity, run_status, active_run, file_offset, answer_deadline, answer_timer,
                                      dir, renamed, off_record, kind, issuer, assignments, outbox)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULLIF(?, ''), ?13, ?14, ?15, ?16, ?17)
                ON CONFLICT (session) DO UPDATE SET
                  title = CASE WHEN title = '' THEN excluded.title ELSE title END,
                  last_activity = COALESCE(excluded.last_activity, last_activity),
                  dir = COALESCE(dir, excluded.dir),
                  off_record = max(off_record, excluded.off_record),
                  kind = excluded.kind,
                  issuer = COALESCE(issuer, excluded.issuer),
                  assignments = excluded.assignments,
                  outbox = excluded.outbox,
                  renamed = CASE WHEN ?12 IS NULL THEN renamed ELSE excluded.renamed END,
                  run_status = excluded.run_status,
                  active_run = excluded.active_run,
                  file_offset = max(file_offset, excluded.file_offset),
                  answer_deadline = excluded.answer_deadline,
                  answer_timer = excluded.answer_timer
                WHERE principal = excluded.principal""")) {
            for (IndexBatch.Row row : batch.sessions.values()) {
                upsert.setString(1, row.session.value());
                upsert.setString(2, row.principal.value());
                upsert.setString(3, row.title);
                upsert.setString(4, row.lastActivity == null ? null : row.lastActivity.toString());
                upsert.setString(5, row.runStatus);
                upsert.setString(6, row.activeRun == null ? null : row.activeRun.value());
                upsert.setLong(7, row.fileOffset);
                upsert.setString(8, row.answerDeadline == null ? null : row.answerDeadline.toString());
                upsert.setString(9, row.answerTimer == null ? null : row.answerTimer.value());
                upsert.setString(10, row.dir);
                upsert.setString(11, row.rename);
                // 改名为空串（恢复自动标题）要清掉旧名字，没改名要留着：所以另传一次“这一批有没有改名”
                upsert.setString(12, row.rename);
                upsert.setInt(13, row.offRecord ? 1 : 0);
                upsert.setString(14, row.kind.name());
                upsert.setString(15, row.issuer);
                upsert.setString(16, row.assignments);
                upsert.setInt(17, row.outbox);
                upsert.addBatch();
            }
            upsert.executeBatch();
        }
    }

    private void writeUsage(IndexBatch batch) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO usage (principal, seq, day, input, output, cache_read, cache_write, counted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            for (IndexBatch.UsageRow row : batch.usage) {
                TokenUsage amount = row.usage().amount();
                insert.setString(1, row.principal().value());
                insert.setLong(2, row.usage().seq());
                insert.setString(3, row.usage().day().toString());
                insert.setLong(4, amount.input());
                insert.setLong(5, amount.output());
                insert.setLong(6, amount.cacheRead());
                insert.setLong(7, amount.cacheWrite());
                // 折算后的量；没折算时就是四项之和
                insert.setLong(8, amount.counted());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void writeSubmissions(IndexBatch batch) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO submissions (principal, request_id, submission) VALUES (?, ?, ?)")) {
            for (IndexBatch.SubmissionRow row : batch.submissions) {
                insert.setString(1, row.principal().value());
                insert.setString(2, row.submission().request().value());
                insert.setString(3, row.submission().id().value());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void writeTexts(IndexBatch batch) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO texts (session, seq, principal, role, text) VALUES (?, ?, ?, ?, ?)")) {
            for (IndexBatch.TextRow row : batch.texts) {
                insert.setString(1, row.session().value());
                insert.setLong(2, row.seq());
                insert.setString(3, row.principal().value());
                insert.setString(4, row.text().role());
                insert.setString(5, row.text().text());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * 撤下的文字（{@code TextWithdrawn}）：这个会话在那一步之前存的文字里逐字换掉，之后说的不动（忘掉以后又说了一遍的算新的）。
     * 工具结果存的时候把空白并成了一个空格，所以原文并过空白的样子也换一遍。自动标题跟着第一句话换。
     */
    private void writeSwaps(IndexBatch batch) throws SQLException {
        try (PreparedStatement swap = connection.prepareStatement(
                "UPDATE texts SET text = replace(text, ?, ?) WHERE session = ? AND principal = ? AND seq < ? AND instr(text, ?) > 0");
             PreparedStatement retitle = connection.prepareStatement(
                     "UPDATE sessions SET title = ? WHERE session = ? AND principal = ? AND title <> ''");
             PreparedStatement swapTitle = connection.prepareStatement(
                     "UPDATE sessions SET title = replace(title, ?, ?) WHERE session = ? AND principal = ? AND instr(title, ?) > 0")) {
            for (IndexBatch.SwapRow row : batch.swaps) {
                for (tantalum.value.event.TextWithdrawn.Swap each : row.swaps()) {
                    java.util.Set<String> forms = new java.util.LinkedHashSet<>(List.of(each.original()));
                    forms.add(each.original().replaceAll("\\s+", " ").strip());
                    for (String form : forms) {
                        if (form.isBlank()) {
                            continue;
                        }
                        swap.setString(1, form);
                        swap.setString(2, each.replacement());
                        swap.setString(3, row.session().value());
                        swap.setString(4, row.principal().value());
                        swap.setLong(5, row.seq());
                        swap.setString(6, form);
                        swap.executeUpdate();
                    }
                }
                if (row.retitle() != null) {
                    retitle.setString(1, row.retitle());
                    retitle.setString(2, row.session().value());
                    retitle.setString(3, row.principal().value());
                    retitle.executeUpdate();
                } else {
                    // 第一句话已经不在内存里（压缩掉了）：对现有的标题做同样的替换
                    for (tantalum.value.event.TextWithdrawn.Swap each : row.swaps()) {
                        if (each.original().isBlank()) {
                            continue;
                        }
                        swapTitle.setString(1, each.original());
                        swapTitle.setString(2, each.replacement());
                        swapTitle.setString(3, row.session().value());
                        swapTitle.setString(4, row.principal().value());
                        swapTitle.setString(5, each.original());
                        swapTitle.executeUpdate();
                    }
                }
            }
        }
    }

    /** 索引里存的一条文字：会话、序号、角色、全文。 */
    public record TextLine(SessionId session, long seq, String role, String text) {}

    /**
     * 这个人的会话里逐字含有 {@code words} 的每一条（不分 ASCII 大小写；为任务开的会话不算），新的在前，最多 {@code limit} 条。
     * {@code tools} 为真时也找工具调用的结果。
     */
    public synchronized List<TextLine> lines(Principal principal, String words, boolean tools, int limit) throws SQLException {
        String pattern = "%" + words.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<TextLine> lines = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT t.session, t.seq, t.role, t.text FROM texts t
                JOIN sessions s ON s.session = t.session AND s.principal = t.principal
                WHERE t.principal = ? AND s.kind IN """ + SEARCHABLE + """
                 AND t.text LIKE ? ESCAPE '\\' AND (? OR t.role <> 'tool')
                ORDER BY t.seq DESC LIMIT ?""")) {
            select.setString(1, principal.value());
            select.setString(2, pattern);
            select.setBoolean(3, tools);
            select.setInt(4, limit);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    lines.add(new TextLine(new SessionId(result.getString(1)), result.getLong(2), result.getString(3), result.getString(4)));
                }
            }
        }
        return lines;
    }

    /** 这个人某个会话里按序号的一条文字；没有为空。 */
    public synchronized Optional<TextLine> line(Principal principal, SessionId session, long seq) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT role, text FROM texts WHERE principal = ? AND session = ? AND seq = ?")) {
            select.setString(1, principal.value());
            select.setString(2, session.value());
            select.setLong(3, seq);
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? Optional.of(new TextLine(session, seq, result.getString(1), result.getString(2))) : Optional.empty();
            }
        }
    }

    /** 一条内容命中：会话、命中那条的角色和全文。 */
    public record TextHit(SessionId session, String role, String text) {}

    /**
     * 按内容搜这个人的会话（session-find-pin R3）：不分 ASCII 大小写的子串匹配，每个会话取最近一条命中，
     * 按会话最近活动排，最多 {@code limit} 个。文字的主人和会话的主人必须是同一个人。
     */
    public synchronized List<TextHit> search(Principal principal, String query, int limit) throws SQLException {
        return search(principal, query, limit, false);
    }

    /** 同上；{@code tools} 为真时也搜工具调用的结果（插件的 history_search 用；侧栏的搜索不搜）。 */
    public synchronized List<TextHit> search(Principal principal, String query, int limit, boolean tools) throws SQLException {
        String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<TextHit> hits = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT t.session, t.role, t.text, max(t.seq) FROM texts t
                JOIN sessions s ON s.session = t.session AND s.principal = t.principal
                WHERE t.principal = ? AND s.kind IN """ + SEARCHABLE + """
                 AND t.text LIKE ? ESCAPE '\\' AND (? OR t.role <> 'tool')
                GROUP BY t.session ORDER BY s.last_activity DESC, t.session LIMIT ?""")) {
            select.setString(1, principal.value());
            select.setString(2, pattern);
            select.setBoolean(3, tools);
            select.setInt(4, limit);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    hits.add(new TextHit(new SessionId(result.getString(1)), result.getString(2), result.getString(3)));
                }
            }
        }
        return hits;
    }

    /** 这个人的运行，一页（run-tree 需求 4）。游标不合法时抛 {@link IllegalArgumentException}。 */
    public synchronized RunTable.Page runs(Principal principal, RunTable.Filter filter) throws SQLException {
        return RunTable.list(connection, principal, filter);
    }

    /** 当前筛选条件下运行的汇总（observe-page 需求 5）。 */
    public synchronized RunTable.Summary runSummary(Principal principal, RunTable.Filter filter, java.time.ZoneId zone) throws SQLException {
        return RunTable.summary(connection, principal, filter, zone);
    }

    /** 重放完一个文件后写实际大小。文件可能比记录的短（残缺尾部被截掉），所以这里不取最大值。 */
    synchronized void setOffset(SessionId session, Principal principal, long offset) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE sessions SET file_offset = ? WHERE session = ? AND principal = ?")) {
            update.setLong(1, offset);
            update.setString(2, session.value());
            update.setString(3, principal.value());
            update.executeUpdate();
        }
    }

    public synchronized Optional<Session> session(SessionId session) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_SESSION + " WHERE session = ?")) {
            select.setString(1, session.value());
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? Optional.of(session(result)) : Optional.empty();
            }
        }
    }

    public synchronized List<Session> sessions() throws SQLException {
        List<Session> sessions = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT_SESSION + " ORDER BY session");
                ResultSet result = select.executeQuery()) {
            while (result.next()) {
                sessions.add(session(result));
            }
        }
        return sessions;
    }

    /** 写进索引的样子：一行一次交代，四段用制表符隔开（编号、会话、期限、定时器；没设期限时后两段是空的）。四样都不含制表符和换行。 */
    static String encode(List<OpenAssignment> tasks) {
        StringBuilder text = new StringBuilder();
        for (OpenAssignment task : tasks) {
            text.append(task.id()).append('\t').append(task.session().value()).append('\t')
                    .append(task.deadline() == null ? "" : task.deadline().toString()).append('\t')
                    .append(task.timer() == null ? "" : task.timer().value()).append('\n');
        }
        return text.toString();
    }

    /** 这个人的各个会话放出去还没回来的任务数；没有的不在里面。 */
    public synchronized Map<SessionId, Integer> openTaskCounts(Principal principal) throws SQLException {
        Map<SessionId, Integer> counts = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT session, assignments FROM sessions WHERE principal = ? AND assignments <> ''")) {
            select.setString(1, principal.value());
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    counts.put(new SessionId(result.getString(1)), (int) result.getString(2).lines().count());
                }
            }
        }
        return counts;
    }

    /** 信箱不空的会话（delivery）：启动时各发一条重送。 */
    public synchronized List<SessionIndex.Session> withOutbox() throws SQLException {
        List<Session> sessions = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT_SESSION + " WHERE outbox > 0 ORDER BY session");
                ResultSet result = select.executeQuery()) {
            while (result.next()) {
                sessions.add(session(result));
            }
        }
        return sessions;
    }

    /** 放出去还没回来、设了期限的交代：启动时重新起期限定时器（和等用户回答的期限一样，到点按超时结算）。 */
    public synchronized List<tantalum.shell.loop.AnswerDeadline> assignmentDeadlines() throws SQLException {
        List<tantalum.shell.loop.AnswerDeadline> found = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT session, principal, assignments FROM sessions WHERE assignments <> '' ORDER BY session");
                ResultSet result = select.executeQuery()) {
            while (result.next()) {
                SessionId session = new SessionId(result.getString(1));
                Principal principal = new Principal(result.getString(2));
                for (String line : result.getString(3).split("\n")) {
                    String[] parts = line.split("\t", -1);
                    if (parts.length == 4 && !parts[2].isEmpty() && !parts[3].isEmpty()) {
                        // 期限的定时器不属于哪一轮：信封不带运行，内核按“没人等的定时器”认出它是哪个交代的
                        found.add(new tantalum.shell.loop.AnswerDeadline(session, principal, null, Instant.parse(parts[2]), new CommandId(parts[3])));
                    }
                }
            }
        }
        return found;
    }

    /** 等用户回答且有期限的会话：启动时为它们重新起定时器。 */
    public synchronized List<AnswerDeadline> awaitingWithDeadline() throws SQLException {
        List<AnswerDeadline> deadlines = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT session, principal, active_run, answer_deadline, answer_timer FROM sessions WHERE answer_deadline IS NOT NULL ORDER BY session");
                ResultSet result = select.executeQuery()) {
            while (result.next()) {
                deadlines.add(new AnswerDeadline(new SessionId(result.getString(1)), new Principal(result.getString(2)), new RunId(result.getString(3)),
                        Instant.parse(result.getString(4)), new CommandId(result.getString(5))));
            }
        }
        return deadlines;
    }

    /** 有未结束运行的会话：启动时要给这些运行收尾。 */
    public synchronized List<Session> unfinished() throws SQLException {
        List<Session> sessions = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT_SESSION + " WHERE active_run IS NOT NULL ORDER BY session");
                ResultSet result = select.executeQuery()) {
            while (result.next()) {
                sessions.add(session(result));
            }
        }
        return sessions;
    }

    /** 一个会话放出过的任务各自的会话（含已经结束的），按会话号排。 */
    public synchronized List<Session> tasksOf(Principal principal, SessionId parent) throws SQLException {
        List<Session> sessions = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT_SESSION + " WHERE principal = ? AND issuer = ? ORDER BY session")) {
            select.setString(1, principal.value());
            select.setString(2, parent.value());
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    sessions.add(session(result));
                }
            }
        }
        return sessions;
    }

    /** 一个用户的全部会话（含帮手会话、只认领过的）。 */
    public synchronized List<Session> allOf(Principal principal) throws SQLException {
        List<Session> sessions = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT_SESSION + " WHERE principal = ?")) {
            select.setString(1, principal.value());
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    sessions.add(session(result));
                }
            }
        }
        return sessions;
    }

    /** 一个用户写过事件、列进侧栏的会话；只认领还没写过的不算，帮手会话也不算（它们跟着交代它们的会话看）。 */
    public synchronized List<Session> sessions(Principal principal) throws SQLException {
        List<Session> sessions = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT_SESSION + " WHERE principal = ? AND last_activity IS NOT NULL AND kind IN " + LISTED)) {
            select.setString(1, principal.value());
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    sessions.add(session(result));
                }
            }
        }
        return sessions;
    }

    private static final String SELECT_SESSION =
            "SELECT session, principal, title, last_activity, run_status, active_run, file_offset, dir, renamed, off_record, kind, issuer FROM sessions";
    /** 照表：列进侧栏的、搜得到的是哪几种。 */
    private static final String LISTED = SessionProfile.kindsWhere(SessionProfile::listed);
    private static final String SEARCHABLE = SessionProfile.kindsWhere(SessionProfile::searchable);

    private static Session session(ResultSet result) throws SQLException {
        String at = result.getString(4);
        String run = result.getString(6);
        return new Session(new SessionId(result.getString(1)), new Principal(result.getString(2)), result.getString(3),
                at == null ? null : Instant.parse(at), result.getString(5), run == null ? null : new RunId(run), result.getLong(7),
                result.getString(8), result.getString(9), result.getInt(10) != 0, SessionKind.valueOf(result.getString(11)),
                result.getString(12) == null ? null : new SessionId(result.getString(12)));
    }

    public synchronized Optional<SubmissionId> submission(Principal principal, RequestId request) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT submission FROM submissions WHERE principal = ? AND request_id = ?")) {
            select.setString(1, principal.value());
            select.setString(2, request.value());
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? Optional.of(new SubmissionId(result.getString(1))) : Optional.empty();
            }
        }
    }

    /** 该用户的全部用量，按序号排列。 */
    public List<IndexChange.Usage> usage(Principal principal) throws SQLException {
        return usageFrom(principal, Long.MIN_VALUE);
    }

    /** 序号不小于 {@code seq} 的用量，按序号排列。 */
    public synchronized List<IndexChange.Usage> usageFrom(Principal principal, long seq) throws SQLException {
        List<IndexChange.Usage> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT seq, day, input, output, cache_read, cache_write, counted FROM usage WHERE principal = ? AND seq >= ? ORDER BY seq")) {
            select.setString(1, principal.value());
            select.setLong(2, seq);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    rows.add(new IndexChange.Usage(result.getLong(1), LocalDate.parse(result.getString(2)),
                            new TokenUsage(result.getLong(3), result.getLong(4), result.getLong(5), result.getLong(6), result.getLong(7))));
                }
            }
        }
        return rows;
    }

    /** 该用户在 {@code day} 这一天（UTC）的用量合计，没有为零。 */
    public synchronized TokenUsage usageOn(Principal principal, LocalDate day) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT coalesce(sum(input), 0), coalesce(sum(output), 0), coalesce(sum(cache_read), 0), coalesce(sum(cache_write), 0),
                       coalesce(sum(counted), 0)
                FROM usage WHERE principal = ? AND day = ?""")) {
            select.setString(1, principal.value());
            select.setString(2, day.toString());
            try (ResultSet result = select.executeQuery()) {
                result.next();
                return new TokenUsage(result.getLong(1), result.getLong(2), result.getLong(3), result.getLong(4), result.getLong(5));
            }
        }
    }

    /** 序号小于 {@code seq} 的用量，按天累计。 */
    public synchronized Map<LocalDate, TokenUsage> usageBefore(Principal principal, long seq) throws SQLException {
        Map<LocalDate, TokenUsage> totals = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT day, sum(input), sum(output), sum(cache_read), sum(cache_write), sum(counted) FROM usage
                WHERE principal = ? AND seq < ? GROUP BY day""")) {
            select.setString(1, principal.value());
            select.setLong(2, seq);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    totals.put(LocalDate.parse(result.getString(1)),
                            new TokenUsage(result.getLong(2), result.getLong(3), result.getLong(4), result.getLong(5), result.getLong(6)));
                }
            }
        }
        return totals;
    }

    /** 全部请求 ID 的数量，供测试和诊断。 */
    public synchronized long submissionCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM submissions")) {
            return result.getLong(1);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
