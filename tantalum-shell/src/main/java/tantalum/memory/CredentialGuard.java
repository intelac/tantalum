package tantalum.memory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 记忆内容进 system 等于进每次模型请求和 journal，所以看起来像凭据或身份信息的内容一律拒收（memory-final-plan 第 7 节的统一检测）。
 * 只看特征，不做语义判断；拒绝原因里绝不回显被检查的内容。
 */
public final class CredentialGuard {

    public static final String REASON = "memory must not contain credentials or personal identifiers (API keys, tokens, passwords, "
            + "private keys, ID numbers, phone numbers)";

    private CredentialGuard() {}

    /** 返回命中的特征名（给日志用，不含内容）；空表示通过。规则是平台统一的（{@link tantalum.shell.plugin.SensitiveText}）。 */
    public static Optional<String> detect(String... texts) {
        return tantalum.shell.plugin.SensitiveText.current().detect(texts);
    }

}
