package tantalum.local.sandbox;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 沙箱用不了的原因：代码加参数。连接程序的终端按它的语言说，插件页按看的人的语言说；
 * 给模型和老版本服务器的是 {@link #english()}，由下面这张英文表生成，不经资源文件，不随连接程序的语言变。
 * {@code detail} 是 MXC 或操作系统的输出，原样放进去。
 */
public record Unavailable(Code code, Map<String, String> params) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)}");

    public enum Code {
        NOT_MACOS("sandbox/not-macos", "MXC's sandbox program " + Mxc.PROGRAM + " only runs on macOS"),
        MXC_INVALID("sandbox/mxc-invalid", "--mxc {path} does not exist or is not executable"),
        MXC_MISSING("sandbox/mxc-missing", "cannot find the MXC sandbox program " + Mxc.PROGRAM
                + ": start tantalum-connect from the installer, or point --mxc at it"),
        TEMP_DIR("sandbox/temp-dir", "cannot prepare a temporary directory for the sandbox: {detail}"),
        CHECK_FAILED("sandbox/check-failed", "MXC's policy check failed: {detail}"),
        START_FAILED("sandbox/start-failed", "MXC could not start the sandbox: {detail}"),
        RUN_FAILED("sandbox/run-failed", "MXC could not run: {detail}"),
        CHECK_INTERRUPTED("sandbox/check-interrupted", "the MXC check was interrupted"),
        /** 老版本的服务器和控制台按这句英文认出“没有 --allow-shell”，所以字一个不改。 */
        SHELL_NOT_ALLOWED("shell/not-allowed", "--allow-shell was not given");

        private final String wire;
        private final String english;

        Code(String wire, String english) {
            this.wire = wire;
            this.english = english;
        }

        /** 连接消息和接口里的写法，如 {@code sandbox/not-macos}。 */
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
