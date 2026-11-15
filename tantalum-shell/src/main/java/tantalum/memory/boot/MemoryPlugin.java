package tantalum.memory.boot;

import java.time.Clock;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import tantalum.memory.MemoryContext;
import tantalum.memory.MemoryDeleteTool;
import tantalum.memory.MemoryLimits;
import tantalum.memory.MemoryReadTool;
import tantalum.memory.MemoryStores;
import tantalum.memory.MemoryTools;
import tantalum.memory.MemoryWriteTool;
import tantalum.memory.api.MemoryEndpoints;
import tantalum.memory.capture.CaptureSettings;
import tantalum.memory.capture.NoteTaker;
import tantalum.shell.plugin.PluginServices;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

/**
 * 记忆插件（memory-plugin 需求 2）：记忆工具、记忆指引和索引、每天的笔记、自动记下和它们的接口，都只经插件接口和平台打交道。
 * 随服务器发布、默认装上；{@code tantalum.memory-plugin.enabled=false} 不装（企业换成自己的实现）。
 *
 * <p>配置名沿用以前的：上限 {@code tantalum.memory.*}，自动记下 {@code tantalum.memory-capture.*}（没配时是关的，免得测试里多出模型调用）。
 * 平台的服务（{@link PluginServices}）用的时候才取：检查记录的命令行里没有它，那里只要工具的调度描述。
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "tantalum.memory-plugin.enabled", matchIfMissing = true)
public class MemoryPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryPlugin.class);

    @Bean
    MemoryStores memoryStores(ObjectProvider<PluginServices> services, Environment environment) {
        MemoryLimits limits = Binder.get(environment).bind("tantalum.memory", MemoryLimits.class).orElse(MemoryLimits.defaults());
        // 账在插件的私有状态目录（数据目录下）；旧格式的记忆目录只用来第一次导入
        return new MemoryStores(user -> services.getObject().stateDir(MemoryTools.PLUGIN, user),
                user -> services.getObject().dataDir(MemoryTools.PLUGIN, MemoryStores.DIR, user), limits, Clock.systemUTC());
    }

    /** 记忆工具写入时的信任判断：这一轮读过外部内容没有（平台的 runView）。 */
    @Bean
    tantalum.memory.ToolTrust memoryToolTrust(ObjectProvider<PluginServices> services) {
        return new tantalum.memory.ToolTrust(context -> services.getObject().runView(context.principal(), context.session(), context.run()));
    }

    @Bean
    MemoryWriteTool memoryWriteTool(MemoryStores stores, tantalum.memory.ToolTrust trust) {
        return new MemoryWriteTool(stores, trust);
    }

    @Bean
    tantalum.memory.NowTool memoryNowTool(MemoryStores stores, tantalum.memory.ToolTrust trust) {
        return new tantalum.memory.NowTool(stores, trust);
    }

    @Bean
    tantalum.memory.MemoryNoteTool memoryNoteTool(MemoryStores stores, tantalum.memory.ToolTrust trust) {
        return new tantalum.memory.MemoryNoteTool(stores, trust);
    }

    @Bean
    tantalum.memory.MemoryNow memoryNow(MemoryStores stores) {
        return new tantalum.memory.MemoryNow(stores, Clock.systemUTC());
    }

    @Bean
    MemoryReadTool memoryReadTool(MemoryStores stores) {
        return new MemoryReadTool(stores);
    }

    @Bean
    MemoryDeleteTool memoryDeleteTool(MemoryStores stores) {
        return new MemoryDeleteTool(stores);
    }

    @Bean
    MemoryContext memoryContext(MemoryStores stores) {
        return new MemoryContext(stores);
    }

    @Bean
    CaptureSettings memoryCaptureSettings(Environment environment) {
        return Binder.get(environment).bind("tantalum.memory-capture", CaptureSettings.class).orElse(CaptureSettings.defaults());
    }

    @Bean(destroyMethod = "close")
    NoteTaker memoryNoteTaker(CaptureSettings settings, MemoryStores stores, ObjectProvider<PluginServices> services) {
        if (settings.enabled()) {
            LOG.info("memory notes on; route {}, model {}", settings.route() == null ? "(local or default)" : settings.route(),
                    settings.model() == null ? "(route default)" : settings.model());
        }
        PluginServices.ModelPick pick = new PluginServices.ModelPick(settings.route(), settings.model());
        return new NoteTaker(settings, user -> wantsNotes(services.getObject(), user), stores::notes,
                user -> new tantalum.memory.capture.NoteFiler(stores.of(user), stores.working(user), stores.notes(user),
                        services.getObject().sensitive()),
                (user, system, text, maxTokens) -> services.getObject().complete(new PluginServices.Completion(user, MemoryTools.PLUGIN,
                        "capture", PluginServices.Priority.BACKGROUND, system, text, pick, maxTokens)),
                new NoteTaker.Catchup() {
                    @Override
                    public java.util.List<PluginServices.FinishedRef> finished(Principal user, java.time.Instant since) {
                        return services.getObject().finishedRuns(user, since, 200);
                    }

                    @Override
                    public java.util.List<tantalum.shell.plugin.RunSegment> view(Principal user, tantalum.value.id.SessionId session,
                                                                               tantalum.value.id.RunId run) {
                        return services.getObject().runView(user, session, run);
                    }
                },
                ZoneId.systemDefault());
    }

    @Bean
    MemoryEndpoints memoryEndpoints(MemoryStores stores, CaptureSettings settings, ObjectProvider<PluginServices> services) {
        return new MemoryEndpoints(stores, user -> settings.enabled() && wantsNotes(services.getObject(), user), settings.enabled());
    }

    /** 插件页上“自动记下”这个参数；没设过是开。 */
    static boolean wantsNotes(PluginServices services, Principal user) {
        return !(services.params(MemoryTools.PLUGIN, user).fields().get(NoteTaker.CAPTURE) instanceof Json.Bool value) || value.value();
    }
}
