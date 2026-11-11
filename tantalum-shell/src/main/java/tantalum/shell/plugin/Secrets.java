package tantalum.shell.plugin;

import java.util.Optional;

/** 当前用户在这个插件下填的密码。用的时候才取，别存进字段、日志和结果。没填、或没声明过这个键时为空。 */
@FunctionalInterface
public interface Secrets {

    Secrets NONE = key -> Optional.empty();

    Optional<String> get(String key);
}
