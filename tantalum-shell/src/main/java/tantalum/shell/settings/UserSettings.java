package tantalum.shell.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.auth.UserStore;
import tantalum.value.id.Principal;
import tantalum.value.model.ModelChoice;
import tantalum.value.text.Language;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 每个用户自己的设置（compaction-threshold T5）。存在 {@code users.db} 的 {@code user_settings} 表里，一行一项，不进 journal：
 * 压缩点，“上次的选择”（model-picker M7，键 {@code model_choice}，值是 JSON），和控制台的语言（console-i18n I3，键 {@code language}，
 * 值是 {@code zh}、{@code en}、{@code ja}，没有这一行就是“跟随浏览器”）。按用户缓存在内存里，改的时候先写库再更新缓存。
 */
public final class UserSettings implements AutoCloseable {

    /** 压缩点能选的百分比，100 就是“用满”（T4）。 */
    public static final List<Integer> COMPACTION_PERCENTS = List.of(50, 70, 85, 100);
    public static final int DEFAULT_COMPACTION_PERCENT = 100;

    private static final String COMPACTION_PERCENT = "compaction_percent";
    private static final String MODEL_CHOICE = "model_choice";
    private static final String LANGUAGE = "language";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Connection connection;
    private final Clock clock;
    private final Map<Principal, Integer> percents = new ConcurrentHashMap<>();
    /** 没选过的用户也缓存一份空的，免得每次提问都读库。 */
    private final Map<Principal, Optional<ModelChoice>> choices = new ConcurrentHashMap<>();
    private final Map<Principal, Optional<Language>> languages = new ConcurrentHashMap<>();

    private UserSettings(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /** 不落盘的设置：嵌入使用和测试。 */
    public static UserSettings inMemory(Clock clock) throws SQLException {
        return create(DriverManager.getConnection("jdbc:sqlite::memory:"), clock);
    }

    /** 和用户表、插件开关同一个文件。 */
    public static UserSettings open(Path dataDir, Clock clock) throws IOException, SQLException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(UserStore.FILE);
        if (!Files.exists(file)) {
            PrivateFiles.createFile(file);
        }
        return create(DriverManager.getConnection("jdbc:sqlite:" + file), clock);
    }

    private static UserSettings create(Connection connection, Clock clock) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS user_settings (
                      user       TEXT NOT NULL,
                      name       TEXT NOT NULL,
                      value      TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY (user, name)
                    )""");
        }
        return new UserSettings(connection, clock);
    }

    /** 这个用户选的压缩点百分比；没选过是 100。 */
    public int compactionPercent(Principal user) {
        Integer cached = percents.get(user);
        if (cached != null) {
            return cached;
        }
        // 不用 computeIfAbsent：读库要拿对象锁，保存时拿着对象锁写缓存，两边会互等
        int loaded = loadPercent(user);
        Integer raced = percents.putIfAbsent(user, loaded);
        return raced == null ? loaded : raced;
    }

    /** 不是 {@link #COMPACTION_PERCENTS} 之一时抛 {@link IllegalArgumentException}。 */
    public synchronized void setCompactionPercent(Principal user, int percent) {
        if (!COMPACTION_PERCENTS.contains(percent)) {
            throw new IllegalArgumentException("compaction percent must be one of " + COMPACTION_PERCENTS + " but was " + percent);
        }
        save(user, COMPACTION_PERCENT, Integer.toString(percent));
        percents.put(user, percent);
    }

    /** 这个用户上次在选择器里选的（M7）；没选过、或者存的读不懂时为空。能不能用由调用方按现在的模型列表判断。 */
    public Optional<ModelChoice> modelChoice(Principal user) {
        Optional<ModelChoice> cached = choices.get(user);
        if (cached != null) {
            return cached;
        }
        Optional<ModelChoice> loaded = load(user, MODEL_CHOICE).flatMap(UserSettings::parseChoice);
        Optional<ModelChoice> raced = choices.putIfAbsent(user, loaded);
        return raced == null ? loaded : raced;
    }

    /** 存成这个用户的“上次的选择”，只改他自己的。 */
    public synchronized void setModelChoice(Principal user, ModelChoice choice) {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("route", choice.route());
        value.put("model", choice.model());
        if (choice.effort() != null) {
            value.put("effort", choice.effort());
        }
        save(user, MODEL_CHOICE, JSON.writeValueAsString(value));
        choices.put(user, Optional.of(choice));
    }

    /** 这个用户选的控制台语言；没选过（跟随浏览器）、或者存的读不懂时为空。 */
    public Optional<Language> language(Principal user) {
        Optional<Language> cached = languages.get(user);
        if (cached != null) {
            return cached;
        }
        Optional<Language> loaded = load(user, LANGUAGE).flatMap(Language::parse);
        Optional<Language> raced = languages.putIfAbsent(user, loaded);
        return raced == null ? loaded : raced;
    }

    /** 空就删掉这一行，回到“跟随浏览器”。只改他自己的。 */
    public synchronized void setLanguage(Principal user, Optional<Language> language) {
        if (language.isPresent()) {
            save(user, LANGUAGE, language.get().id());
        } else {
            delete(user, LANGUAGE);
        }
        languages.put(user, language);
    }

    private static Optional<ModelChoice> parseChoice(String stored) {
        try {
            JsonNode node = JSON.readTree(stored);
            String route = node.path("route").asString(null);
            String model = node.path("model").asString(null);
            if (route == null || model == null || route.isBlank() || model.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ModelChoice(route, model, node.path("effort").asString(null)));
        } catch (RuntimeException garbled) {
            return Optional.empty();
        }
    }

    private void save(Principal user, String name, String value) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO user_settings (user, name, value, updated_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, user.value());
            statement.setString(2, name);
            statement.setString(3, value);
            statement.setString(4, clock.instant().toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot save settings for " + user.value(), failure);
        }
    }

    private void delete(Principal user, String name) {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM user_settings WHERE user = ? AND name = ?")) {
            statement.setString(1, user.value());
            statement.setString(2, name);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot save settings for " + user.value(), failure);
        }
    }

    private synchronized Optional<String> load(Principal user, String name) {
        try (PreparedStatement query = connection.prepareStatement("SELECT value FROM user_settings WHERE user = ? AND name = ?")) {
            query.setString(1, user.value());
            query.setString(2, name);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot read settings for " + user.value(), failure);
        }
    }

    private int loadPercent(Principal user) {
        Optional<String> stored = load(user, COMPACTION_PERCENT);
        if (stored.isEmpty()) {
            return DEFAULT_COMPACTION_PERCENT;
        }
        try {
            int percent = Integer.parseInt(stored.get());
            // 以后版本可能加别的档位，这个版本不认识的按默认
            return COMPACTION_PERCENTS.contains(percent) ? percent : DEFAULT_COMPACTION_PERCENT;
        } catch (NumberFormatException garbled) {
            return DEFAULT_COMPACTION_PERCENT;
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
