package tantalum.shell.memory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 记忆内容进 system 等于进每次模型请求和 journal，所以看起来像凭据的内容一律拒收。
 * 只看特征，不做语义判断；拒绝原因里绝不回显被检查的内容。
 */
public final class CredentialGuard {

    public static final String REASON = "memory must not contain credentials (API keys, tokens, passwords, private keys)";

    private record Rule(String label, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("Anthropic key", Pattern.compile("sk-ant-[A-Za-z0-9_-]{10,}")),
            new Rule("OpenAI-style key", Pattern.compile("\\bsk-(?=[A-Za-z0-9_-]*\\d)[A-Za-z0-9_-]{20,}")),
            new Rule("Stripe key", Pattern.compile("\\b[sr]k_live_[A-Za-z0-9]{10,}")),
            new Rule("webhook secret", Pattern.compile("\\bwhsec_[A-Za-z0-9+/=]{10,}")),
            new Rule("GitHub fine-grained token", Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}")),
            new Rule("GitLab token", Pattern.compile("\\bglpat-[A-Za-z0-9_-]{16,}")),
            new Rule("npm token", Pattern.compile("\\bnpm_[A-Za-z0-9]{30,}")),
            new Rule("Hugging Face token", Pattern.compile("\\bhf_[A-Za-z0-9]{30,}")),
            new Rule("Google API key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{30,}")),
            new Rule("AWS temporary key", Pattern.compile("\\bASIA[0-9A-Z]{16}\\b")),
            new Rule("Alibaba Cloud key", Pattern.compile("\\bLTAI[A-Za-z0-9]{12,}")),
            new Rule("Tencent Cloud key", Pattern.compile("\\bAKID[A-Za-z0-9]{13,}")),
            new Rule("JWT", Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")),
            new Rule("password in URL", Pattern.compile("://[^/\\s:@]+:[^/\\s@]+@")),
            new Rule("AWS access key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
            new Rule("GitHub token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}")),
            new Rule("Slack token", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}")),
            new Rule("bearer token", Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{20,}")),
            new Rule("secret assignment", Pattern.compile(
                    "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)\\b\\s*[:=]\\s*[\"']?\\S{6,}")),
            new Rule("PEM private key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")));

    private CredentialGuard() {}

    /** 返回命中的特征名（给日志用，不含内容）；空表示通过。 */
    public static Optional<String> detect(String... texts) {
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (Rule rule : RULES) {
                if (rule.pattern().matcher(text).find()) {
                    return Optional.of(rule.label());
                }
            }
        }
        return Optional.empty();
    }

    public static boolean looksLikeCredential(String... texts) {
        return detect(texts).isPresent();
    }
}
