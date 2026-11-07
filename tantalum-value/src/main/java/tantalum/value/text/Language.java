package tantalum.value.text;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * 给人看的文字用哪种语言：中文（简体）、英文、日文。
 * 控制台的规则在 {@code console/src/text/language.ts}，这里是同样的规则，给服务器、连接程序、命令行用。
 */
public enum Language {
    ZH("zh"),
    EN("en"),
    JA("ja");

    private final String id;

    Language(String id) {
        this.id = id;
    }

    /** {@code zh}、{@code en}、{@code ja}：存进用户设置、写在接口里的值。 */
    public String id() {
        return id;
    }

    public Locale locale() {
        return Locale.of(id);
    }

    /** 只认这三个值，大小写要对。 */
    public static Optional<Language> parse(String value) {
        for (Language language : values()) {
            if (language.id.equals(value)) {
                return Optional.of(language);
            }
        }
        return Optional.empty();
    }

    /** 先比整个标签、再只比主语言（{@code zh-TW} 算 {@code zh}），第一个对上的就用；都对不上是英文。 */
    public static Language fromTags(List<String> tags) {
        for (String tag : tags) {
            Optional<Language> found = ofTag(tag);
            if (found.isPresent()) {
                return found.get();
            }
        }
        return EN;
    }

    /**
     * 终端里的语言（照 pi chord {@code launcher.ts:472-481}）：依次看 {@code LC_ALL}、{@code LC_MESSAGES}、{@code LANG}，
     * 取 {@code .} 和 {@code @} 之前的部分（{@code ja_JP.UTF-8} 是日文），{@code C}、{@code POSIX} 和空的当没设；
     * 第一个设了的就定了，不是三种之一时用英文。都没设时看 Java 的系统语言，也不是三种之一时用英文。
     */
    public static Language fromEnvironment(Function<String, String> env, Locale system) {
        for (String name : List.of("LC_ALL", "LC_MESSAGES", "LANG")) {
            String value = env.apply(name);
            if (value == null) {
                continue;
            }
            String tag = value.split("[.@]", 2)[0].trim();
            if (tag.isEmpty() || tag.equals("C") || tag.equals("POSIX")) {
                continue;
            }
            return ofTag(tag).orElse(EN);
        }
        return ofTag(system.toLanguageTag()).orElse(EN);
    }

    private static Optional<Language> ofTag(String tag) {
        String whole = tag.toLowerCase(Locale.ROOT).replace('_', '-');
        Optional<Language> exact = parse(whole);
        if (exact.isPresent()) {
            return exact;
        }
        return parse(whole.split("-", 2)[0]);
    }
}
