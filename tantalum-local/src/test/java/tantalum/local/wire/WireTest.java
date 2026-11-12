package tantalum.local.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tantalum.local.tool.LocalImage;
import tantalum.local.tool.LocalResult;
import tantalum.value.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 连接用的消息（local-workspace 设计“tantalum.local.wire”）：字段和设计的表一致、往返不变、能向前兼容、不带令牌。 */
class WireTest {

    private static final JsonMapper PLAIN = JsonMapper.builder().build();

    private static Json arguments() {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("path", new Json.Str("src/App.java"));
        fields.put("offset", new Json.Num(new BigDecimal("10")));
        fields.put("ratio", new Json.Num(new BigDecimal("1.50")));
        fields.put("flags", new Json.Arr(List.of(new Json.Bool(true), new Json.Null())));
        return new Json.Obj(fields);
    }

    private static Hello hello() {
        return new Hello(Wire.PROTOCOL, "0.1.0", new Hello.Device("Yi's MacBook", "macos", "26.2", "arm64"),
                List.of(new Hello.Root("/Users/yi/work/shop"), new Hello.Root("/Users/yi/.m2")), true,
                new Hello.Shell(false, "--allow-shell was not given"), true, true);
    }

    private static Hello helloWith(Hello.Browser browser) {
        Hello hello = hello();
        return new Hello(hello.protocol(), hello.connector(), hello.device(), hello.roots(), hello.write(), hello.shell(),
                hello.network(), hello.git(), browser);
    }

    /** local-browser 需求 1.5、4.5：没有浏览器这一项、图片没有名字时，帧和协议 4 的逐字节相同；老的帧照常能读。 */
    @Test
    void withoutTheBrowserFieldsFramesAreWhatProtocol4Sent() throws Exception {
        assertEquals(Set.of("type", "protocol", "connector", "device", "roots", "write", "shell", "network", "git"), keys(hello()));
        assertEquals("{\"type\":\"result\",\"id\":\"c\",\"text\":\"t\",\"error\":false,\"images\":[{\"mime\":\"image/png\",\"base64\":\"AQID\"}]}",
                Wire.encode(new Result("c", "t", false, List.of(new Result.Image("image/png", "AQID")))));
        Hello old = (Hello) Wire.decode(Wire.encode(hello()));
        assertEquals(null, old.browser());
        JsonNode browser = PLAIN.readTree(Wire.encode(helloWith(Hello.Browser.available("Google Chrome", true)))).get("browser");
        assertEquals(Set.of("available", "name", "headless"), Set.copyOf(browser.propertyNames()));
        JsonNode missing = PLAIN.readTree(Wire.encode(helloWith(Hello.Browser.unavailable(Hello.Browser.NOT_ALLOWED)))).get("browser");
        assertEquals("browser/not-allowed", missing.get("code").asString());
        assertEquals(false, missing.get("available").asBoolean());
        assertEquals("screenshot-a.png", ((Result) Wire.decode(Wire.encode(new Result("c", "t", false,
                List.of(new Result.Image("image/png", "AQID", "screenshot-a.png")))))).toLocal().images().getFirst().name());
    }

    static Stream<Message> messages() {
        return Stream.of(
                hello(),
                new Welcome("yi", Instant.parse("2026-11-06T03:00:00Z")),
                new Call("c-1", "yi", "s-1a2b", "read_file", arguments(), 60_000),
                new Result("c-1", "line 1", false, List.of()),
                new Result("c-2", "Image a.png (image/png, 3 bytes).", false, List.of(new Result.Image("image/png", "AQID"))),
                new Result("c-4", "Screenshot of https://a.example/", false,
                        List.of(new Result.Image("image/png", "AQID", "screenshot-a.example.png"))),
                helloWith(Hello.Browser.available("Google Chrome", false)),
                helloWith(Hello.Browser.unavailable(Hello.Browser.NOT_FOUND)),
                new Cancel("c-1"),
                new Call("c-3", "yi", "s-1a2b", "bash", arguments(), 60_000, "C:\\APPL\\Quattro"),
                new Context("x-1"),
                new Context("x-3", "/Users/yi/work/shop/app"),
                new ContextResult("x-1", "# Rules\nUse tabs.\n"),
                new Browse("b-1", null),
                new Browse("b-2", "/Users/yi/work"),
                new BrowseResult("b-2", "/Users/yi/work", List.of("/Users/yi/work/app", "/Users/yi/work/shop"), false, null),
                new BrowseResult("b-3", null, List.of(), false, "path not found in the allowed directories: /etc"),
                new ContextResult("x-2", null),
                new Context("x-4", "/w", "~/.agents/skills", "/Users/yi/wiki"),
                new ContextResult("x-4", null, skills(), wiki()),
                new ContextResult("x-5", "a", SkillList.unusable(DirState.OUTSIDE), WikiList.unusable(DirState.MISSING)),
                new Call("c-4", "yi", "s-1a2b", "skill", arguments(), 60_000, null, "~/.agents/skills"),
                new SkillLoad("k-1", "s-1a2b", "~/.agents/skills", List.of("pdf-tools", "nope")),
                new SkillLoaded("k-1", List.of(new SkillLoaded.Loaded("pdf-tools", "<skill_content name=\"pdf-tools\">…</skill_content>", null),
                        new SkillLoaded.Loaded("nope", null, null), new SkillLoaded.Loaded("broken", null, "yaml-invalid"))),
                new Bye(Bye.Reason.REPLACED),
                withCode());
    }

