package tantalum.shell.boot;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.cli.CliText;
import tantalum.shell.plugin.Tool;
import tantalum.shell.store.RecordCheck;

/**
 * {@code java -jar tantalum.jar check [--tantalum.data-dir=…]}：用这个版本的内核检查数据目录里的会话。
 * 读和服务器相同的配置、用相同的钩子插件，但不建模型客户端、不开端口，也不取存储锁，服务器运行时也能跑。
 * 类路径上以自动配置注册的插件（比如 {@code loader.path} 里的示例 jar）也装上：少了它们，插件当时做过决定的会话都会报不一致。
 * 嵌入 Tantalum 的应用有自己的插件时，用自己的 Spring 配置调 {@link RecordCheck#run}。
 */
final class RecordCheckCommand {

    @Configuration
    @EnableConfigurationProperties(TantalumProperties.class)
    static class Config {}

    private RecordCheckCommand() {}

    /** 自己的配置，加上类路径上插件 jar 登记的自动配置；Spring Boot 自带的那些（Web、数据源……）不要。 */
    static List<Class<?>> sources() {
        ClassLoader loader = RecordCheckCommand.class.getClassLoader();
        List<Class<?>> sources = new ArrayList<>(List.of(Config.class));
        for (String name : ImportCandidates.load(AutoConfiguration.class, loader)) {
            if (!name.startsWith("org.springframework.")) {
                sources.add(ClassUtils.resolveClassName(name, loader));
            }
        }
        return sources;
    }

    static int run(String[] args, PrintStream out, CliText text) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(sources().toArray(Class<?>[]::new))
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false).run(args)) {
            TantalumProperties properties = context.getBean(TantalumProperties.class);
            if (!DataDirCheck.forCommand(properties.dataDir(), out, text)) {
                return 1;
            }
            List<KernelHook> hooks = context.getBeanProvider(KernelHook.class).orderedStream().toList();
            List<Tool> tools = context.getBeanProvider(Tool.class).orderedStream().toList();
            Kernel kernel = KernelFactory.kernel(properties, KernelFactory.safety(properties, tools), hooks);
            return RecordCheck.print(RecordCheck.run(properties.dataDir(), kernel), out, text);
        } catch (Exception failure) {
            out.println(text.say(CliText.Key.CHECK_FAILED, java.util.Map.of("detail", String.valueOf(failure.getMessage()))));
            return 2;
        }
    }
}
