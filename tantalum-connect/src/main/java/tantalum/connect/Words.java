package tantalum.connect;

import java.util.HashMap;
import java.util.Map;
import tantalum.local.sandbox.Mxc;
import tantalum.local.sandbox.Unavailable;
import tantalum.value.text.Bundle;
import tantalum.value.text.Language;

/**
 * 连接程序在终端里说的话：中、英、日三份资源文件 {@code tantalum/connect/Text*.properties}。
 * 语言由 {@code --lang} 或系统决定（{@link Main}）。每次调用的记录行（{@link Journal}）和给模型的工具结果不经这里。
 */
final class Words {

    static final String BUNDLE = "tantalum.connect.Text";

    /** 资源文件里的键：常量名小写、下划线换成点（{@link Bundle#key}）。 */
    enum Key {
        HELP,
        REFUSED_TOKEN_ON_COMMAND_LINE,
        REFUSED_UNKNOWN_OPTION,
        REFUSED_NO_ALLOW,
        REFUSED_MISSING_VALUE,
        REFUSED_ALLOW_NOT_DIR,
        REFUSED_ALLOW_UNUSABLE,
        REFUSED_READ_NOT_DIR,
        REFUSED_ENV_SECRET,
        REFUSED_ENV_NAME,
        REFUSED_ROOT,
        REFUSED_HOME,
        REFUSED_SERVER_SCHEME,
        REFUSED_SERVER_ADDRESS,
        REFUSED_SERVER_INSECURE,
        TOKEN_PROMPT,
        REFUSED_NO_TOKEN,
        REFUSED_NOT_A_TOKEN,
        SUMMARY_SERVER,
        SUMMARY_DEVICE,
        SUMMARY_MAIN_DIR,
        SUMMARY_DIR,
        SUMMARY_GIT_YES,
        SUMMARY_GIT_NO,
        SUMMARY_WRITE_YES,
        SUMMARY_WRITE_NO,
        SUMMARY_SHELL_YES,
        SUMMARY_SHELL_NO,
        SUMMARY_SANDBOX,
        SUMMARY_NETWORK_YES,
        SUMMARY_NETWORK_NO,
        SUMMARY_READ,
        SUMMARY_ENV,
        SUMMARY_FOOTER,
        LINK_RETRY,
        LINK_SHUTDOWN,
        LINK_LOST,
        LINK_BAD_TOKEN,
        LINK_REVOKED,
        LINK_EXPIRED,
        LINK_REPLACED,
        LINK_PROTOCOL,
        LINK_UNREADABLE,
        LINK_CONNECTED,
        SANDBOX_NOT_MACOS,
        SANDBOX_MXC_INVALID,
        SANDBOX_MXC_MISSING,
        SANDBOX_TEMP_DIR,
        SANDBOX_CHECK_FAILED,
        SANDBOX_START_FAILED,
        SANDBOX_RUN_FAILED,
        SANDBOX_CHECK_INTERRUPTED,
        SHELL_NOT_ALLOWED
    }

    private final Bundle bundle;

    Words(Language language) {
        this.bundle = Bundle.load(BUNDLE, language);
    }

    Language language() {
        return bundle.language();
    }

    String say(Key key) {
        return bundle.text(key);
    }

    String say(Key key, Map<String, ?> params) {
        return bundle.text(key, params);
    }

    /** 沙箱用不了的原因，按这个终端的语言说；{@code detail} 等参数是 MXC 或系统的输出，原样放进去。 */
    String sandbox(Unavailable why) {
        Key key = switch (why.code()) {
            case NOT_MACOS -> Key.SANDBOX_NOT_MACOS;
            case MXC_INVALID -> Key.SANDBOX_MXC_INVALID;
            case MXC_MISSING -> Key.SANDBOX_MXC_MISSING;
            case TEMP_DIR -> Key.SANDBOX_TEMP_DIR;
            case CHECK_FAILED -> Key.SANDBOX_CHECK_FAILED;
            case START_FAILED -> Key.SANDBOX_START_FAILED;
            case RUN_FAILED -> Key.SANDBOX_RUN_FAILED;
            case CHECK_INTERRUPTED -> Key.SANDBOX_CHECK_INTERRUPTED;
            case SHELL_NOT_ALLOWED -> Key.SHELL_NOT_ALLOWED;
        };
        Map<String, Object> params = new HashMap<>(why.params());
        params.put("program", Mxc.PROGRAM);
        return say(key, params);
    }
}