    private static SkillList skills() {
        return new SkillList("/Users/yi/.agents/skills", DirState.OK,
                List.of(new SkillList.Item("pdf-tools", "Extract text from PDFs", true, true),
                        new SkillList.Item("release", "Cut a release", false, true)),
                List.of(new SkillList.Problem("Bad Name", "name-invalid", "Bad Name"), new SkillList.Problem("empty", "description-missing", null)));
    }

    private static WikiList wiki() {
        return new WikiList("/Users/yi/wiki", DirState.OK,
                List.of(new WikiList.Page("deploy/rollback.md", "Rolling back"), new WikiList.Page("notes.md", null)), 2, "# Start here\n");
    }

    /** console-i18n I12：新版连接程序在 shell 里加上原因的代码和参数，老服务器不认识的字段照常忽略。 */
    private static Hello withCode() {
        Hello hello = hello();
        return new Hello(hello.protocol(), hello.connector(), hello.device(), hello.roots(), hello.write(),
                new Hello.Shell(false, "MXC could not start the sandbox: denied", "sandbox/start-failed", Map.of("detail", "denied")),
                hello.network(), hello.git());
    }

    @ParameterizedTest
    @MethodSource("messages")
    void everyMessageSurvivesARoundTrip(Message message) {
        assertEquals(message, Wire.decode(Wire.encode(message)));
    }

    /** 字段名和设计里的表逐个对上，服务器和连接程序以后各自实现时不会对不上。 */
    @Test
    void fieldNamesMatchTheDesign() {
        assertEquals(Set.of("type", "protocol", "connector", "device", "roots", "write", "shell", "network", "git"), keys(hello()));
        JsonNode hello = PLAIN.readTree(Wire.encode(hello()));
        assertEquals(new TreeSet<>(Set.of("name", "os", "osVersion", "arch")), new TreeSet<>(hello.get("device").propertyNames()));
        // 协议 2（B1）：是不是仓库不再按目录报，只报电脑上有没有 git
        assertEquals(new TreeSet<>(Set.of("path")), new TreeSet<>(hello.get("roots").get(0).propertyNames()));
        assertEquals(new TreeSet<>(Set.of("available", "reason")), new TreeSet<>(hello.get("shell").propertyNames()));
        assertEquals(new TreeSet<>(Set.of("available", "reason", "code", "params")),
                new TreeSet<>(PLAIN.readTree(Wire.encode(withCode())).get("shell").propertyNames()));
        assertEquals(Set.of("type", "user", "expiresAt"), keys(new Welcome("yi", Instant.EPOCH)));
        assertEquals(Set.of("type", "id", "user", "session", "tool", "arguments", "timeoutMs"),
                keys(new Call("c", "yi", "s", "grep", arguments(), 1)));
        assertEquals(Set.of("type", "id", "text", "error", "images"), keys(new Result("c", "", true, List.of())));
        assertEquals(Set.of("type", "id"), keys(new Cancel("c")));
        assertEquals(Set.of("type", "id", "user", "session", "tool", "arguments", "timeoutMs", "dir"),
                keys(new Call("c", "yi", "s", "grep", arguments(), 1, "/w")));
        assertEquals(Set.of("type", "id"), keys(new Context("x")));
        assertEquals(Set.of("type", "id", "dir"), keys(new Context("x", "/w")));
        assertEquals(Set.of("type", "id"), keys(new Browse("b", null)));
        assertEquals(Set.of("type", "id", "path"), keys(new Browse("b", "/w")));
        assertEquals(Set.of("type", "id", "path", "dirs", "truncated"), keys(new BrowseResult("b", "/w", List.of("/w/a"), true, null)));
        assertEquals(Set.of("type", "id", "dirs", "truncated", "error"), keys(new BrowseResult("b", null, List.of(), false, "nope")));
        assertEquals(Set.of("type", "id", "agents"), keys(new ContextResult("x", "a")));
        assertEquals(Set.of("type", "reason"), keys(new Bye(Bye.Reason.EXPIRED)));
        // 协议 4：技能和 wiki
        assertEquals(Set.of("type", "id", "dir", "skills", "wiki"), keys(new Context("x", "/w", "/s", "/k")));
        assertEquals(Set.of("type", "id", "skills"), keys(new Context("x", null, "/s", null)));
        assertEquals(Set.of("type", "id", "agents", "skills", "wiki"), keys(new ContextResult("x", "a", skills(), wiki())));
        assertEquals(Set.of("type", "id", "user", "session", "tool", "arguments", "timeoutMs", "skills"),
                keys(new Call("c", "yi", "s", "skill", arguments(), 1, null, "/s")));
        assertEquals(Set.of("type", "id", "session", "skills", "names"), keys(new SkillLoad("k", "s", "/s", List.of("a"))));
        assertEquals(Set.of("type", "id", "items"), keys(new SkillLoaded("k", List.of())));
        JsonNode result = PLAIN.readTree(Wire.encode(new ContextResult("x", null, skills(), wiki())));
        assertEquals(new TreeSet<>(Set.of("dir", "state", "items", "problems")), new TreeSet<>(result.get("skills").propertyNames()));
        assertEquals(new TreeSet<>(Set.of("name", "description", "model", "user")),
                new TreeSet<>(result.get("skills").get("items").get(0).propertyNames()));
        assertEquals(new TreeSet<>(Set.of("folder", "code", "detail")),
                new TreeSet<>(result.get("skills").get("problems").get(0).propertyNames()));
        assertEquals(new TreeSet<>(Set.of("dir", "state", "pages", "total", "index")), new TreeSet<>(result.get("wiki").propertyNames()));
        assertEquals("OK", result.get("skills").get("state").asString());
    }

