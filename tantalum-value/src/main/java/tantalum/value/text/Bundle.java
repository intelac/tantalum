package tantalum.value.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一种语言的资源文件：每个模块一组 UTF-8 的 {@code Text.properties}（英文）、{@code Text_zh}、{@code Text_ja}。
 * 读的时候关掉 Java 按系统语言的回退，否则日文里找不到的键会拿系统语言那份；找不到的键用英文，英文也没有就显示键名。
 * 占位符写 {@code {name}}，不用 {@code MessageFormat}：它要把英文里的 {@code '} 写成 {@code ''}，一不小心整句就错。
 * 只读类路径上的资源文件，不做别的 I/O。别处一律经这里读 {@link ResourceBundle}（ArchitectureTest 管着）。
 * 写成 record 是因为 {@code tantalum.value} 里只放 record、接口和枚举。
 */
public record Bundle(ResourceBundle bundle, Language language) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final ResourceBundle.Control NO_FALLBACK = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    /** {@code baseName} 形如 {@code tantalum.connect.Text}。英文那份不存在时抛 {@link MissingResourceException}。 */
    public static Bundle load(String baseName, Language language) {
        // 英文是没有后缀的那份：按 ROOT 读，不会去找 Text_en
        Locale locale = language == Language.EN ? Locale.ROOT : language.locale();
        return new Bundle(ResourceBundle.getBundle(baseName, locale, Bundle.class.getClassLoader(), NO_FALLBACK), language);
    }

    /** 每个模块的键是一个枚举，文件里的键是常量名的小写、下划线换成点：{@code TOKEN_PROMPT} 是 {@code token.prompt}。 */
    public static String key(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT).replace('_', '.');
    }

    public String text(Enum<?> key) {
        return text(key(key), Map.of());
    }

    public String text(Enum<?> key, Map<String, ?> params) {
        return text(key(key), params);
    }

    public String text(String key) {
        return text(key, Map.of());
    }

    /** 把 {@code {name}} 换成参数；参数里没有的原样留着，测试会抓到。 */
    public String text(String key, Map<String, ?> params) {
        String template = bundle.containsKey(key) ? bundle.getString(key) : key;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = params.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : String.valueOf(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 这一种语言的文件里自己写了的键和模板，不含从英文那份继承来的；文件不存在时是空表。给测试用：查三份是否齐全。
     */
    public static Map<String, String> ownTemplates(String baseName, Language language) {
        String path = baseName.replace('.', '/') + (language == Language.EN ? "" : "_" + language.id()) + ".properties";
        try (InputStream in = Bundle.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return Map.of();
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<String, String> out = new TreeMap<>();
            properties.stringPropertyNames().forEach(key -> out.put(key, properties.getProperty(key)));
            return out;
        } catch (IOException failure) {
            throw new UncheckedIOException(path, failure);
        }
    }

    /** 模板里的占位符名字，按出现的顺序。 */
    public static Set<String> placeholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
