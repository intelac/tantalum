package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** model-picker 任务 2：模型配置改成路线（M1、M5、M17，需求 1.4、1.5、9.1）。 */
class RouteConfigTest {

    private static final String LOCAL = "tantalum.openai-routes.local.";

    private static TantalumProperties bind(String... overrides) throws Exception {
        Properties properties = new Properties();
        try (InputStream defaults = TantalumProperties.class.getResourceAsStream("/application.properties")) {
            properties.load(new java.io.InputStreamReader(defaults, java.nio.charset.StandardCharsets.UTF_8));
        }
        for (int i = 0; i < overrides.length; i += 2) {
            properties.setProperty(overrides[i], overrides[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(properties)).bind("tantalum", TantalumProperties.class).get();
    }

    private static String[] local(String... more) {
        List<String> all = new java.util.ArrayList<>(List.of(LOCAL + "base-url", "http://127.0.0.1:8090/v1", LOCAL + "model", "qwen"));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    /** 绑定失败时最里面那层的说明。 */
    private static String failure(String... overrides) {
        Throwable cause = assertThrows(Exception.class, () -> bind(overrides));
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    @Test
    void theShippedDefaultsAreOneClaudeRouteAsBefore() throws Exception {
        TantalumProperties properties = bind();
        assertEquals("claude", properties.provider());
        assertEquals("claude-sonnet-5-5", properties.claude().model());
        assertEquals("medium", properties.claude().effort());
        assertEquals("ANTHROPIC_API_KEY", properties.claude().apiKeyEnv());
        assertEquals(List.of(), properties.claude().models(), "empty allowlist: every adaptive model the Models API lists");
        assertEquals(Map.of(), properties.openaiRoutes(), "the local route is only an example in comments");
    }

    @Test
    void anOpenAiRouteGetsItsDefaultsAndCanBeTheDefaultRoute() throws Exception {
        TantalumProperties properties = bind(local("tantalum.provider", "local", LOCAL + "thinking", "chat-template",
                LOCAL + "models", "qwen, qwen-coder"));
        TantalumProperties.OpenAiRoute route = properties.openaiRoutes().get("local");
        assertEquals("local", properties.provider());
        assertNull(route.apiKeyEnv(), "no key variable: no authorization header for a local server");
        assertEquals(List.of("qwen", "qwen-coder"), route.models());
        assertEquals(16384, route.maxTokens());
        assertEquals(1, route.concurrency());
        assertEquals("chat-template", route.thinking());
        assertEquals(List.of("off", "on"), route.efforts());
        assertEquals("on", route.defaultEffort());
        assertTrue(route.listModels());
    }

    /** 需求 1.4：旧键还在时启动失败，消息写明改成什么；不悄悄忽略。 */
    @Test
    void theOldOpenAiKeysStopTheStart() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineArgs",
                Map.of("tantalum.openai.base-url", "http://127.0.0.1:8090/v1", "tantalum.openai-routes.local.model", "qwen")));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RemovedKeys().postProcessEnvironment(environment, new SpringApplication()));
        assertTrue(failure.getMessage().startsWith("tantalum.openai.* 已改为 tantalum.openai-routes.{名字}.*，见 README“模型路线”"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("tantalum.openai.base-url"), failure.getMessage());
        assertTrue(!failure.getMessage().contains("8090"), "the value is not echoed: " + failure.getMessage());

        StandardEnvironment fine = new StandardEnvironment();
        fine.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", Map.of("tantalum.openai-routes.local.model", "qwen")));
        new RemovedKeys().postProcessEnvironment(fine, new SpringApplication());
        assertThrows(IllegalStateException.class, () -> RemovedKeys.check(List.of("TANTALUM_OPENAI_BASE_URL")));
        RemovedKeys.check(List.of("TANTALUM_OPENAI_ROUTES_LOCAL_MODEL"));

        assertTrue(registered(), "registered in META-INF/spring.factories");
    }

    private static boolean registered() throws java.io.IOException {
        java.util.Enumeration<java.net.URL> factories = RemovedKeys.class.getClassLoader().getResources("META-INF/spring.factories");
        while (factories.hasMoreElements()) {
            try (InputStream in = factories.nextElement().openStream()) {
                Properties entries = new Properties();
                entries.load(in);
                String names = entries.getProperty(EnvironmentPostProcessor.class.getName(), "");
                if (names.contains(RemovedKeys.class.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 需求 1.5：路线名只用小写字母、数字、-，最长 32；claude 留给 Claude 路线。 */
    @Test
    void badRouteNamesStopTheStart() {
        assertTrue(failure("tantalum.openai-routes.Local.base-url", "http://x/v1", "tantalum.openai-routes.Local.model", "m")
                .contains("lowercase"));
        String long33 = "a".repeat(33);
        assertTrue(failure("tantalum.openai-routes." + long33 + ".base-url", "http://x/v1", "tantalum.openai-routes." + long33 + ".model", "m")
                .contains("at most 32"));
        assertTrue(failure("tantalum.openai-routes.claude.base-url", "http://x/v1", "tantalum.openai-routes.claude.model", "m")
                .contains("already taken by the Claude route"));
        assertTrue(failure("tantalum.provider", "kimi").contains("names no route"));
    }

    @Test
    void unknownThinkingAndEffortsStopTheStart() {
        assertTrue(failure("tantalum.claude.effort", "extreme").contains("is not a Claude effort"));
        assertTrue(failure(local(LOCAL + "thinking", "foo")).contains("unknown thinking \"foo\""));
        assertTrue(failure(local(LOCAL + "thinking", "reasoning-effort", LOCAL + "efforts", "")).contains("at least one effort"));
        assertTrue(failure(local(LOCAL + "thinking", "reasoning-effort", LOCAL + "default-effort", "max"))
                .contains("default-effort=max is not one of low, medium, high"));
        assertTrue(failure(local(LOCAL + "thinking", "chat-template", LOCAL + "default-effort", "high"))
                .contains("not one of off, on"));
    }

    /** 模型名里有 /、. 时用 Spring 的方括号写法（设计“application.properties”）。 */
    @Test
    void overridesUseTheBracketSyntaxForModelNames() throws Exception {
        TantalumProperties properties = bind(local(LOCAL + "overrides[moonshotai/kimi-k2.5].thinking", "thinking-type",
                LOCAL + "overrides[moonshotai/kimi-k2.5].context-window", "262144", LOCAL + "overrides[moonshotai/kimi-k2.5].vision", "true"));
        TantalumProperties.ModelOverride kimi = properties.openaiRoutes().get("local").override("moonshotai/kimi-k2.5");
        assertEquals("thinking-type", kimi.thinking());
        assertEquals(List.of("off", "on"), kimi.efforts());
        assertEquals(262144L, kimi.contextWindow());
        assertEquals(Boolean.TRUE, kimi.vision());
        assertNull(properties.openaiRoutes().get("local").override("qwen").thinking(), "a model without overrides follows its route");
    }
}