    /** 没带新字段时，写出来的和协议 3 一字节不差：对老的连接程序发的就是这些。 */
    @Test
    void withoutTheNewFieldsFramesAreWhatProtocol3Sent() {
        assertEquals("{\"type\":\"context\",\"id\":\"x\",\"dir\":\"/w\"}", Wire.encode(new Context("x", "/w")));
        assertEquals("{\"type\":\"contextResult\",\"id\":\"x\",\"agents\":null}", Wire.encode(new ContextResult("x", null)));
        assertEquals("{\"type\":\"call\",\"id\":\"c\",\"user\":\"yi\",\"session\":\"s\",\"tool\":\"list_dir\",\"arguments\":{},\"timeoutMs\":5,\"dir\":\"/w\"}",
                Wire.encode(new Call("c", "yi", "s", "list_dir", new Json.Obj(Map.of()), 5, "/w")));
        // 协议 3 的连接程序回来的帧照常解码
        assertEquals(new ContextResult("x", "a", null, null), Wire.decode("{\"type\":\"contextResult\",\"id\":\"x\",\"agents\":\"a\"}"));
    }

    private static Set<String> keys(Message message) {
        return new TreeSet<>(PLAIN.readTree(Wire.encode(message)).propertyNames());
    }

    @Test
    void typesAndValuesAreWrittenAsTheDesignSays() {
        assertEquals("contextResult", PLAIN.readTree(Wire.encode(new ContextResult("x", null))).get("type").asString());
        assertEquals("hello", PLAIN.readTree(Wire.encode(hello())).get("type").asString());
        assertEquals(5, PLAIN.readTree(Wire.encode(hello())).get("protocol").asInt());
        assertEquals("browseResult", PLAIN.readTree(Wire.encode(new BrowseResult("b", null, List.of(), false, null))).get("type").asString());
        for (Bye.Reason reason : Bye.Reason.values()) {
            assertEquals(reason.name().toLowerCase(Locale.ROOT), PLAIN.readTree(Wire.encode(new Bye(reason))).get("reason").asString());
        }
        assertEquals("2026-11-06T03:00:00Z",
                PLAIN.readTree(Wire.encode(new Welcome("yi", Instant.parse("2026-11-06T03:00:00Z")))).get("expiresAt").asString());
    }

    /** 模型给的参数原样过去：键的顺序和数字的写法都不变（1.50 不变成 1.5）。 */
    @Test
    void argumentsKeepOrderAndNumberText() {
        String encoded = Wire.encode(new Call("c", "yi", "s", "read_file", arguments(), 1));
        assertTrue(encoded.contains("\"arguments\":{\"path\":\"src/App.java\",\"offset\":10,\"ratio\":1.50,\"flags\":[true,null]}"), encoded);
        Call missing = (Call) Wire.decode("{\"type\":\"call\",\"id\":\"c\",\"user\":\"yi\",\"session\":\"s\",\"tool\":\"list_dir\",\"timeoutMs\":5}");
        assertEquals(new Json.Obj(Map.of()), missing.arguments());
    }

