package tantalum.shell.store;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tantalum.shell.trace.RunRow;
import tantalum.shell.trace.Tally;
import tantalum.shell.trace.TraceRecord.RunHead;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.EndReason;

/**
 * 索引库里的运行表（run-tree 需求 3、4）：每次运行一行，加每个工具一行。时刻存成毫秒数，按数字排序。
 * 只由 {@link SessionIndex} 在它的锁里调用。
 */
public final class RunTable {

    /** 怎么排：都从大到小。 */
    public enum Sort { STARTED, MS, TOKENS }

    /** 筛选条件，为 null 的不筛。{@code ends} 里的 {@code RUNNING} 表示还没结束。 */
    public record Filter(Instant from, Instant to, Set<String> ends, Boolean toolErrors, String tool, String model, SessionId session,
                  Sort sort, String after, int limit) {}

    /** 列表里的一行：运行和会话显示的名字。 */
    public record Listed(RunRow row, String title) {}

    /** {@code next} 是下一页的游标，没有下一页时为 null。 */
    public record Page(List<Listed> runs, String next) {}

    /** 一天：各结束原因（还没结束的记作 {@value #RUNNING}）的运行数和 token 合计。 */
    public record Day(LocalDate day, Map<String, Integer> ends, long tokens) {}

    /**
     * 一段时间的汇总（observe-page 需求 5）：运行数、各结束原因的个数、有工具错误的运行数、token 合计、
     * 已结束运行耗时的中位数和 P90（没有已结束的为 null）、按天（没有运行的天不给）、筛选用的工具和模型清单（不受工具、模型筛选影响）。
     */
    public record Summary(int runs, Map<String, Integer> ends, int toolErrorRuns, long tokens, Long p50Ms, Long p90Ms, List<Day> days,
                          List<String> tools, List<String> models) {}

    public static final String RUNNING = "RUNNING";

    private RunTable() {}

