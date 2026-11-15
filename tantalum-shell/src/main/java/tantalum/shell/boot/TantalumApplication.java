package tantalum.shell.boot;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.auth.AdminBootstrap;
import tantalum.shell.auth.UserCommand;
import tantalum.shell.auth.UserStore;
import tantalum.shell.cli.CliText;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.local.LocalEndpoint;
import tantalum.shell.model.ClaudeClient;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.OpenAiClient;
import tantalum.shell.model.OverflowRules;
import tantalum.shell.panel.Panel;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.ContextContributor;
import tantalum.shell.plugin.PluginEndpoints;
import tantalum.shell.plugin.PromptAttacher;
import tantalum.shell.plugin.RunFinishedListener;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.route.ClaudeModels;
import tantalum.shell.route.OpenAiModels;
import tantalum.shell.route.Route;
import tantalum.shell.route.RouteClients;
import tantalum.shell.route.RouteSettings;
import tantalum.shell.route.Routes;

/** Spring Boot 入口和装配。Spring 只出现在这里和 {@code web} 包。 */
@SpringBootApplication
@EnableConfigurationProperties(TantalumProperties.class)
@ComponentScan(basePackages = {"tantalum.shell.web", "tantalum.shell.auth"})
public class TantalumApplication {

    private static final Logger LOG = LoggerFactory.getLogger(TantalumApplication.class);
    /** 读模型列表的单次请求最多等多久（同 DSH {@code listing-config.ts:9}）。 */
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(5);

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && SUBCOMMANDS.contains(args[0])) {
            System.exit(subcommand(args));
        }
        SpringApplication.run(TantalumApplication.class, args);
    }

    private static final List<String> SUBCOMMANDS = List.of("users", "check", "browser");

    /** 子命令先取出 {@code --lang}，再分派；服务器本身的日志不翻译，不看它。 */
    private static int subcommand(String[] args) throws Exception {
        Optional<CliText.Parsed> parsed = CliText.parse(Arrays.copyOfRange(args, 1, args.length), System.getenv(), Locale.getDefault());
        if (parsed.isEmpty()) {
            System.out.println(CliText.BAD_LANGUAGE);
            return 2;
        }
        String[] command = parsed.get().args();
        CliText text = parsed.get().text();
        if (args[0].equals("check")) {
            // check 读和服务器相同的配置，数据目录在 Spring 里才知道，在那里检查
            return RecordCheckCommand.run(command, System.out, text);
        }
        if (args[0].equals("browser")) {
            // 以前用来给服务器装 Chromium。浏览器现在在用户电脑上，这个命令只剩一句说明（local-browser 需求 8.3）
            System.out.println(text.say(CliText.Key.BROWSER_MOVED));
            return 2;
        }
        Path dataDir = dataDirFromEnvironment();
        if (!DataDirCheck.forCommand(dataDir, System.out, text)) {
            return 1;
        }
        return UserCommand.run(command, dataDir, passwordInput(), System.out, text);
    }

    /**
     * Windows 上在终端里输入时按控制台的编码读：中文 Windows 的控制台是 GBK（代码页 936），按 UTF-8 读会把
     * 中文密码读错，和浏览器登录时的 UTF-8 对不上。管道和重定向时照旧 UTF-8；macOS、Linux 照旧 UTF-8（{@code LANG=C} 的终端报的是
     * US-ASCII，改成按控制台读反而把原来能用的中文密码读坏）。
     */
    private static BufferedReader passwordInput() {
        Console console = System.console();
        boolean windowsTerminal = System.getProperty("os.name", "").startsWith("Windows") && console != null && console.isTerminal();
        Charset charset = windowsTerminal ? console.charset() : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(System.in, charset));
    }

    /** 命令行工具不启动 Spring，所以和服务共用同一个环境变量。 */
    private static Path dataDirFromEnvironment() {
        String configured = System.getenv("TANTALUM_DATA_DIR");
        return configured != null ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".local", "share", "tantalum");
    }

    /**
     * 统一的敏感信息检测（memory-final-plan 第 7 节）：内置规则，去掉 {@code tantalum.sensitive.off} 里列的（逗号分隔的规则名），
     * 加上 {@code tantalum.sensitive.patterns.<名字>=正则}。启动时设一次，插件经 {@link tantalum.shell.plugin.SensitiveText#current()} 用。
     */
    @Bean
    tantalum.shell.plugin.SensitiveText sensitiveText(Environment environment) {
        org.springframework.boot.context.properties.bind.Binder binder = org.springframework.boot.context.properties.bind.Binder.get(environment);
        java.util.Map<String, String> extra = binder.bind("tantalum.sensitive.patterns",
                org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, String.class)).orElse(java.util.Map.of());
        java.util.Set<String> off = new java.util.HashSet<>();
        for (String label : environment.getProperty("tantalum.sensitive.off", "").split(",")) {
            if (!label.isBlank()) {
                off.add(label.strip());
            }
        }
        tantalum.shell.plugin.SensitiveText configured = tantalum.shell.plugin.SensitiveText.of(off, extra);
        tantalum.shell.plugin.SensitiveText.configure(configured);
        return configured;
    }

    /** 管理员只读查看的记录（memory-final-plan 第 13 节）。在 {@link #userStore} 之后打开：数据目录的位置在那里先检查。 */
    @Bean(destroyMethod = "close")
    tantalum.shell.auth.ViewLog viewLog(TantalumProperties properties, UserStore afterTheDataDirCheck) throws IOException, SQLException {
        return tantalum.shell.auth.ViewLog.open(properties.dataDir());
    }

    /** 第一个打开数据目录的地方：先检查数据目录的位置（只在 Windows 上查）。 */
    @Bean(destroyMethod = "close")
    UserStore userStore(TantalumProperties properties) throws IOException, SQLException {
        DataDirCheck.atStartup(properties.dataDir());
        UserStore store = UserStore.open(properties.dataDir());
        AdminBootstrap.ensure(store, System.getenv());
        return store;
    }

    /** 平台给插件的服务（memory-plugin）：插件构造时注入，引擎起来后接上。 */
    @Bean(destroyMethod = "close")
    PlatformServices platformServices(Environment environment) {
        PlatformServices services = new PlatformServices();
        services.dailyTokens(environment.getProperty("tantalum.plugin-models.daily-tokens", Long.class, 0L));
        return services;
    }

    private static List<Object> members(ObjectProvider<?>... providers) {
        List<Object> all = new java.util.ArrayList<>();
        for (ObjectProvider<?> provider : providers) {
            provider.orderedStream().forEach(all::add);
        }
        return all;
    }

    @Bean(destroyMethod = "close")
    Engine engine(TantalumProperties properties, UserStore users, ObjectProvider<ModelClient> model, ObjectProvider<RouteClients> injected,
                  ObjectProvider<Tool> tools, ObjectProvider<KernelHook> hooks, ObjectProvider<WorkType> workTypes,
                  ObjectProvider<AfterToolHook> afterHooks, ObjectProvider<Panel> panels, ObjectProvider<RunFinishedListener> runListeners,
                  ObjectProvider<PromptAttacher> attachers, ObjectProvider<PluginEndpoints> endpoints,
                  ObjectProvider<ContextContributor> contributors, PlatformServices services, Environment environment) throws IOException {
        // 测试和嵌入使用：给了 RouteClients 就按路线换客户端；只给一个 ModelClient 时它就是默认路线的客户端
        RouteClients clients = injected.getIfAvailable(() -> {
            ModelClient single = model.getIfAvailable();
            return single == null ? null : RouteClients.of(properties.provider(), single);
        });
        Routes routes = createRoutes(properties, clients);
        Engine engine = new Engine(properties, routes, List.copyOf(tools.orderedStream().toList()),
                hooks.orderedStream().toList(), workTypes.orderedStream().toList(), afterHooks.orderedStream().toList(),
                panels.orderedStream().toList(), members(runListeners, attachers, endpoints, contributors), EventCodec.withBuiltins(),
                Clock.systemUTC());
        // 插件构造时拿到的服务现在才接上引擎（memory-plugin）
        services.bind(engine);
        RetiredBrowser.atStartup(environment::containsProperty, properties.dataDir());
        RetiredMcp.atStartup(environment);
        // 停用或删掉的账号不能再连电脑：握手时查，之后每次心跳再查
        engine.localLinks().accounts(principal -> usable(users, principal.value()));
        // 联网访问自己永远被拦：先用配置的端口，Web 服务器起来后换成实际端口（见 ownPort）
        int configured = environment.getProperty("server.port", Integer.class, 8080);
        if (configured > 0) {
            engine.addressRules().ownPort(configured);
        }
        return engine;
    }

    /**
     * 内置的命令先问（connector-jar 需求 5）：用户电脑上的 {@code bash}、{@code powershell} 执行前在对话页上问。默认开，每个用户在插件页
     * 关；{@code tantalum.local.command-approval.enabled=false} 整个去掉（评测场景、端到端测试里没有人回答）。排在最前，先于例子插件。
     */
    @Bean
    @org.springframework.core.annotation.Order(0)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty(name = "tantalum.local.command-approval.enabled",
            matchIfMissing = true)
    tantalum.kernel.hook.KernelHook commandApprovalHook() {
        return new tantalum.shell.local.CommandApprovalHook();
    }

    /**
     * 内置的密钥遮盖（QAgent 问题 67）：工具结果里的密钥换成 [REDACTED]。默认开，每个用户在插件页关；
     * {@code tantalum.secret-mask.enabled=false} 整个去掉。排在最前，先于截断这类工具后处理。
     */
    @Bean
    @org.springframework.core.annotation.Order(0)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty(name = "tantalum.secret-mask.enabled", matchIfMissing = true)
    tantalum.shell.plugin.AfterToolHook secretMaskHook() {
        return new tantalum.shell.mask.SecretMaskHook();
    }

    /** Web 服务器的实际端口（配置 0 时是随机的）告诉联网的地址规则。 */
    @Bean
    ApplicationListener<WebServerInitializedEvent> ownPort(ObjectProvider<Engine> engine) {
        return event -> engine.getObject().addressRules().ownPort(event.getWebServer().getPort());
    }

    /** 读不了用户表时当作还能用：心跳里查失败一次就撤销全部令牌，代价太大；登录本身照常由 {@code LoginContextRepository} 把关。 */
    private static boolean usable(UserStore users, String name) {
        try {
            return users.status(name).map(status -> !status.disabled()).orElse(false);
        } catch (SQLException failure) {
            LOG.warn("cannot read the account of {} for its local link: {}", name, failure.getMessage());
            return true;
        }
    }

    /**
     * 连接程序的 WebSocket 端点，用 Tomcat 自带的 Jakarta WebSocket 注册，不加 Spring WebSocket。Spring 的初始化器先于
     * Tomcat 的 WebSocket 初始化器执行，那时还没有 {@link ServerContainer}，所以挂一个监听器，等全部初始化器执行完再注册。
     * {@code Engine} 在第一次握手时才取。
     */
    @Bean
    ServletContextInitializer localEndpoint(ObjectProvider<Engine> engine) {
        return servletContext -> servletContext.addListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                ServerContainer container = (ServerContainer) event.getServletContext().getAttribute(ServerContainer.class.getName());
                if (container == null) {
                    throw new IllegalStateException("WebSocket support (tomcat-embed-websocket) is missing; cannot serve "
                            + LocalEndpoint.PATH);
                }
                try {
                    container.addEndpoint(LocalEndpoint.config(() -> engine.getObject().localLinks()));
                } catch (DeploymentException failure) {
                    throw new IllegalStateException("cannot register " + LocalEndpoint.PATH, failure);
                }
            }
        });
    }

    /**
     * 每条路线一个客户端和一张模型列表：Claude 的密钥按 {@code api-key-env} 读，
     * 客户端和 Models 接口共用一个 SDK 客户端；OpenAI 兼容的有 {@code api-key-env} 才读、才发认证头。密钥只在这里经过，不记日志。
     * 云端路线照旧提示 prompt 会发出去（tech.md）。
     */
    static Routes createRoutes(TantalumProperties properties, RouteClients injected) {
        OverflowRules rules = OverflowRules.defaults();
        List<RouteSettings> settings = properties.routeSettings();
        Routes routes = Routes.create(settings, properties.provider(), (route, key) -> switch (route.kind()) {
            case CLAUDE -> {
                AnthropicClient sdk = AnthropicOkHttpClient.builder().apiKey(key).maxRetries(0).build();
                yield new Routes.Built(new ClaudeClient(sdk, new ClaudeClient.Settings(route.defaultModel(), route.maxTokens(),
                        route.defaultEffort(), route.fallbacks(), route.firstChunkTimeout(), route.betweenChunkTimeout(), rules, false,
                        properties.claude() == null || properties.claude().cacheTtl().equals("1h"))),
                        new ClaudeModels(sdk, route.name(), route.allow(), route.defaultEffort(), route.fallbacks(), route.maxTokens(),
                                LIST_TIMEOUT));
            }
            case OPENAI -> new Routes.Built(new OpenAiClient(route.baseUrl(), route.defaultModel(), key, (int) route.maxTokens(),
                    route.firstChunkTimeout(), route.betweenChunkTimeout(), rules, route::vision, route::thinking),
                    new OpenAiModels(route, key, LIST_TIMEOUT));
        }, injected, System::getenv, properties.windowFromModel(), Clock.systemUTC());
        for (Route route : routes.all()) {
            if (route.settings().cloud()) {
                LOG.warn("Route {}: complete prompts, which may contain customer code, are sent to {}.", route.name(),
                        route.settings().kind() == RouteSettings.Kind.CLAUDE ? "Anthropic" : "a hosted service");
            }
        }
        return routes;
    }
}
