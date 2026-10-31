package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OverflowRulesTest {

    private final OverflowRules rules = OverflowRules.defaults();

    @Test
    void recognisesStrataAsMeasured() {
        assertTrue(rules.matches(400, "request exceeds the context window: prompt (560052 tokens) leaves no room"));
    }

    @Test
    void recognisesClaude() {
        assertTrue(rules.matches(400, "prompt is too long: 213462 tokens > 200000 maximum"));
        assertTrue(rules.matches(413, "Request exceeds the maximum size"));
    }

    @Test
    void recognisesOtherOpenAiCompatibleServers() {
        assertTrue(rules.matches(400, "This model's maximum context length is 8192 tokens"));
        assertTrue(rules.matches(400, "context_length_exceeded"));
    }

    @Test
    void doesNotMisreadOtherErrors() {
        assertFalse(rules.matches(429, "Too many tokens, please wait before trying again."));
        assertFalse(rules.matches(500, "prompt is too long"));
        assertFalse(rules.matches(400, "invalid tool schema"));
        assertFalse(rules.matches(400, null));
    }

    @Test
    void rulesAreConfigurable() {
        OverflowRules custom = OverflowRules.of(List.of("custom overflow"));
        assertTrue(custom.matches(400, "Custom Overflow happened"));
        assertFalse(custom.matches(400, "prompt is too long"));
    }
}
