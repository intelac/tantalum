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

    /** 记忆工具写入时的信任判断：这个会话读过外部内容没有（这一轮看平台的 runView，以前各轮看 toolsUsed）。 */
    @Bean
    tantalum.memory.ToolTrust memoryToolTrust(ObjectProvider<PluginServices> services) {
        return new tantalum.memory.ToolTrust(context -> services.getObject().runView(context.principal(), context.session(), context.run()),
                context -> services.getObject().toolsUsed(context.principal(), context.session()).stream()
                        .filter(use -> tantalum.memory.capture.Quotes.outside(use.tool(), use.plugin()))
                        .map(PluginServices.ToolUse::tool).distinct().toList());
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

    /** 找回（memory-final-plan 第 9 节）：钩子在一轮第一次调模型前要一次工作；工作读记忆账，必要时用本机模型（关思考）改写问题。 */
    @Bean
    tantalum.memory.recall.RecallHook memoryRecallHook() {
        return new tantalum.memory.recall.RecallHook();
    }

    /** 忘掉之前先问用户（2026-11-20 Yi 定）。 */
    @Bean
    tantalum.memory.ForgetApprovalHook memoryForgetApprovalHook() {
        return new tantalum.memory.ForgetApprovalHook();
    }

    /** 按意思找回的参数的默认值（{@code tantalum.memory-vectors.*}）；参数本身在插件页上。 */
    @Bean
    tantalum.memory.recall.VectorSettings memoryVectorSettings(Environment environment) {
        return Binder.get(environment).bind("tantalum.memory-vectors", tantalum.memory.recall.VectorSettings.class)
                .orElse(tantalum.memory.recall.VectorSettings.defaults());
    }

    /**
     * 按意思找（memory-final-plan 第 9 节第 3 步）：向量服务是记忆插件自己的参数。每次照这个人此刻的参数取服务；
     * 他关了、或者没配地址时给空，找回只靠关键词。
     */
    @Bean(destroyMethod = "close")
    tantalum.memory.recall.Semantic memorySemantic(MemoryStores stores, ObjectProvider<PluginServices> services,
                                                   tantalum.memory.recall.VectorSettings vectors) {
        java.util.Map<tantalum.memory.recall.Embeddings.Settings, tantalum.memory.recall.Embeddings> clients = new java.util.concurrent.ConcurrentHashMap<>();
        return new tantalum.memory.recall.Semantic(stores, user -> {
            tantalum.memory.recall.Embeddings.Settings settings = vectors.effective(services.getObject().params(MemoryTools.PLUGIN, user));
            tantalum.memory.recall.Embeddings client = clients.computeIfAbsent(settings, tantalum.memory.recall.Embeddings::new);
            return new tantalum.memory.recall.Vectors.Embedder() {
                @Override public java.util.Optional<java.util.List<float[]>> embed(java.util.List<String> texts, boolean query) {
                    return client.embed(texts, query);
                }
                @Override public String stamp() {
                    return settings.model() + "/" + settings.dimensions() + "\n";
                }
            };
        }, vectors.firstWait());
    }

    @Bean
    tantalum.memory.recall.RecallWork memoryRecallWork(MemoryStores stores, CaptureSettings settings, ObjectProvider<PluginServices> services,
                                                         tantalum.memory.recall.Semantic semantic,
                                                         tantalum.memory.recall.VectorSettings vectors) {
        PluginServices.ModelPick pick = new PluginServices.ModelPick(settings.route(), settings.model(), "off");
        return new tantalum.memory.recall.RecallWork(stores, (user, system, text) -> services.getObject().complete(
                new PluginServices.Completion(user, MemoryTools.PLUGIN, "rewrite", PluginServices.Priority.FOREGROUND, system, text, pick, 160)),
                user -> services.getObject().zone(user), semantic, vectors);
    }

    @Bean
    tantalum.memory.recall.MemorySearchTool memorySearchTool(MemoryStores stores, ObjectProvider<PluginServices> services,
                                                             tantalum.memory.recall.Semantic semantic) {
        return new tantalum.memory.recall.MemorySearchTool(stores, user -> services.getObject().zone(user), semantic);
    }

    @Bean
    tantalum.memory.recall.HistorySearchTool historySearchTool(ObjectProvider<PluginServices> services, MemoryStores stores) {
        return new tantalum.memory.recall.HistorySearchTool(context -> services.getObject(), stores.budget());
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
    MemoryDeleteTool memoryDeleteTool(MemoryStores stores, tantalum.memory.ToolTrust trust, ObjectProvider<PluginServices> services) {
        return new MemoryDeleteTool(stores, trust, services::getObject);
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
        NoteTaker taker = new NoteTaker(settings, user -> wantsNotes(services.getObject(), user), stores::notes,
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
                (Principal user) -> services.getObject().zone(user));
        // 图书管理员的提议（memory-final-plan 第 9 节）：排在最后（后台），只读记忆，提议都进“待确认”
        return taker.proposals(user -> new tantalum.memory.LibrarianProposals(stores.ledger(user), stores.of(user), stores.working(user),
                services.getObject().zone(user)).propose((system, text) -> services.getObject().complete(new PluginServices.Completion(user,
                MemoryTools.PLUGIN, "librarian", PluginServices.Priority.BACKGROUND, system, text, pick, 1500))));
    }

    @Bean
    MemoryEndpoints memoryEndpoints(MemoryStores stores, CaptureSettings settings, ObjectProvider<PluginServices> services) {
        return new MemoryEndpoints(stores, user -> settings.enabled() && wantsNotes(services.getObject(), user), settings.enabled(),
                services::getObject);
    }

    /** 插件页上“自动记下”这个参数；没设过是开。 */
    static boolean wantsNotes(PluginServices services, Principal user) {
        return !(services.params(MemoryTools.PLUGIN, user).fields().get(NoteTaker.CAPTURE) instanceof Json.Bool value) || value.value();
    }
}
