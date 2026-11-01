package tantalum.shell.journal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/** 事件日志。写入只在一个线程上进行，{@link #commit()} 成功才算落盘。读取用独立连接，只看到已提交的事件。 */
public final class Journal implements AutoCloseable {

    public static final String FILE = "journal.db";

    private static final String SELECT =
            "SELECT seq, type, version, session_id, run_id, principal, at, payload FROM events ORDER BY seq";

    private final Path file;
    private final EventCodec codec;
    private final Connection writer;
    private final PreparedStatement insert;

    private Journal(Path file, EventCodec codec, Connection writer) throws SQLException {
        this.file = file;
        this.codec = codec;
        this.writer = writer;
        this.insert = writer.prepareStatement(
                "INSERT INTO events (seq, type, version, session_id, run_id, principal, at, payload) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
    }

    public static Journal open(Path dir, EventCodec codec) throws IOException {
        Files.createDirectories(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Connection writer = null;
        try {
            writer = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement statement = writer.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS events (
                          seq        INTEGER PRIMARY KEY,
                          type       TEXT NOT NULL,
                          version    INTEGER NOT NULL,
                          session_id TEXT NOT NULL,
                          run_id     TEXT,
                          principal  TEXT NOT NULL,
                          at         TEXT NOT NULL,
                          payload    TEXT NOT NULL
                        )""");
                checkIntegrity(statement);
            }
            writer.setAutoCommit(false);
            return new Journal(file, codec, writer);
        } catch (SQLException failure) {
            closeQuietly(writer);
            throw new JournalCorruptException("cannot open journal " + file + ": " + failure.getMessage(), failure);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 打开已经失败，这里只是不留下连接。
            }
        }
    }

    private static void checkIntegrity(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            result.next();
            String status = result.getString(1);
            if (!"ok".equals(status)) {
                throw new SQLException("quick_check: " + status);
            }
        }
    }

    public void append(EventEnvelope envelope) throws SQLException {
        Event event = envelope.event();
        insert.setLong(1, envelope.seq());
        insert.setString(2, event.type());
        insert.setInt(3, event.version());
        insert.setString(4, envelope.session().value());
        insert.setString(5, envelope.run() == null ? null : envelope.run().value());
        insert.setString(6, envelope.principal().value());
        insert.setString(7, envelope.at().toString());
        insert.setString(8, codec.encode(event));
        insert.executeUpdate();
    }

    public void commit() throws SQLException {
        writer.commit();
    }

    /** 下一个可用序号。 */
    public long nextSeq() {
        List<EventEnvelope> events = readAll();
        return events.isEmpty() ? 0 : events.getLast().seq() + 1;
    }

    /** 检查序号连续、事件都能解码。有问题时抛异常并指出位置。 */
    public void verify() {
        readAll();
    }

    /** 按序读取全部已提交事件。遇到缺口、未注册类型或损坏内容时报错，不跳过。 */
    public List<EventEnvelope> readAll() {
        List<EventEnvelope> events = new ArrayList<>();
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = reader.createStatement();
                ResultSet rows = statement.executeQuery(SELECT)) {
            long expected = 0;
            while (rows.next()) {
                long seq = rows.getLong(1);
                if (seq != expected) {
                    throw new JournalCorruptException("journal gap: expected seq " + expected + " but found seq " + seq, null);
                }
                events.add(decode(seq, rows));
                expected++;
            }
        } catch (SQLException failure) {
            throw new JournalCorruptException("cannot read journal " + file + ": " + failure.getMessage(), failure);
        }
        return events;
    }

    private EventEnvelope decode(long seq, ResultSet rows) throws SQLException {
        String type = rows.getString(2);
        try {
            Event event = codec.decode(type, rows.getInt(3), rows.getString(8));
            String run = rows.getString(5);
            return new EventEnvelope(seq, Instant.parse(rows.getString(7)), new Principal(rows.getString(6)),
                    new SessionId(rows.getString(4)), run == null ? null : new RunId(run), event);
        } catch (IllegalArgumentException failure) {
            throw new JournalCorruptException("cannot decode event at seq " + seq + " (" + type + "): "
                    + failure.getMessage(), failure);
        }
    }

    /** 可重复调用；未提交的追加回滚。 */
    @Override
    public void close() throws SQLException {
        if (writer.isClosed()) {
            return;
        }
        try (writer) {
            insert.close();
            writer.rollback();
        }
    }
}
