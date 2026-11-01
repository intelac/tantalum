package tantalum.shell.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class CredentialGuardTest {

    @Test
    void recognisesCommonCredentialShapes() {
        String[] samples = {
            "use sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789 for calls",
            "key is sk-abcdefghijklmnopqrstuvwxyz1234",
            "aws AKIAIOSFODNN7EXAMPLE",
            "gh token ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            "slack xoxb-123456789012-abcdefghij",
            "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
            "password=hunter2secret",
            "api_key: \"abcdef123456\"",
            "PRIVATE_KEY = ZmFrZWtleQ==",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIE...",
        };
        for (String sample : samples) {
            assertTrue(CredentialGuard.looksLikeCredential(sample), sample);
        }
    }

    @Test
    void ordinaryTextIsNotFlagged() {
        String[] samples = {
            "我们讨论了 password 策略：至少 12 位，每 90 天更换。",
            "The token budget per day is 200000.",
            "Use the secret-santa project for the demo.",
            "api key rotation happens on the first Monday",
            "sk-8 is a chess opening, not a key",
            "set the bearer of the message to the user",
            "project uses Java 25 and Spring Boot 4",
        };
        for (String sample : samples) {
            assertFalse(CredentialGuard.looksLikeCredential(sample), sample);
        }
    }

    @Test
    void checksEveryFieldAndNamesTheRuleNotTheContent() {
        Optional<String> hit = CredentialGuard.detect("harmless description", "body with password=hunter2secret");
        assertEquals(Optional.of("secret assignment"), hit);
        assertFalse(hit.orElseThrow().contains("hunter2"));
        assertFalse(CredentialGuard.REASON.contains("hunter2"));
        assertTrue(CredentialGuard.detect((String) null).isEmpty());
        assertTrue(CredentialGuard.detect().isEmpty());
    }
}
