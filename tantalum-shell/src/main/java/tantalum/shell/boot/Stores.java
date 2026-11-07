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
import tantalum.shell.memory.MemoryStores;
import tantalum.shell.settings.UserSettings;
import tantalum.shell.tool.Workspaces;

/**
 * 装配第一步：打开的数据文件和数据库——journal 和索引、本机工作区的令牌、插件开关、用户设置、记忆。
 * 每打开一样就记进 {@code opened}，后面的步骤失败时 {@link Engine} 倒序关。
 */
record Stores(Storage storage, Workspaces workspaces, LocalTokens localTokens, PluginSwitches switches, UserSettings userSettings,
              MemoryStores memories) {

    private static final Logger LOG = LoggerFactory.getLogger(Stores.class);

    static Stores open(TantalumProperties properties, EventCodec codec, Clock clock, List<AutoCloseable> opened) throws IOException {
        Storage storage = Storage.open(properties.dataDir(), codec, properties.index(), properties.attachmentLimits());
        opened.add(storage);
        Workspaces workspaces = KernelFactory.workspaces(properties);
        if (workspaces.isShared()) {
            LOG.warn("tantalum.workspace-mode=shared: all users share {} and can read each other's files.", properties.workspace());
        }
        LocalTokens localTokens = openLocalTokens(properties.dataDir(), clock);
        opened.add(localTokens);
        PluginSwitches switches = openSwitches(properties.dataDir(), clock);
        opened.add(switches);
        UserSettings userSettings = openUserSettings(properties.dataDir(), clock);
        opened.add(userSettings);
        MemoryStores memories = new MemoryStores(workspaces, properties.dataDir(), properties.memory(), clock);
        return new Stores(storage, workspaces, localTokens, switches, userSettings, memories);
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

    private static PluginSwitches openSwitches(Path dataDir, Clock clock) throws IOException {
        try {
            return PluginSwitches.open(dataDir, clock);
        } catch (SQLException failure) {
            throw new IOException("cannot open plugin settings in " + dataDir, failure);
        }
    }
}
