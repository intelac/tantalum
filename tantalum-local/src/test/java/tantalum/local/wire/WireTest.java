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

    static Stream<Message> messages() {
        return Stream.of(
                hello(),
                new Welcome("yi", Instant.parse("2026-11-06T03:00:00Z")),
                new Call("c-1", "yi", "s-1a2b", "read_file", arguments(), 60_000),
                new Result("c-1", "line 1", false, List.of()),
                new Result("c-2", "Image a.png (image/png, 3 bytes).", false, List.of(new Result.Image("image/png", "AQID"))),
                new Cancel("c-1"),
                new Context("x-1"),
                new ContextResult("x-1", "# Rules\nUse tabs.\n"),
                new ContextResult("x-2", null),
                new Bye(Bye.Reason.REPLACED),
                withCode());
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
        assertEquals(Set.of("type", "id"), keys(new Context("x")));
        assertEquals(Set.of("type", "id", "agents"), keys(new ContextResult("x", "a")));
        assertEquals(Set.of("type", "reason"), keys(new Bye(Bye.Reason.EXPIRED)));
    }

    private static Set<String> keys(Message message) {
        return new TreeSet<>(PLAIN.readTree(Wire.encode(message)).propertyNames());
    }

    @Test
    void typesAndValuesAreWrittenAsTheDesignSays() {
        assertEquals("contextResult", PLAIN.readTree(Wire.encode(new ContextResult("x", null))).get("type").asString());
        assertEquals("hello", PLAIN.readTree(Wire.encode(hello())).get("type").asString());
        assertEquals(2, PLAIN.readTree(Wire.encode(hello())).get("protocol").asInt());
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
        assertEquals(2, Wire.PROTOCOL);
        assertEquals(List.of(4400, 4401, 4403, 4409),
                List.of(Wire.CLOSE_PROTOCOL, Wire.CLOSE_BAD_TOKEN, Wire.CLOSE_EXPIRED, Wire.CLOSE_REPLACED));
    }
}
