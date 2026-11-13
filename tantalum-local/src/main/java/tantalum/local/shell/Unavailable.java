package tantalum.local.shell;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命令用不了的原因：代码加参数。连接程序的终端按它的语言说，插件页按看的人的语言说；
 * 给模型和老版本服务器的是 {@link #english()}，由下面这张英文表生成，不经资源文件，不随连接程序的语言变。
 */
public record Unavailable(Code code, Map<String, String> params) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)}");

    public enum Code {
        /** 老版本的服务器和控制台按这句英文认出“没有 --allow-shell”，所以字一个不改。 */
        SHELL_NOT_ALLOWED("shell/not-allowed", "--allow-shell was not given"),
        /** 电脑上找不到 bash 或 PowerShell；{@code shell} 是要找的那个。 */
        NOT_FOUND("shell/not-found", "cannot find {shell} on this computer");

        private final String wire;
        private final String english;

        Code(String wire, String english) {
            this.wire = wire;
            this.english = english;
        }

        /** 连接消息和接口里的写法，如 {@code shell/not-found}。 */
        public String wire() {
            return wire;
        }
    }

    public Unavailable {
        Objects.requireNonNull(code, "code");
        params = Map.copyOf(params);
    }

    public static Unavailable of(Code code) {
        return new Unavailable(code, Map.of());
    }

    public static Unavailable of(Code code, String name, String value) {
        return new Unavailable(code, Map.of(name, value == null ? "" : value));
    }

    /** 给模型和老版本服务器的一句英文。 */
    public String english() {
        Matcher matcher = PLACEHOLDER.matcher(code.english);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(params.getOrDefault(matcher.group(1), matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
