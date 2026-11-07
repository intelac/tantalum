package tantalum.examples.boot;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import tantalum.examples.CitationCheckHook;
import tantalum.examples.CitationWork;
import tantalum.examples.DailyQuotaHook;
import tantalum.examples.PinnedContextHook;
import tantalum.examples.ProviderBackoffHook;
import tantalum.examples.ProviderStatusWork;
import tantalum.examples.ReadOnlyHook;
import tantalum.examples.ToolApprovalHook;
import tantalum.examples.TruncateOutputHook;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.WorkType;

/**
 * 把示例插件注册成 Spring bean（自动配置，{@code META-INF/spring/…AutoConfiguration.imports}）。可执行 jar 里没有示例插件；
 * 部署要用时把示例 jar 放进 {@code loader.path}，用 {@code PropertiesLauncher} 启动，再加 {@code --tantalum.examples.enabled=true}
 * （README“写插件 › 部署示例插件”）。插件本身不依赖 Spring，Spring 只在这个包里。
 *
 * <p>钩子按这里的顺序进钩子链：只读策略在工具审批前面，被只读拦下的调用不用再问人。
 */
@AutoConfiguration
@ConditionalOnBooleanProperty("tantalum.examples.enabled")
@EnableConfigurationProperties(ExampleSettings.class)
public class ExamplePlugins {

    private static final Logger LOG = LoggerFactory.getLogger(ExamplePlugins.class);
    private static final String PREFIX = "tantalum.examples.";

    private final ExampleSettings settings;

    public ExamplePlugins(ExampleSettings settings) {
        this.settings = settings;
        LOG.info("example plugins from tantalum-examples: {}", names(settings));
    }

    private static List<String> names(ExampleSettings settings) {
        List<String> names = new ArrayList<>();
        if (settings.readOnly().enabled()) names.add("readOnly");
        if (settings.toolApproval().enabled()) names.add("toolApproval");
        if (settings.dailyQuota().enabled()) names.add("dailyQuota");
        if (settings.pinnedContext().enabled()) names.add("pinnedContext");
        if (settings.providerBackoff().enabled() && hasText(settings.providerBackoff().statusUrl())) names.add("providerBackoff");
        if (settings.citationCheck().enabled()) names.add("citationCheck");
        if (settings.truncateOutput().enabled()) names.add("truncateOutput");
        return names;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Bean
    @Order(1)
    @ConditionalOnBooleanProperty(name = PREFIX + "read-only.enabled", matchIfMissing = true)
    KernelHook exampleReadOnlyHook() {
        return new ReadOnlyHook(Set.copyOf(settings.readOnly().tools()));
    }

    @Bean
    @Order(2)
    @ConditionalOnBooleanProperty(name = PREFIX + "tool-approval.enabled", matchIfMissing = true)
    KernelHook exampleToolApprovalHook() {
        return new ToolApprovalHook(Set.copyOf(settings.toolApproval().tools()));
    }

    @Bean
    @Order(3)
    @ConditionalOnBooleanProperty(name = PREFIX + "daily-quota.enabled", matchIfMissing = true)
    KernelHook exampleDailyQuotaHook() {
        return new DailyQuotaHook(settings.dailyQuota().tokens());
    }

    @Bean
    @Order(4)
    @ConditionalOnBooleanProperty(name = PREFIX + "pinned-context.enabled", matchIfMissing = true)
    KernelHook examplePinnedContextHook() {
        return new PinnedContextHook(settings.pinnedContext().marker());
    }

    @Bean
    @Order(5)
    @ConditionalOnBooleanProperty(name = PREFIX + "provider-backoff.enabled", matchIfMissing = true)
    @ConditionalOnProperty(PREFIX + "provider-backoff.status-url")
    KernelHook exampleProviderBackoffHook() {
        return new ProviderBackoffHook(settings.providerBackoff().maxWait());
    }

    @Bean
    @ConditionalOnBooleanProperty(name = PREFIX + "provider-backoff.enabled", matchIfMissing = true)
    @ConditionalOnProperty(PREFIX + "provider-backoff.status-url")
    WorkType exampleProviderStatusWork() {
        return new ProviderStatusWork(URI.create(settings.providerBackoff().statusUrl()), settings.providerBackoff().poll());
    }

    @Bean
    @Order(6)
    @ConditionalOnBooleanProperty(name = PREFIX + "citation-check.enabled", matchIfMissing = true)
    KernelHook exampleCitationCheckHook() {
        return new CitationCheckHook();
    }

    @Bean
    @ConditionalOnBooleanProperty(name = PREFIX + "citation-check.enabled", matchIfMissing = true)
    WorkType exampleCitationWork() {
        return new CitationWork();
    }

    @Bean
    @ConditionalOnBooleanProperty(name = PREFIX + "truncate-output.enabled", matchIfMissing = true)
    AfterToolHook exampleTruncateOutputHook() {
        return new TruncateOutputHook(settings.truncateOutput().maxChars());
    }
}
