package tantalum.shell.cli;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tantalum.value.text.Bundle;
import tantalum.value.text.Language;

/**
 * 命令行子命令 {@code users}、{@code check}、{@code browser install} 的输出（console-i18n I13）：三种语言的资源文件
 * {@code tantalum/shell/cli/Text*.properties}。语言：{@code --lang} → {@code LC_ALL}、{@code LC_MESSAGES}、{@code LANG} → 系统语言 → 英文，
 * 同连接程序。服务器日志和启动失败的日志不经这里，一律英文。
 */
public final class CliText {

    static final String BUNDLE = "tantalum.shell.cli.Text";
    /** {@code --lang} 的值不对时还不知道用哪种语言，一律英文。 */
    public static final String BAD_LANGUAGE = "--lang must be zh, en or ja";

    /** 资源文件里的键：常量名小写、下划线换成点。 */
    public enum Key {
        USERS_USAGE,
        USERS_PASSWORD_REQUIRED,
        USERS_DONE,
        USERS_BAD_NAME,
        USERS_EXISTS,
        USERS_NO_SUCH,
        CHECK_DIFFERENT,
        CHECK_ALL_OK,
        CHECK_SOME_DIFFER,
        CHECK_FAILED,
        BROWSER_USAGE,
        BROWSER_INSTALLING,
        BROWSER_DONE,
        BROWSER_FAILED
    }

    /** 去掉 {@code --lang} 以后的参数和选出的语言。 */
    public record Parsed(String[] args, CliText text) {}

    private final Bundle bundle;

    private CliText(Language language) {
        this.bundle = Bundle.load(BUNDLE, language);
    }

    public static CliText of(Language language) {
        return new CliText(language);
    }

    /** 从参数里取出 {@code --lang 值}；没有时按环境变量和系统语言。值不对时为空，调用方用英文报 {@link #BAD_LANGUAGE}。 */
    public static Optional<Parsed> parse(String[] args, Map<String, String> env, Locale system) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--lang")) {
                Optional<Language> language = Language.parse(i + 1 < args.length ? args[i + 1] : null);
                if (language.isEmpty()) {
                    return Optional.empty();
                }
                String[] rest = new String[args.length - 2];
                System.arraycopy(args, 0, rest, 0, i);
                System.arraycopy(args, i + 2, rest, i, args.length - i - 2);
                return Optional.of(new Parsed(rest, of(language.get())));
            }
        }
        return Optional.of(new Parsed(Arrays.copyOf(args, args.length), of(Language.fromEnvironment(env::get, system))));
    }

    public Language language() {
        return bundle.language();
    }

    public String say(Key key) {
        return bundle.text(key);
    }

    public String say(Key key, Map<String, ?> params) {
        return bundle.text(key, params);
    }
}
