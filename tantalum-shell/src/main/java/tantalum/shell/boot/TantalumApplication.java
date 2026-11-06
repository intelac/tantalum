package tantalum.shell.boot;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.auth.AdminBootstrap;
import tantalum.shell.auth.UserCommand;
import tantalum.shell.auth.UserStore;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.local.LocalEndpoint;
import tantalum.shell.model.ClaudeClient;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.OpenAiClient;
import tantalum.shell.model.OverflowRules;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.WorkType;

/** Spring Boot 入口和装配。Spring 只出现在这里和 {@code web} 包。 */
@SpringBootApplication
@EnableConfigurationProperties(TantalumProperties.class)
@ComponentScan(basePackages = {"tantalum.shell.web", "tantalum.shell.auth"})
public class TantalumApplication {

    private static final Logger LOG = LoggerFactory.getLogger(TantalumApplication.class);

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("users")) {
            String[] command = Arrays.copyOfRange(args, 1, args.length);
            System.exit(UserCommand.run(command, dataDirFromEnvironment(),
                    new BufferedReader(new InputStreamReader(System.in)), System.out));
        }
        if (args.length > 0 && args[0].equals("check")) {
            System.exit(RecordCheckCommand.run(Arrays.copyOfRange(args, 1, args.length), System.out));
        }
        SpringApplication.run(TantalumApplication.class, args);
    }

    /** 命令行工具不启动 Spring，所以和服务共用同一个环境变量。 */
    private static Path dataDirFromEnvironment() {
        String configured = System.getenv("TANTALUM_DATA_DIR");
        return configured != null ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".local", "share", "tantalum");
    }

    @Bean(destroyMethod = "close")
    UserStore userStore(TantalumProperties properties) throws IOException, SQLException {
        UserStore store = UserStore.open(properties.dataDir());
        AdminBootstrap.ensure(store, System.getenv());
        return store;
    }

    @Bean(destroyMethod = "close")
    Engine engine(TantalumProperties properties, ObjectProvider<ModelClient> model, ObjectProvider<Tool> tools,
                  ObjectProvider<KernelHook> hooks, ObjectProvider<WorkType> workTypes,
                  ObjectProvider<AfterToolHook> afterHooks) throws IOException {
        boolean claude = properties.provider().equals("claude");
        Duration wait = claude ? properties.claude().firstChunkTimeout() : properties.openai().firstChunkTimeout();
        int concurrency = claude ? properties.claude().concurrency() : properties.openai().concurrency();
        ModelClient client = model.getIfAvailable(() -> createClient(properties));
        return new Engine(properties, client, wait, concurrency, List.copyOf(tools.orderedStream().toList()),
                hooks.orderedStream().toList(), workTypes.orderedStream().toList(), afterHooks.orderedStream().toList(),
                EventCodec.withBuiltins(), Clock.systemUTC());
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

    private static ModelClient createClient(TantalumProperties properties) {
        OverflowRules rules = OverflowRules.defaults();
        switch (properties.provider()) {
            case "claude" -> {
                String key = System.getenv("ANTHROPIC_API_KEY");
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("ANTHROPIC_API_KEY is not set. Set it, for example by loading "
                            + "~/.config/tantalum/env, or use the local model with --tantalum.provider=openai.");
                }
                LOG.warn("Using Claude: complete prompts, which may contain customer code, are sent to Anthropic. "
                        + "Use --tantalum.provider=openai for a local model.");
                TantalumProperties.Claude claude = properties.claude();
                return new ClaudeClient(AnthropicOkHttpClient.builder().apiKey(key).maxRetries(0).build(),
                        new ClaudeClient.Settings(claude.model(), claude.maxTokens(), claude.effort(), claude.fallbacks(),
                                claude.firstChunkTimeout(), claude.betweenChunkTimeout(), rules, false));
            }
            case "openai" -> {
                TantalumProperties.OpenAi openai = properties.openai();
                return new OpenAiClient(openai.baseUrl(), openai.model(), System.getenv("OPENAI_API_KEY"),
                        openai.maxTokens(), openai.firstChunkTimeout(), openai.betweenChunkTimeout(), rules, openai.vision());
            }
            default -> throw new IllegalStateException("unknown tantalum.provider: " + properties.provider()
                    + "; expected claude or openai");
        }
    }
}
