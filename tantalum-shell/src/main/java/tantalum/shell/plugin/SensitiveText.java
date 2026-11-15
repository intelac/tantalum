package tantalum.shell.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的敏感信息检测（memory-final-plan 第 7 节）：所有记忆写入点和找回来源都过一遍。规则有密钥和令牌、各家云的访问密钥、
 * 身份证号（核对校验位）、手机号；管理员可以加规则（{@code tantalum.sensitive.patterns.<名字>=正则}）或关掉内置的
 * （{@code tantalum.sensitive.off=手机号的名字,…}）。只看特征，不做语义判断；结果只给规则名，绝不回显内容。
 */
public final class SensitiveText {

    private record Rule(String label, Pattern pattern, boolean idNumber) {}

    public static final String PHONE = "mobile phone number";
    public static final String ID_NUMBER = "national ID number";

    private static final List<Rule> BUILT_IN = List.of(
            rule("Anthropic key", "sk-ant-[A-Za-z0-9_-]{10,}"),
            rule("OpenAI-style key", "\\bsk-(?=[A-Za-z0-9_-]*\\d)[A-Za-z0-9_-]{20,}"),
            rule("Stripe key", "\\b[sr]k_live_[A-Za-z0-9]{10,}"),
            rule("webhook secret", "\\bwhsec_[A-Za-z0-9+/=]{10,}"),
            rule("GitHub fine-grained token", "\\bgithub_pat_[A-Za-z0-9_]{20,}"),
            rule("GitLab token", "\\bglpat-[A-Za-z0-9_-]{16,}"),
            rule("npm token", "\\bnpm_[A-Za-z0-9]{30,}"),
            rule("Hugging Face token", "\\bhf_[A-Za-z0-9]{30,}"),
            rule("Google API key", "\\bAIza[0-9A-Za-z_-]{30,}"),
            rule("AWS temporary key", "\\bASIA[0-9A-Z]{16}\\b"),
            rule("AWS access key", "\\bAKIA[0-9A-Z]{16}\\b"),
            rule("Alibaba Cloud key", "\\bLTAI[A-Za-z0-9]{12,}"),
            rule("Tencent Cloud key", "\\bAKID[A-Za-z0-9]{13,}"),
            rule("Baidu Cloud key", "\\bALTAK[A-Za-z0-9]{15,}"),
            rule("Volcengine key", "\\bAKLT[A-Za-z0-9_-]{20,}"),
            rule("JWT", "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            rule("password in URL", "://[^/\\s:@]+:[^/\\s@]+@"),
            rule("GitHub token", "\\bgh[pousr]_[A-Za-z0-9]{30,}"),
            rule("Slack token", "\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
            rule("bearer token", "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{20,}"),
            rule("secret assignment",
                    "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)\\b\\s*[:=]\\s*[\"']?\\S{6,}"),
            rule("PEM private key", "-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            new Rule(ID_NUMBER, Pattern.compile("(?<![0-9A-Za-z])[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?![0-9A-Za-z])"),
                    true),
            rule(PHONE, "(?<![0-9])(?:\\+?86[- ]?)?1[3-9]\\d[- ]?\\d{4}[- ]?\\d{4}(?![0-9])"));

    private static final SensitiveText DEFAULTS = new SensitiveText(BUILT_IN);
    private static volatile SensitiveText current = DEFAULTS;

    private final List<Rule> rules;

    private SensitiveText(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    private static Rule rule(String label, String regex) {
        return new Rule(label, Pattern.compile(regex), false);
    }

    /** 内置规则，去掉 {@code off} 里的，加上 {@code extra}（名字 → 正则）。正则写错时抛 {@link IllegalArgumentException}。 */
    public static SensitiveText of(Set<String> off, Map<String, String> extra) {
        List<Rule> rules = new ArrayList<>();
        BUILT_IN.stream().filter(rule -> !off.contains(rule.label())).forEach(rules::add);
        extra.forEach((label, regex) -> {
            try {
                rules.add(rule(label, regex));
            } catch (java.util.regex.PatternSyntaxException wrong) {
                throw new IllegalArgumentException("tantalum.sensitive.patterns." + label + " is not a valid regular expression", wrong);
            }
        });
        return new SensitiveText(rules);
    }

    /** 服务器配置好的检测器；没配置时是内置规则。 */
    public static SensitiveText current() {
        return current;
    }

    /** 服务器启动时按配置设一次。 */
    public static void configure(SensitiveText configured) {
        current = configured == null ? DEFAULTS : configured;
    }

    /** 命中的规则名（给日志和拒绝原因，不含内容）；空表示通过。 */
    public Optional<String> detect(String... texts) {
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (Rule rule : rules) {
                Matcher matcher = rule.pattern().matcher(text);
                while (matcher.find()) {
                    if (!rule.idNumber() || validId(matcher.group())) {
                        return Optional.of(rule.label());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** 18 位身份证号的校验位（GB 11643）。 */
    static boolean validId(String id) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        String checks = "10X98765432";
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (id.charAt(i) - '0') * weights[i];
        }
        return Character.toUpperCase(id.charAt(17)) == checks.charAt(sum % 11);
    }
}
