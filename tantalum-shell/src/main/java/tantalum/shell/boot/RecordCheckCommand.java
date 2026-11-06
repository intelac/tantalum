package tantalum.shell.boot;

import java.io.PrintStream;
import java.util.List;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.cli.CliText;
import tantalum.shell.plugin.Tool;
import tantalum.shell.store.RecordCheck;

/**
 * {@code java -jar tantalum.jar check [--tantalum.data-dir=…]}：用这个版本的内核检查数据目录里的会话（record-results R12）。
 * 读和服务器相同的配置、用相同的钩子插件，但不建模型客户端、不连 MCP、不开端口，也不取存储锁，服务器运行时也能跑。
 * 嵌入 Tantalum 的应用有自己的插件时，用自己的 Spring 配置调 {@link RecordCheck#run}。
 */
final class RecordCheckCommand {

    @Configuration
    @EnableConfigurationProperties(TantalumProperties.class)
    static class Config {}

    private RecordCheckCommand() {}

    static int run(String[] args, PrintStream out, CliText text) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Config.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false).run(args)) {
            TantalumProperties properties = context.getBean(TantalumProperties.class);
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