    static void create(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS runs (
                  principal TEXT NOT NULL, session TEXT NOT NULL, run TEXT NOT NULL, turn INTEGER, trigger TEXT NOT NULL,
                  started INTEGER NOT NULL, ended INTEGER, end_reason TEXT, prompt TEXT,
                  input INTEGER NOT NULL, output INTEGER NOT NULL, cache_read INTEGER NOT NULL, cache_write INTEGER NOT NULL,
                  tokens INTEGER NOT NULL, summary_input INTEGER NOT NULL, summary_output INTEGER NOT NULL,
                  summary_cache_read INTEGER NOT NULL, summary_cache_write INTEGER NOT NULL, context INTEGER,
                  requests INTEGER NOT NULL, attempts INTEGER NOT NULL, retries INTEGER NOT NULL, model_ms INTEGER, ttft_ms INTEGER,
                  tool_calls INTEGER NOT NULL, tool_errors INTEGER NOT NULL, tool_ms INTEGER, hooks INTEGER NOT NULL,
                  hook_errors INTEGER NOT NULL, models TEXT NOT NULL, ms INTEGER,
                  PRIMARY KEY (session, run)
                ) WITHOUT ROWID""");
        statement.execute("CREATE INDEX IF NOT EXISTS runs_by_principal ON runs (principal, started DESC)");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS run_tools (
                  session TEXT NOT NULL, run TEXT NOT NULL, position INTEGER NOT NULL, name TEXT NOT NULL,
                  calls INTEGER NOT NULL, errors INTEGER NOT NULL,
                  PRIMARY KEY (session, run, name)
                ) WITHOUT ROWID""");
        statement.execute("CREATE INDEX IF NOT EXISTS run_tools_by_name ON run_tools (name)");
    }

    /** 同一运行写多次时后写的覆盖先写的；运行属于别人的会话（ID 被占）时不写。 */
    static void write(Connection connection, Collection<RunRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO runs (principal, session, run, turn, trigger, started, ended, end_reason, prompt,
                                  input, output, cache_read, cache_write, tokens, summary_input, summary_output, summary_cache_read,
                                  summary_cache_write, context, requests, attempts, retries, model_ms, ttft_ms, tool_calls, tool_errors,
                                  tool_ms, hooks, hook_errors, models, ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session, run) DO UPDATE SET
                  turn = excluded.turn, trigger = excluded.trigger, started = excluded.started, ended = excluded.ended,
                  end_reason = excluded.end_reason, prompt = excluded.prompt, input = excluded.input, output = excluded.output,
                  cache_read = excluded.cache_read, cache_write = excluded.cache_write, tokens = excluded.tokens,
                  summary_input = excluded.summary_input, summary_output = excluded.summary_output,
                  summary_cache_read = excluded.summary_cache_read, summary_cache_write = excluded.summary_cache_write,
                  context = excluded.context, requests = excluded.requests, attempts = excluded.attempts, retries = excluded.retries,
                  model_ms = excluded.model_ms, ttft_ms = excluded.ttft_ms, tool_calls = excluded.tool_calls,
                  tool_errors = excluded.tool_errors, tool_ms = excluded.tool_ms, hooks = excluded.hooks,
                  hook_errors = excluded.hook_errors, models = excluded.models, ms = excluded.ms
                WHERE principal = excluded.principal""");
                PreparedStatement clear = connection.prepareStatement("DELETE FROM run_tools WHERE session = ? AND run = ?");
                PreparedStatement tool = connection.prepareStatement(
                        "INSERT INTO run_tools (session, run, position, name, calls, errors) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (RunRow row : rows) {
                Tally tally = row.tally();
                int i = 1;
                upsert.setString(i++, row.principal().value());
                upsert.setString(i++, row.session().value());
                upsert.setString(i++, row.run().value());
                setInt(upsert, i++, row.turn());
                upsert.setString(i++, row.trigger().name());
                upsert.setLong(i++, row.start().toEpochMilli());
                setLong(upsert, i++, row.end() == null ? null : row.end().toEpochMilli());
                upsert.setString(i++, row.endReason() == null ? null : row.endReason().name());
                upsert.setString(i++, row.prompt());
                TokenUsage tokens = tally.tokens();
                upsert.setLong(i++, tokens.input());
                upsert.setLong(i++, tokens.output());
                upsert.setLong(i++, tokens.cacheRead());
                upsert.setLong(i++, tokens.cacheWrite());
                upsert.setLong(i++, tokens.input() + tokens.output() + tokens.cacheRead() + tokens.cacheWrite());
                TokenUsage summary = tally.summaryTokens();
                upsert.setLong(i++, summary.input());
                upsert.setLong(i++, summary.output());
                upsert.setLong(i++, summary.cacheRead());
                upsert.setLong(i++, summary.cacheWrite());
                setLong(upsert, i++, tally.context());
                upsert.setInt(i++, tally.requests());
                upsert.setInt(i++, tally.attempts());
                upsert.setInt(i++, tally.retries());
                setLong(upsert, i++, tally.modelMs());
                setLong(upsert, i++, tally.ttftMs());
                upsert.setInt(i++, tally.toolCalls());
                upsert.setInt(i++, tally.toolErrors());
                setLong(upsert, i++, tally.toolMs());
                upsert.setInt(i++, tally.hooks());
                upsert.setInt(i++, tally.hookErrors());
                upsert.setString(i++, String.join("\n", tally.models()));
                setLong(upsert, i, tally.ms());
                if (upsert.executeUpdate() == 0) {
                    continue;
                }
                clear.setString(1, row.session().value());
                clear.setString(2, row.run().value());
                clear.executeUpdate();
                int position = 0;
                for (Tally.ToolCount count : tally.tools()) {
                    tool.setString(1, row.session().value());
                    tool.setString(2, row.run().value());
                    tool.setInt(3, position++);
                    tool.setString(4, count.name());
                    tool.setInt(5, count.calls());
                    tool.setInt(6, count.errors());
                    tool.addBatch();
                }
                tool.executeBatch();
            }
        }
    }

    /** 这个人的运行，一页；会话要是这个人的。 */
    static Page list(Connection connection, Principal principal, Filter filter) throws SQLException {
        String key = switch (filter.sort()) {
            case STARTED -> "r.started";
            case MS -> "COALESCE(r.ms, -1)";
            case TOKENS -> "r.tokens";
        };
        StringBuilder sql = new StringBuilder("SELECT r.*, ").append(key).append(" AS sort_key, COALESCE(s.renamed, s.title) AS shown")
                .append(" FROM runs r JOIN sessions s ON s.session = r.session AND s.principal = r.principal WHERE r.principal = ?");
        List<Object> args = new ArrayList<>();
        args.add(principal.value());
        where(filter, true, sql, args);
        if (filter.after() != null) {
            Cursor cursor = Cursor.decode(filter.after());
            sql.append(" AND (").append(key).append(" < ? OR (").append(key).append(" = ? AND (r.session, r.run) < (?, ?)))");
            args.add(cursor.value());
            args.add(cursor.value());
            args.add(cursor.session());
            args.add(cursor.run());
        }
        sql.append(" ORDER BY sort_key DESC, r.session DESC, r.run DESC LIMIT ?");
        args.add(filter.limit() + 1);
        List<Listed> listed = new ArrayList<>();
        List<Long> keys = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                select.setObject(i + 1, args.get(i));
            }
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    listed.add(new Listed(row(result), result.getString("shown")));
                    keys.add(result.getLong("sort_key"));
                }
            }
        }
        String next = null;
        if (listed.size() > filter.limit()) {
            listed = new ArrayList<>(listed.subList(0, filter.limit()));
            RunRow last = listed.getLast().row();
            next = new Cursor(keys.get(filter.limit() - 1), last.session().value(), last.run().value()).encode();
        }
        return new Page(withTools(connection, listed), next);
    }

    /** 当前筛选条件下的汇总；游标、排序、条数不管。天按 {@code zone} 划分。 */
    static Summary summary(Connection connection, Principal principal, Filter filter, ZoneId zone) throws SQLException {
        String from = " FROM runs r JOIN sessions s ON s.session = r.session AND s.principal = r.principal WHERE r.principal = ?";
        StringBuilder sql = new StringBuilder("SELECT r.started, r.ended, r.end_reason, r.tokens, r.ms, r.tool_errors").append(from);
        List<Object> args = new ArrayList<>();
        args.add(principal.value());
        where(filter, true, sql, args);
        int runs = 0;
        int toolErrorRuns = 0;
        long tokens = 0;
        Map<String, Integer> ends = new TreeMap<>();
        Map<LocalDate, Map<String, Integer>> dayEnds = new TreeMap<>();
        Map<LocalDate, Long> dayTokens = new TreeMap<>();
        List<Long> durations = new ArrayList<>();
        try (PreparedStatement select = prepare(connection, sql.toString(), args); ResultSet result = select.executeQuery()) {
            while (result.next()) {
                runs++;
                String end = result.getString(3);
                result.getLong(2);
                boolean ended = !result.wasNull();
                String key = ended && end != null ? end : RUNNING;
                ends.merge(key, 1, Integer::sum);
                long runTokens = result.getLong(4);
                tokens += runTokens;
                long ms = result.getLong(5);
                if (ended && !result.wasNull()) {
                    durations.add(ms);
                }
                if (result.getInt(6) > 0) {
                    toolErrorRuns++;
                }
                LocalDate day = Instant.ofEpochMilli(result.getLong(1)).atZone(zone).toLocalDate();
                dayEnds.computeIfAbsent(day, ignored -> new TreeMap<>()).merge(key, 1, Integer::sum);
                dayTokens.merge(day, runTokens, Long::sum);
            }
        }
        durations.sort(null);
        List<Day> days = new ArrayList<>();
        dayEnds.forEach((day, counts) -> days.add(new Day(day, counts, dayTokens.get(day))));
        return new Summary(runs, ends, toolErrorRuns, tokens, rank(durations, 50), rank(durations, 90), days,
                facet(connection, "SELECT DISTINCT t.name FROM run_tools t JOIN runs r ON r.session = t.session AND r.run = t.run"
                        + " JOIN sessions s ON s.session = r.session AND s.principal = r.principal WHERE r.principal = ?", principal, filter, false),
                facet(connection, "SELECT DISTINCT r.models" + from, principal, filter, true));
    }

    /** 最近秩法：第 ceil(p% × n) 个；没有值时为 null。 */
    static Long rank(List<Long> sorted, int percent) {
        if (sorted.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(percent / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** 筛选用的清单：不按工具和模型筛，去重、排序；{@code split} 时每个值是换行分隔的多个。 */
    private static List<String> facet(Connection connection, String select, Principal principal, Filter filter, boolean split)
            throws SQLException {
        StringBuilder sql = new StringBuilder(select);
        List<Object> args = new ArrayList<>();
        args.add(principal.value());
        where(filter, false, sql, args);
        TreeSet<String> values = new TreeSet<>();
        try (PreparedStatement statement = prepare(connection, sql.toString(), args); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String value = result.getString(1);
                if (value == null || value.isEmpty()) {
                    continue;
                }
                if (split) {
                    values.addAll(List.of(value.split("\n")));
                } else {
                    values.add(value);
                }
            }
        }
        return List.copyOf(values);
    }

    private static PreparedStatement prepare(Connection connection, String sql, List<Object> args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < args.size(); i++) {
            statement.setObject(i + 1, args.get(i));
        }
        return statement;
    }

    /** 筛选条件拼成 SQL（表别名 {@code r}）；{@code byToolAndModel} 为 false 时不按工具和模型筛（筛选用的清单）。 */
    private static void where(Filter filter, boolean byToolAndModel, StringBuilder sql, List<Object> args) {
        if (filter.from() != null) {
            sql.append(" AND r.started >= ?");
            args.add(filter.from().toEpochMilli());
        }
        if (filter.to() != null) {
            sql.append(" AND r.started < ?");
            args.add(filter.to().toEpochMilli());
        }
        if (filter.ends() != null && !filter.ends().isEmpty()) {
            List<String> reasons = filter.ends().stream().filter(end -> !end.equals(RUNNING)).toList();
            List<String> parts = new ArrayList<>();
            if (!reasons.isEmpty()) {
                parts.add("r.end_reason IN (" + String.join(", ", reasons.stream().map(reason -> "?").toList()) + ")");
                args.addAll(reasons);
            }
            if (filter.ends().contains(RUNNING)) {
                parts.add("r.ended IS NULL");
            }
            sql.append(" AND (").append(String.join(" OR ", parts)).append(")");
        }
        if (filter.toolErrors() != null) {
            sql.append(filter.toolErrors() ? " AND r.tool_errors > 0" : " AND r.tool_errors = 0");
        }
        if (byToolAndModel && filter.tool() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM run_tools t WHERE t.session = r.session AND t.run = r.run AND t.name = ?)");
            args.add(filter.tool());
        }
        if (byToolAndModel && filter.model() != null) {
            sql.append(" AND instr(char(10) || r.models || char(10), char(10) || ? || char(10)) > 0");
            args.add(filter.model());
        }
        if (filter.session() != null) {
            sql.append(" AND r.session = ?");
            args.add(filter.session().value());
        }
    }

    /** 列表的工具名和次数另查一次，按写入时的先后。 */
    private static List<Listed> withTools(Connection connection, List<Listed> listed) throws SQLException {
        if (listed.isEmpty()) {
            return listed;
        }
        Map<String, List<Tally.ToolCount>> tools = new LinkedHashMap<>();
        String placeholders = String.join(", ", listed.stream().map(item -> "(?, ?)").toList());
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT session, run, name, calls, errors FROM run_tools WHERE (session, run) IN (VALUES " + placeholders
                        + ") ORDER BY session, run, position")) {
            int i = 1;
            for (Listed item : listed) {
                select.setString(i++, item.row().session().value());
                select.setString(i++, item.row().run().value());
            }
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    tools.computeIfAbsent(result.getString(1) + "\n" + result.getString(2), ignored -> new ArrayList<>())
                            .add(new Tally.ToolCount(result.getString(3), result.getInt(4), result.getInt(5)));
                }
            }
        }
        List<Listed> out = new ArrayList<>();
        for (Listed item : listed) {
            RunRow row = item.row();
            Tally tally = row.tally();
            List<Tally.ToolCount> own = tools.getOrDefault(row.session().value() + "\n" + row.run().value(), List.of());
            Tally full = new Tally(tally.tokens(), tally.summaryTokens(), tally.context(), tally.requests(), tally.attempts(),
                    tally.retries(), tally.modelMs(), tally.ttftMs(), tally.toolCalls(), tally.toolErrors(), tally.toolMs(), own,
                    tally.hooks(), tally.hookErrors(), tally.models(), tally.ms());
            out.add(new Listed(new RunRow(row.principal(), row.session(), row.run(), row.turn(), row.trigger(), row.start(), row.end(),
                    row.endReason(), row.prompt(), full), item.title()));
        }
        return out;
    }

    private static RunRow row(ResultSet result) throws SQLException {
        String models = result.getString("models");
        Tally tally = new Tally(
                new TokenUsage(result.getLong("input"), result.getLong("output"), result.getLong("cache_read"), result.getLong("cache_write")),
                new TokenUsage(result.getLong("summary_input"), result.getLong("summary_output"), result.getLong("summary_cache_read"),
                        result.getLong("summary_cache_write")),
                getLong(result, "context"), result.getInt("requests"), result.getInt("attempts"), result.getInt("retries"),
                getLong(result, "model_ms"), getLong(result, "ttft_ms"), result.getInt("tool_calls"), result.getInt("tool_errors"),
                getLong(result, "tool_ms"), List.of(), result.getInt("hooks"), result.getInt("hook_errors"),
                models.isEmpty() ? List.of() : List.of(models.split("\n")), getLong(result, "ms"));
        Long ended = getLong(result, "ended");
        String reason = result.getString("end_reason");
        Long turn = getLong(result, "turn");
        return new RunRow(new Principal(result.getString("principal")), new SessionId(result.getString("session")),
                new RunId(result.getString("run")), turn == null ? null : turn.intValue(), RunHead.Trigger.valueOf(result.getString("trigger")),
                Instant.ofEpochMilli(result.getLong("started")), ended == null ? null : Instant.ofEpochMilli(ended),
                reason == null ? null : EndReason.valueOf(reason), result.getString("prompt"), tally);
    }

    private static Long getLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    /** 翻页位置：上一页最后一行的排序值、会话和运行。对外是不透明的字符串。 */
    record Cursor(long value, String session, String run) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((value + "\n" + session + "\n" + run).getBytes(StandardCharsets.UTF_8));
        }

        /** 不合法时抛 {@link IllegalArgumentException}。 */
        static Cursor decode(String raw) {
            String[] parts = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new Cursor(Long.parseLong(parts[0]), parts[1], parts[2]);
        }
    }
}
