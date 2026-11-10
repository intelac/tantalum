package tantalum.shell.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.auth.UserStore;
import tantalum.value.id.Principal;

/**
 * 每个用户自己登记的工作区。存在 {@code users.db} 的 {@code projects} 表里，不进 journal：会话的目录已经记在会话自己的账里，
 * 这里只是“哪个目录叫什么名字”。同一个人里路径（按 {@link ProjectPaths#key}）和名字都不能重复。
 * 删除只删登记，会话和电脑上的目录都不动。
 */
public final class Projects implements AutoCloseable {

    /** 名字最长多少字。 */
    public static final int MAX_TITLE = 80;

    /** 登记被拒的原因。 */
    public enum Problem { PATH_INVALID, PATH_TAKEN, TITLE_INVALID, TITLE_TAKEN, NOT_FOUND }

    /** 登记被拒。 */
    public static final class Refused extends Exception {

        private final Problem problem;

        Refused(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final Connection connection;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    private Projects(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /** 不落盘：测试和嵌入使用。 */
    public static Projects inMemory(Clock clock) throws SQLException {
        return create(DriverManager.getConnection("jdbc:sqlite::memory:"), clock);
    }

    /** 和用户表同一个文件。 */
    public static Projects open(Path dataDir, Clock clock) throws IOException, SQLException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(UserStore.FILE);
        if (!Files.exists(file)) {
            PrivateFiles.createFile(file);
        }
        return create(DriverManager.getConnection("jdbc:sqlite:" + file), clock);
    }

    private static Projects create(Connection connection, Clock clock) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS projects (
                      principal TEXT NOT NULL,
                      id        TEXT NOT NULL,
                      title     TEXT NOT NULL,
                      path      TEXT NOT NULL,
                      path_key  TEXT NOT NULL,
                      created   INTEGER NOT NULL,
                      PRIMARY KEY (principal, id),
                      UNIQUE (principal, path_key),
                      UNIQUE (principal, title)
                    )""");
        }
        return new Projects(connection, clock);
    }

    /** 这个人的工作区，加入时间新的在前。 */
    public synchronized List<Project> list(Principal user) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, title, path, created FROM projects WHERE principal = ? ORDER BY created DESC, id")) {
            select.setString(1, user.value());
            List<Project> found = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(row(rows));
                }
            }
            return List.copyOf(found);
        }
    }

    /** 不是这个人的和不存在一样。 */
    public synchronized Optional<Project> find(Principal user, String id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, title, path, created FROM projects WHERE principal = ? AND id = ?")) {
            select.setString(1, user.value());
            select.setString(2, id);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(row(rows)) : Optional.empty();
            }
        }
    }

    /** 登记。{@code title} 为空时用目录最后一段。路径要先由调用方确认电脑上允许（连着的话）。 */
    public synchronized Project add(Principal user, String path, String title) throws SQLException, Refused {
        if (path == null || !ProjectPaths.isAbsolute(path)) {
            throw new Refused(Problem.PATH_INVALID);
        }
        String trimmedPath = path.strip();
        String chosen = title(title == null || title.isBlank() ? ProjectPaths.name(trimmedPath) : title);
        String key = ProjectPaths.key(trimmedPath);
        if (taken(user, "path_key", key, null)) {
            throw new Refused(Problem.PATH_TAKEN);
        }
        if (taken(user, "title", chosen, null)) {
            throw new Refused(Problem.TITLE_TAKEN);
        }
        Project project = new Project(newId(), chosen, trimmedPath, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO projects (principal, id, title, path, path_key, created) VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, user.value());
            insert.setString(2, project.id());
            insert.setString(3, project.title());
            insert.setString(4, project.path());
            insert.setString(5, key);
            insert.setLong(6, project.created().toEpochMilli());
            insert.executeUpdate();
        }
        return project;
    }

    /** 只改名字，路径不能改。 */
    public synchronized Project rename(Principal user, String id, String title) throws SQLException, Refused {
        Project existing = find(user, id).orElseThrow(() -> new Refused(Problem.NOT_FOUND));
        if (title == null || title.isBlank()) {
            throw new Refused(Problem.TITLE_INVALID);
        }
        String chosen = title(title);
        if (taken(user, "title", chosen, id)) {
            throw new Refused(Problem.TITLE_TAKEN);
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE projects SET title = ? WHERE principal = ? AND id = ?")) {
            update.setString(1, chosen);
            update.setString(2, user.value());
            update.setString(3, id);
            update.executeUpdate();
        }
        return new Project(existing.id(), chosen, existing.path(), existing.created());
    }

    /** 只删登记。没有这一项返回 false。 */
    public synchronized boolean delete(Principal user, String id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM projects WHERE principal = ? AND id = ?")) {
            delete.setString(1, user.value());
            delete.setString(2, id);
            return delete.executeUpdate() > 0;
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    /** 去首尾空白；超长的报错而不是截断，免得截出重名。 */
    private static String title(String title) throws Refused {
        String trimmed = title.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_TITLE) {
            throw new Refused(Problem.TITLE_INVALID);
        }
        return trimmed;
    }

    private boolean taken(Principal user, String column, String value, String exceptId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM projects WHERE principal = ? AND " + column + " = ? AND id <> ?")) {
            select.setString(1, user.value());
            select.setString(2, value);
            select.setString(3, exceptId == null ? "" : exceptId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }

    private String newId() {
        StringBuilder id = new StringBuilder("p-");
        for (int i = 0; i < 8; i++) {
            id.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
        }
        return id.toString();
    }

    private static Project row(ResultSet rows) throws SQLException {
        return new Project(rows.getString("id"), rows.getString("title"), rows.getString("path"), Instant.ofEpochMilli(rows.getLong("created")));
    }
}
