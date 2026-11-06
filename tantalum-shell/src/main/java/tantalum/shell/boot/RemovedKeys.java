package tantalum.shell.boot;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * 已经改名的配置还在时拒绝启动（model-picker M17）：Spring 会悄悄忽略多余的键，管理员以为本地模型配好了，其实没有生效。
 * 在读完配置文件之后运行，命令行、环境变量、配置文件里的都查。
 */
public final class RemovedKeys implements EnvironmentPostProcessor, Ordered {

    static final String MESSAGE = "tantalum.openai.* 已改为 tantalum.openai-routes.{名字}.*，见 README“模型路线”";
    private static final String DOTTED = "tantalum.openai.";
    /** 环境变量的写法；{@code TANTALUM_OPENAI_ROUTES_…} 是新的写法，不算。 */
    private static final String VARIABLE = "TANTALUM_OPENAI_";
    private static final String VARIABLE_ROUTES = "TANTALUM_OPENAI_ROUTES_";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> names = new ArrayList<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                names.addAll(List.of(enumerable.getPropertyNames()));
            }
        }
        check(names);
    }

    /** 有旧键时抛出，消息写明改成什么和是哪几个键（只写键名，不写值）。 */
    static void check(Iterable<String> names) {
        List<String> removed = new ArrayList<>();
        for (String name : names) {
            boolean dotted = name.startsWith(DOTTED);
            boolean variable = name.startsWith(VARIABLE) && !name.startsWith(VARIABLE_ROUTES);
            if ((dotted || variable) && !removed.contains(name)) {
                removed.add(name);
            }
        }
        if (!removed.isEmpty()) {
            throw new IllegalStateException(MESSAGE + "（还在用的：" + String.join(", ", removed) + "）");
        }
    }

    /** 在读配置文件的 {@code ConfigDataEnvironmentPostProcessor} 之后。 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
