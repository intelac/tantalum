package tantalum.shell.mask;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把文字里常见形式的密钥换成 {@value #REDACTED}（QAgent 问题 67：工具读到的裸密钥原样送给模型、写进记录、显示在页面上）。
 * 纯函数，只认形式，不查真假：
 * <ul>
 *   <li>有固定前缀的：Anthropic/OpenAI 的 {@code sk-}、AWS 的 {@code AKIA}/{@code ASIA}、GitHub 的 {@code ghp_}、{@code github_pat_}、
 *       Slack 的 {@code xox?-}、Google 的 {@code AIza}、JWT（{@code eyJ…}.{@code …}.{@code …}）、PEM 私钥块；</li>
 *   <li>网址里的口令（{@code postgres://app:口令@host}）；</li>
 *   <li>赋值：键名里带 password、secret、token、api key、relay key、private key、client secret 这类词，后面 {@code =} 或 {@code :}
 *       跟着的值。只换值，键名和引号留着，模型知道“这里原来有个密钥”。值太短、是变量引用（{@code $X}、{@code ${x}}）、函数调用、
 *       纯小写单词（{@code required}、{@code none}）的不换，免得读代码时把 {@code token = request.header(...)} 也遮掉。</li>
 * </ul>
 */
public final class SecretMask {

    public static final String REDACTED = "[REDACTED]";

    private static final List<Pattern> PREFIXED = List.of(
            Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("\\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
            Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}\\b"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{22,}"),
            Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"));

    /** 键名、分隔（可以带引号和空白）、可选的开引号、值。值不含空白、引号、逗号、分号、括号。 */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)([A-Za-z0-9_.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|apikey|access[_-]?key|relay[_-]?key|private[_-]?key"
                    + "|auth[_-]?key)[A-Za-z0-9_.-]*[\"']?\\s*[:=]\\s*)([\"']?)([^\\s\"'`,;(){}<>\\[\\]]{6,})");

    /** 网址里的口令：{@code postgres://app:口令@db.internal/x}、{@code https://user:口令@host}。只换口令，用户名和主机留着。 */
    private static final Pattern URL_PASSWORD = Pattern.compile("(\\b[a-zA-Z][a-zA-Z0-9+.-]*://[^:/\\s@]+:)([^@\\s/]+)(@)");

    private SecretMask() {}

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        for (Pattern pattern : PREFIXED) {
            masked = pattern.matcher(masked).replaceAll(REDACTED);
        }
        masked = URL_PASSWORD.matcher(masked).replaceAll(match -> Matcher.quoteReplacement(
                match.group(2).equals(REDACTED) ? match.group() : match.group(1) + REDACTED + match.group(3)));
        Matcher assignment = ASSIGNMENT.matcher(masked);
        StringBuilder out = new StringBuilder();
        while (assignment.find()) {
            String value = assignment.group(3);
            String replacement = secretLooking(value) ? assignment.group(1) + assignment.group(2) + REDACTED : assignment.group();
            assignment.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        assignment.appendTail(out);
        return out.toString();
    }

    /** 值像密钥：不是已经遮掉的、不是变量引用、不是纯小写单词或标识符调用、不是全是星号。 */
    private static boolean secretLooking(String value) {
        if (value.startsWith(REDACTED) || value.startsWith("$") || value.startsWith("%") || value.chars().allMatch(c -> c == '*')) {
            return false;
        }
        if (value.matches("[a-z_]+") || value.matches("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_.]*")) {
            return false;
        }
        return true;
    }
}
