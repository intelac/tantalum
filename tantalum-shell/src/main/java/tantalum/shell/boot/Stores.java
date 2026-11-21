package tantalum.shell.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.local.LocalTokens;
import tantalum.shell.param.ParamStore;
import tantalum.shell.param.SecretBox;
import tantalum.shell.param.SecretKey;
import tantalum.shell.project.Projects;
import tantalum.shell.project.SessionMarks;
import tantalum.shell.settings.UserSettings;
import tantalum.shell.tool.Workspaces;

/**
 * 装配第一步：打开的数据文件和数据库——journal 和索引、本机工作区的令牌、插件开关、插件参数、用户设置、工作区登记、归档、记忆。
 * 每打开一样就记进 {@code opened}，后面的步骤失败时 {@link Engine} 倒序关。
 */
record Stores(Storage storage, Workspaces workspaces, LocalTokens localTokens, PluginSwitches switches, ParamStore params,
              UserSettings userSettings, Projects projects, SessionMarks marks) {

    private static final Logger LOG = LoggerFactory.getLogger(Stores.class);
    static final String SNAPSHOT_BYTES = "tantalum.journal.snapshot-bytes";

    static Stores open(TantalumProperties properties, EventCodec codec, Clock clock, List<AutoCloseable> opened) throws IOException {
        Storage storage = Storage.open(properties.dataDir(), codec, properties.index(), properties.attachmentLimits());
        opened.add(storage);
        // 写多少字节以后在账里写一份快照（journal-snapshots）：JVM 参数 -Dtantalum.journal.snapshot-bytes，默认 8 MiB
        storage.files.snapshotBytes(Long.getLong(SNAPSHOT_BYTES, tantalum.shell.journal.SessionFiles.DEFAULT_SNAPSHOT_BYTES));
        Workspaces workspaces = KernelFactory.workspaces(properties);
        if (workspaces.isShared()) {
            LOG.warn("tantalum.workspace-mode=shared: all users share {} and can read each other's files.", properties.workspace());
        }
        LocalTokens localTokens = openLocalTokens(properties.dataDir(), clock);
        opened.add(localTokens);
        PluginSwitches switches = openSwitches(properties.dataDir(), clock);
        opened.add(switches);
        ParamStore params = openParams(properties.dataDir(), clock);
        opened.add(params);
        UserSettings userSettings = openUserSettings(properties.dataDir(), clock);
        opened.add(userSettings);
        Projects projects;
        SessionMarks marks;
        try {
            projects = Projects.open(properties.dataDir(), clock);
            opened.add(projects);
            marks = SessionMarks.open(properties.dataDir(), clock);
            opened.add(marks);
        } catch (SQLException failure) {
            throw new IOException("cannot open projects or the session marks in " + properties.dataDir(), failure);
        }
        return new Stores(storage, workspaces, localTokens, switches, params, userSettings, projects, marks);
    }

    private static UserSettings openUserSettings(Path dataDir, Clock clock) throws IOException {
        try {
            return UserSettings.open(dataDir, clock);
        } catch (SQLException failure) {
            throw new IOException("cannot open user settings in " + dataDir, failure);
        }
    }

    private static LocalTokens openLocalTokens(Path dataDir, Clock clock) throws IOException {
        try {
            return LocalTokens.open(dataDir, clock);
        } catch (SQLException failure) {
            throw new IOException("cannot open local tokens in " + dataDir, failure);
        }
    }

    /** 密码的密钥：环境变量给了用环境变量的，没给用数据目录里的 {@code secret.key}（第一次启动时生成）。 */
    private static ParamStore openParams(Path dataDir, Clock clock) throws IOException {
        SecretBox box = new SecretBox(SecretKey.load(dataDir, System.getenv(SecretKey.ENV)));
        try {
            return ParamStore.open(dataDir, clock, box);
        } catch (SQLException failure) {
            throw new IOException("cannot open plugin params in " + dataDir, failure);
        }
    }

    private static PluginSwitches openSwitches(Path dataDir, Clock clock) throws IOException {
        try {
            return PluginSwitches.open(dataDir, clock);
        } catch (SQLException failure) {
            throw new IOException("cannot open plugin settings in " + dataDir, failure);
        }
    }
}