    /** 以后加的字段，旧的一方不认识也照常解码。 */
    @Test
    void unknownFieldsAreIgnored() {
        assertEquals(new Cancel("c-9"), Wire.decode("{\"type\":\"cancel\",\"id\":\"c-9\",\"reason\":\"later\",\"extra\":{\"x\":[1,2]}}"));
        Hello decoded = (Hello) Wire.decode(Wire.encode(hello()).replace("\"write\":true", "\"write\":true,\"gpu\":\"m4\""));
        assertEquals(hello(), decoded);
    }

    /** 别的实现不一定把 type 写在最前面。 */
    @Test
    void typeMayComeLast() {
        Call call = (Call) Wire.decode("{\"id\":\"c\",\"arguments\":{\"path\":\"a\",\"n\":[1,{\"x\":null}]},\"user\":\"yi\",\"session\":\"s\","
                + "\"tool\":\"read_file\",\"timeoutMs\":5,\"type\":\"call\"}");
        assertEquals("{\"path\":\"a\",\"n\":[1,{\"x\":null}]}",
                Wire.encode(call).replaceAll(".*\"arguments\":(\\{.*\\}),\"timeoutMs\".*", "$1"));
    }

    @Test
    void unknownOrMissingTypesAndBrokenFramesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Wire.decode("{\"type\":\"setAllowedDirs\",\"dirs\":[\"/\"]}"));
        assertThrows(IllegalArgumentException.class, () -> Wire.decode("{\"id\":\"c\"}"));
        assertThrows(IllegalArgumentException.class, () -> Wire.decode("not json"));
        assertThrows(IllegalArgumentException.class, () -> Wire.decode("{\"type\":\"cancel\"}"));
        assertThrows(IllegalArgumentException.class, () -> Wire.decode("{\"type\":\"bye\",\"reason\":\"bored\"}"));
    }

    /** 图片的字节用 base64 放进 result，还原后一字节不差。 */
    @Test
    void imagesTravelAsBase64() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        LocalResult local = LocalResult.image("Image a.png (image/png, 256 bytes).", new LocalImage("image/png", bytes));
        String encoded = Wire.encode(Result.of("c-3", local));
        JsonNode image = PLAIN.readTree(encoded).get("images").get(0);
        assertEquals("image/png", image.get("mime").asString());
        assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), image.get("base64").asString());
        LocalResult back = ((Result) Wire.decode(encoded)).toLocal();
        assertEquals(local.text(), back.text());
        assertFalse(back.error());
        assertArrayEquals(bytes, back.images().getFirst().bytes());
        assertEquals("image/png", back.images().getFirst().mime());
    }

    /** L18：令牌只在握手头里。任何消息、任何嵌套的记录都没有叫 token 的字段。 */
    @Test
    void noMessageCarriesAToken() {
        List<String> names = new ArrayList<>();
        for (Class<?> type : Message.class.getPermittedSubclasses()) {
            collect(type, names);
        }
        assertTrue(names.size() > 20, names.toString());
        assertTrue(names.stream().noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("token")), names.toString());
    }

    private static void collect(Class<?> type, List<String> names) {
        for (RecordComponent component : type.getRecordComponents()) {
            names.add(type.getSimpleName() + "." + component.getName());
            Class<?> nested = component.getType();
            if (component.getGenericType() instanceof java.lang.reflect.ParameterizedType list) {
                nested = (Class<?>) list.getActualTypeArguments()[0];
            }
            if (nested.isRecord()) {
                collect(nested, names);
            }
        }
    }

    /** 关闭码是和连接程序的约定，改了旧的连接程序就不知道该不该重连。 */
    @Test
    void closeCodesAndProtocolArePinned() {
        // 2：hello.roots[].git 换成 hello.git（B1，2026-11-06），旧的连接程序被拒绝并提示下载新的
        // 3：会话的工作目录和列子目录（workspaces，2026-11-10）；服务器仍接受 2
        // 4：技能和 wiki（skills-wiki，2026-11-12）；服务器仍接受 2、3
        // 5：浏览器搬到电脑上（local-browser，2026-11-12）；服务器仍接受 2–4
        assertEquals(5, Wire.PROTOCOL);
        assertEquals(2, Wire.OLDEST);
        assertEquals(List.of(4400, 4401, 4403, 4409),
                List.of(Wire.CLOSE_PROTOCOL, Wire.CLOSE_BAD_TOKEN, Wire.CLOSE_EXPIRED, Wire.CLOSE_REPLACED));
    }
}
