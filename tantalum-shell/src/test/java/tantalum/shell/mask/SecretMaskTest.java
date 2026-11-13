package tantalum.shell.mask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** QAgent 问题 67（2026-11-14 Yi 发来）：工具读到的裸密钥原样给了模型。原来根本没有遮盖。 */
class SecretMaskTest {

    @Test
    void issue67_bareKeysWithKnownPrefixesAreMasked() {
        // 假的密钥，拼起来写，仓库里不出现完整的密钥样子
        String text = "anthropic " + "sk-" + "ant-api03-abcdefghijklmnopqrstuvwxyz0123 aws " + "AKIA" + "IOSFODNN7EXAMPLE gh " + "ghp" + "_abcdefghijklmnopqrstuvwxyz0123456789 "
                + "slack xoxb-1234567890-abcdefghij jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        String masked = SecretMask.mask(text);
        for (String secret : new String[] {"ant-api03", "IOSFODNN7EXAMPLE", "_abcdefghij", "xoxb-", "eyJhbGci"}) {
            assertFalse(masked.contains(secret), masked);
        }
        assertTrue(masked.startsWith("anthropic [REDACTED] aws [REDACTED]"), masked);
    }

    /** QAgent 的原例：relay-key 读出来原样可见。键名留着，值换掉。 */
    @Test
    void issue67_assignedSecretsKeepTheirKeys() {
        assertEquals("relay-key: [REDACTED]", SecretMask.mask("relay-key: 9f8e7d6c5b4a3210"));
        assertEquals("DB_PASSWORD=\"[REDACTED]\"", SecretMask.mask("DB_PASSWORD=\"hunter2!x\""));
        assertEquals("{\"api_key\": \"[REDACTED]\", \"name\": \"shop\"}", SecretMask.mask("{\"api_key\": \"A1b2C3d4E5\", \"name\": \"shop\"}"));
        assertEquals("TANTALUM_TOKEN=[REDACTED] java -jar x.jar", SecretMask.mask("TANTALUM_TOKEN=tc_SXb6rLnWVzxgG7Eags java -jar x.jar"));
    }

    /** 回放 secret-env 场景时看到的：连接串里的口令。 */
    @Test
    void passwordsInsideUrlsAreMasked() {
        assertEquals("DATABASE_URL=postgres://app:[REDACTED]@db.internal/inventory",
                SecretMask.mask("DATABASE_URL=postgres://app:pa55-Secret-77@db.internal/inventory"));
        assertEquals("see https://example.com:8080/path and git@github.com:org/repo.git",
                SecretMask.mask("see https://example.com:8080/path and git@github.com:org/repo.git"));
    }

    @Test
    void privateKeyBlocksAreMasked() {
        String pem = "key:\n-----BEGIN RSA PRIVATE KEY-----\nMIIEow\nIBAAK\n-----END RSA PRIVATE KEY-----\nend";
        assertEquals("key:\n[REDACTED]\nend", SecretMask.mask(pem));
    }

    /** 读代码时不误伤：变量引用、函数调用、纯小写单词、字段访问、已经遮掉的、普通文字。 */
    @Test
    void ordinaryCodeAndTextStayAsTheyAre() {
        for (String text : new String[] {
                "String token = request.header(\"x\");",
                "password: required",
                "token=${TOKEN}",
                "api_key = $API_KEY",
                "secret = config.secret",
                "password: ********",
                "The tokenizer splits text; see Order.java:42.",
                "skip-this sk-short",
                "relay-key: [REDACTED]"}) {
            assertEquals(text, SecretMask.mask(text), text);
        }
    }
}
