package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.local.sandbox.Unavailable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * console-i18n 需求 4.4：服务器的错误代码和控制台共用一份清单 {@code console/src/api/errorCodes.json}；
 * 控制台的 {@code dictionaries.test.ts} 拿同一份和字典比对。两边任何一边加了代码没加另一边，这里或那里失败。
 */
class ErrorCodeTest {

    private static final Path SHARED = Path.of("../console/src/api/errorCodes.json");

    static List<String> listed(String group) throws Exception {
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(SHARED));
        List<String> out = new ArrayList<>();
        root.get(group).forEach(item -> out.add(item.asString()));
        return out;
    }

    @Test
    void theSharedListIsTheEnum() throws Exception {
        assertEquals(Arrays.stream(ErrorCode.values()).map(ErrorCode::wire).toList(), listed("errors"));
    }

    /** 本机沙箱用不了的原因（console-i18n I12）：连接程序送来代码，插件页按看的人的语言说。 */
    @Test
    void theSandboxGroupIsTheConnectorsCodes() throws Exception {
        assertEquals(Arrays.stream(Unavailable.Code.values()).map(Unavailable.Code::wire).toList(), listed("sandbox"));
    }

    @Test
    void codesAreDomainSlashReason() {
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(code.wire().matches("[a-z]+/[a-z]+(-[a-z]+)*"), code.wire());
        }
    }

    /**
     * 需求 4.1：控制器里每个错误都带代码、诊断是英文；每个代码都有地方用（ErrorCode.of 的映射也算）。
     * 接口测试逐个触发了带参数的和常见的代码，这里兜住其余的。
     */
    @Test
    void everyCodeIsUsedAndDiagnosticsAreEnglish() throws Exception {
        StringBuilder all = new StringBuilder();
        try (var files = Files.walk(Path.of("src/main/java/tantalum/shell"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                all.append(source);
                for (String line : source.split("\n")) {
                    boolean error = line.contains("json.error(") || line.contains("new AttachmentRejected(") || line.contains("Rejected(Problem.");
                    assertTrue(!error || !line.matches(".*[\\u3400-\\u9fff].*"), file + ": " + line.strip());
                }
            }
        }
        String code = all.toString().replace("enum ErrorCode", "");
        for (ErrorCode each : ErrorCode.values()) {
            String uses = code.replaceAll("(?s)public enum ErrorCode.*?;\\s*\\n\\s*private final String wire;", "");
            assertTrue(uses.contains("ErrorCode." + each.name()) || uses.contains("-> " + each.name() + ";"), each + " is never used");
        }
    }

    /** 没登录的 401 由 Spring Security 写，在 auth 包里写的是字面值，这里钉住和枚举一致。 */
    @Test
    void theSecurityFilterWritesTheSameCode() throws Exception {
        String source = Files.readString(Path.of("src/main/java/tantalum/shell/auth/SecurityConfig.java"));
        assertTrue(source.contains("\\\"code\\\":\\\"" + ErrorCode.AUTH_NOT_AUTHENTICATED.wire() + "\\\""), "SecurityConfig");
        assertTrue(source.contains("\\\"code\\\":\\\"" + ErrorCode.AUTH_BAD_ORIGIN.wire() + "\\\""), "OriginFilter");
        assertTrue(source.contains("\\\"code\\\":\\\"" + ErrorCode.AUTH_FORBIDDEN.wire() + "\\\""), "access denied");
    }
}
