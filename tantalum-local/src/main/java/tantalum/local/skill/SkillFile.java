package tantalum.local.skill;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import tantalum.local.wire.SkillList;

/**
 * 一个读好、校验过的技能（skills-wiki 需求 1）。{@code base} 是技能目录的真实路径，{@code body} 是 {@code SKILL.md} 去掉属性块之后的正文。
 */
record SkillFile(String name, String description, boolean model, boolean user, Path base, String body) {

    static final String FILE = "SKILL.md";
    /** 两边都没有上限，DSH 把“正文没有大小上限”列为缺陷。 */
    static final int MAX_BYTES = 256 * 1024;
    /** 清单里服务器只用前 500 个字符；这里只防止一个离谱的描述撑大每次往返。 */
    static final int MAX_DESCRIPTION = 1024;
    static final int MAX_NAME = 64;

    /** 小写字母、数字、连字符；不以连字符开头结尾，没有连续的连字符（pi {@code skills.ts:95-107}）。 */
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern BLANKS = Pattern.compile("\\s+");

    static boolean validName(String name) {
        return name != null && name.length() <= MAX_NAME && NAME.matcher(name).matches();
    }

    /** 没通过校验：{@code code} 见 {@link SkillList.Problem}。 */
    static final class Rejected extends Exception {

        final String code;
        final String detail;

        Rejected(String code, String detail) {
            super(code);
            this.code = code;
            this.detail = detail;
        }
    }

    /** 读 {@code file}（已经确认在允许的目录里的真实路径）。{@code folder} 是子目录名，没写 {@code name} 时用它。 */
    static SkillFile read(Path file, String folder) throws Rejected {
        String text = text(file);
        FrontMatter.Parsed parsed;
        try {
            parsed = FrontMatter.parse(text);
        } catch (FrontMatter.Invalid invalid) {
            throw new Rejected("yaml-invalid", invalid.getMessage());
        }
        Map<String, Object> fields = parsed.fields();
        Object named = fields.containsKey("name") ? fields.get("name") : folder;
        if (!(named instanceof String name) || !validName(name)) {
            throw new Rejected("name-invalid", String.valueOf(named));
        }
        boolean hidden = flag(fields, "disable-model-invocation", false);
        boolean invocable = flag(fields, "user-invocable", true);
        if (!(fields.get("description") instanceof String described) || described.isBlank()) {
            throw new Rejected("description-missing", null);
        }
        String description = BLANKS.matcher(described.strip()).replaceAll(" ");
        if (description.length() > MAX_DESCRIPTION) {
            description = description.substring(0, description.offsetByCodePoints(0, description.codePointCount(0, MAX_DESCRIPTION)));
        }
        return new SkillFile(name, description, !hidden, invocable, file.getParent(), parsed.body().strip());
    }

    /** 写了但不是布尔的开关不按默认值放行，整个技能不加载（DSH：失败时默认拒绝）。 */
    private static boolean flag(Map<String, Object> fields, String key, boolean fallback) throws Rejected {
        if (!fields.containsKey(key)) {
            return fallback;
        }
        if (fields.get(key) instanceof Boolean value) {
            return value;
        }
        throw new Rejected("flag-not-boolean", key);
    }

    private static String text(Path file) throws Rejected {
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_BYTES) {
                throw new Rejected("too-large", null);
            }
            bytes = Files.readAllBytes(file);
        } catch (IOException failure) {
            throw new Rejected("unreadable", null);
        }
        if (bytes.length > MAX_BYTES) {
            throw new Rejected("too-large", null);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new Rejected("not-text", null);
        }
        if (text.indexOf('\0') >= 0) {
            throw new Rejected("not-text", null);
        }
        return text;
    }

    SkillList.Item item() {
        return new SkillList.Item(name, description, model, user);
    }
}
