package tantalum.shell.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.auth.UserStore;
import tantalum.shell.param.ParamResolver.Resolved;
import tantalum.shell.param.ParamResolver.Source;
import tantalum.shell.param.ParamStore.Scope;
import tantalum.shell.plugin.Kind;
import tantalum.shell.plugin.Param;
import tantalum.value.hook.Params;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

class ParamStoreTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);

    private static final Param TOKENS = Param.number("tokens", 1000).atLeast(1).adminOnly();
    private static final Param MAX_CHARS = Param.number("maxChars", 500).range(100, 10000);
    private static final Param TOKEN = Param.secret("token");
    private static final List<Param> DECLARED = List.of(TOKENS, MAX_CHARS, TOKEN);

    @TempDir
    Path data;

    private static SecretBox box(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return new SecretBox(key);
    }

    private static Json num(long value) {
        return new Json.Num(BigDecimal.valueOf(value));
    }

    private static Source source(List<Resolved> resolved, String key) {
        return resolved.stream().filter(item -> item.param().key().equals(key)).findFirst().orElseThrow().source();
    }

    @Test
    void valuesSurviveARestartAndAreKeptApart() throws Exception {
        try (ParamStore store = ParamStore.open(data, CLOCK, box(1))) {
            store.save(Scope.OWN, ALICE, Kind.PLUGIN, "demo", Map.of("maxChars", num(200)), Set.of());
            store.save(Scope.DEFAULT, null, Kind.PLUGIN, "demo", Map.of("maxChars", num(300)), Set.of());
            store.save(Scope.ASSIGNED, ALICE, Kind.PLUGIN, "demo", Map.of("tokens", num(50)), Set.of());
        }
        try (ParamStore store = ParamStore.open(data, CLOCK, box(1))) {
            assertEquals(Map.of("maxChars", num(200)), store.values(Scope.OWN, ALICE, Kind.PLUGIN, "demo"));
            assertEquals(Map.of("maxChars", num(300)), store.values(Scope.DEFAULT, null, Kind.PLUGIN, "demo"));
            assertEquals(Map.of("tokens", num(50)), store.values(Scope.ASSIGNED, ALICE, Kind.PLUGIN, "demo"));
            assertEquals(Map.of(), store.values(Scope.OWN, BOB, Kind.PLUGIN, "demo"));
            // 种类不同、名字相同是两个插件
            assertEquals(Map.of(), store.values(Scope.OWN, ALICE, Kind.TOOL, "demo"));
            store.save(Scope.OWN, ALICE, Kind.PLUGIN, "demo", Map.of("other", num(1)), Set.of("maxChars"));
            assertEquals(Map.of("other", num(1)), store.values(Scope.OWN, ALICE, Kind.PLUGIN, "demo"));
            store.clear(Scope.OWN, ALICE, Kind.PLUGIN, "demo");
        }
        try (ParamStore store = ParamStore.open(data, CLOCK, box(1))) {
            assertEquals(Map.of(), store.values(Scope.OWN, ALICE, Kind.PLUGIN, "demo"));
            assertEquals(Map.of("maxChars", num(300)), store.values(Scope.DEFAULT, null, Kind.PLUGIN, "demo"));
        }
    }

    @Test
    void secretsAreEncryptedOnDiskAndPerUser() throws Exception {
        try (ParamStore store = ParamStore.open(data, CLOCK, box(1))) {
            store.putSecret(ALICE, Kind.PLUGIN, "demo", "token", "hunter2-plain-text");
            assertEquals(Optional.of("hunter2-plain-text"), store.secret(ALICE, Kind.PLUGIN, "demo", "token"));
            assertEquals(Optional.empty(), store.secret(BOB, Kind.PLUGIN, "demo", "token"));
            assertTrue(store.hasSecret(ALICE, Kind.PLUGIN, "demo", "token"));
            assertFalse(store.stale(ALICE, Kind.PLUGIN, "demo", "token"));
        }
        // 数据库文件里找不到原文
        String raw = new String(Files.readAllBytes(data.resolve(UserStore.FILE)), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("hunter2"));
        try (ParamStore store = ParamStore.open(data, CLOCK, box(1))) {
            assertEquals(Optional.of("hunter2-plain-text"), store.secret(ALICE, Kind.PLUGIN, "demo", "token"));
            store.clearSecret(ALICE, Kind.PLUGIN, "demo", "token");
            assertFalse(store.hasSecret(ALICE, Kind.PLUGIN, "demo", "token"));
        }
    }

    /** 密钥换了：密码还在库里但解不开，页面要显示“重新填”，服务照常起来。 */
    @Test
    void aChangedKeyLeavesTheSecretStale() throws Exception {
        try (ParamStore store = ParamStore.open(data, CLOCK, box(1))) {
            store.putSecret(ALICE, Kind.PLUGIN, "demo", "token", "hunter2");
        }
        try (ParamStore store = ParamStore.open(data, CLOCK, box(2))) {
            assertTrue(store.hasSecret(ALICE, Kind.PLUGIN, "demo", "token"));
            assertTrue(store.stale(ALICE, Kind.PLUGIN, "demo", "token"));
            assertEquals(Optional.empty(), store.secret(ALICE, Kind.PLUGIN, "demo", "token"));
            store.putSecret(ALICE, Kind.PLUGIN, "demo", "token", "again");
            assertEquals(Optional.of("again"), store.secret(ALICE, Kind.PLUGIN, "demo", "token"));
        }
    }

    @Test
    void resolutionOrder() throws Exception {
        try (ParamStore store = ParamStore.inMemory(CLOCK, box(1))) {
            ParamResolver resolver = new ParamResolver(store);
            List<Resolved> builtin = resolver.resolve(ALICE, Kind.PLUGIN, "demo", DECLARED);
            // 密码不在里面
            assertEquals(List.of("tokens", "maxChars"), builtin.stream().map(item -> item.param().key()).toList());
            assertEquals(Source.BUILTIN, source(builtin, "tokens"));

            store.save(Scope.DEFAULT, null, Kind.PLUGIN, "demo", Map.of("tokens", num(800), "maxChars", num(300)), Set.of());
            assertEquals(Source.ADMIN, source(resolver.resolve(ALICE, Kind.PLUGIN, "demo", DECLARED), "maxChars"));

            store.save(Scope.OWN, ALICE, Kind.PLUGIN, "demo", Map.of("maxChars", num(200)), Set.of());
            store.save(Scope.ASSIGNED, ALICE, Kind.PLUGIN, "demo", Map.of("tokens", num(50)), Set.of());
            Params alice = resolver.params(ALICE, Kind.PLUGIN, "demo", DECLARED);
            assertEquals(200, alice.number("maxChars", 0));
            assertEquals(50, alice.number("tokens", 0));
            Params bob = resolver.params(BOB, Kind.PLUGIN, "demo", DECLARED);
            assertEquals(300, bob.number("maxChars", 0));
            assertEquals(800, bob.number("tokens", 0));
            assertEquals(Source.ADMIN, source(resolver.defaults(Kind.PLUGIN, "demo", DECLARED), "tokens"));
        }
    }

    /** 开关锁着：用户自己填的普通参数不算，往下用管理员的默认；管理员按人指定的照样算。 */
    @Test
    void aLockSkipsTheUsersOwnValues() throws Exception {
        try (ParamStore store = ParamStore.inMemory(CLOCK, box(1))) {
            ParamResolver resolver = new ParamResolver(store);
            store.save(Scope.DEFAULT, null, Kind.PLUGIN, "demo", Map.of("maxChars", num(300)), Set.of());
            store.save(Scope.OWN, ALICE, Kind.PLUGIN, "demo", Map.of("maxChars", num(200)), Set.of());
            store.save(Scope.ASSIGNED, ALICE, Kind.PLUGIN, "demo", Map.of("tokens", num(50)), Set.of());
            Params locked = resolver.params(ALICE, Kind.PLUGIN, "demo", DECLARED, true);
            assertEquals(300, locked.number("maxChars", 0));
            assertEquals(50, locked.number("tokens", 0));
            assertEquals(Source.ADMIN, source(resolver.resolve(ALICE, Kind.PLUGIN, "demo", DECLARED, true), "maxChars"));
            assertEquals(200, resolver.params(ALICE, Kind.PLUGIN, "demo", DECLARED, false).number("maxChars", 0));
        }
    }

    /** 用户自己那份里就算有只归管理员的键（旧数据、绕过接口），也不起作用；反过来也一样。 */
    @Test
    void aValueInTheWrongScopeIsIgnored() throws Exception {
        try (ParamStore store = ParamStore.inMemory(CLOCK, box(1))) {
            ParamResolver resolver = new ParamResolver(store);
            store.save(Scope.OWN, ALICE, Kind.PLUGIN, "demo", Map.of("tokens", num(999999)), Set.of());
            store.save(Scope.ASSIGNED, ALICE, Kind.PLUGIN, "demo", Map.of("maxChars", num(9999)), Set.of());
            Params alice = resolver.params(ALICE, Kind.PLUGIN, "demo", DECLARED);
            assertEquals(1000, alice.number("tokens", 0));
            assertEquals(500, alice.number("maxChars", 0));
        }
    }

    /** 插件升级后范围变了：存着的旧值不合格，当没存，往下找。 */
    @Test
    void aStoredValueThatNoLongerFitsFallsThrough() throws Exception {
        try (ParamStore store = ParamStore.inMemory(CLOCK, box(1))) {
            ParamResolver resolver = new ParamResolver(store);
            store.save(Scope.OWN, ALICE, Kind.PLUGIN, "demo", Map.of("maxChars", num(50)), Set.of());
            store.save(Scope.DEFAULT, null, Kind.PLUGIN, "demo", Map.of("maxChars", new Json.Str("big")), Set.of());
            List<Resolved> resolved = resolver.resolve(ALICE, Kind.PLUGIN, "demo", DECLARED);
            assertEquals(Source.BUILTIN, source(resolved, "maxChars"));
            assertEquals(500, resolver.params(ALICE, Kind.PLUGIN, "demo", DECLARED).number("maxChars", 0));
        }
    }

    @Test
    void onlyDeclaredSecretsCanBeRead() throws Exception {
        try (ParamStore store = ParamStore.inMemory(CLOCK, box(1))) {
            ParamResolver resolver = new ParamResolver(store);
            store.putSecret(ALICE, Kind.PLUGIN, "demo", "token", "hunter2");
            store.putSecret(ALICE, Kind.PLUGIN, "demo", "leftover", "old");
            assertEquals(Optional.of("hunter2"), resolver.secrets(ALICE, Kind.PLUGIN, "demo", DECLARED).get("token"));
            assertEquals(Optional.empty(), resolver.secrets(ALICE, Kind.PLUGIN, "demo", DECLARED).get("leftover"));
            assertEquals(Optional.empty(), resolver.secrets(BOB, Kind.PLUGIN, "demo", DECLARED).get("token"));
            assertEquals(Optional.empty(), resolver.secrets(ALICE, Kind.PLUGIN, "demo", List.of(MAX_CHARS)).get("token"));
        }
    }
}
