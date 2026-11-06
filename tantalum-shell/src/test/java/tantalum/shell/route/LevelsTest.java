package tantalum.shell.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.model.OpenAiThinking;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** model-picker 任务 3（需求 2.6、2.7）：思考档位按模型给，默认档位的取法；不就近换，写错直接报错。 */
class LevelsTest {

    /** 实测的 Models 接口（2026-11-06）里这个模型支持的 effort 档位。 */
    private static List<String> supported(String model) throws Exception {
        try (InputStream in = LevelsTest.class.getResourceAsStream("/route/claude-models.json")) {
            for (JsonNode entry : JsonMapper.builder().build().readTree(in).path("data")) {
                if (entry.path("id").asString().equals(model)) {
                    List<String> levels = new ArrayList<>();
                    entry.path("capabilities").path("effort").properties().forEach(field -> {
                        if (field.getValue().path("supported").asBoolean(false)) {
                            levels.add(field.getKey());
                        }
                    });
                    return levels;
                }
            }
        }
        throw new AssertionError(model + " is not in the recorded list");
    }

    @Test
    void claudeLevelsAreTheSupportedEffortsInOrderAndNeverOff() throws Exception {
        Levels sonnet55 = Levels.claude(supported("claude-sonnet-5-5"), "medium");
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), sonnet55.names());
        assertEquals("medium", sonnet55.defaultLevel());

        Levels sonnet46 = Levels.claude(supported("claude-sonnet-4-6"), "medium");
        assertEquals(List.of("low", "medium", "high", "max"), sonnet46.names(), "Sonnet 4.6 has no xhigh");
        assertTrue(!sonnet46.names().contains("off"));
    }

    @Test
    void aRouteDefaultTheModelLacksFallsBackToTheLevelNearestMedium() throws Exception {
        assertEquals("medium", Levels.claude(supported("claude-sonnet-4-6"), "xhigh").defaultLevel());
        assertEquals("xhigh", Levels.claude(supported("claude-opus-5-5"), "xhigh").defaultLevel());
        // 一样近取低的：只有 low 和 high
        assertEquals("low", Levels.claude(List.of("low", "high"), "max").defaultLevel());
        assertEquals("high", Levels.claude(List.of("high", "max"), "low").defaultLevel());
        assertEquals(Levels.NONE, Levels.claude(supported("claude-haiku-4-5-20251001"), "medium"), "no effort: no levels");
    }

    @Test
    void openAiLevelsFollowTheConfiguredThinking() {
        assertEquals(Levels.NONE, Levels.openAi(OpenAiThinking.NONE, null, null));
        assertNull(Levels.openAi(OpenAiThinking.NONE, List.of("low"), "low").defaultLevel(), "none has no levels whatever is written");
        assertEquals(new Levels(List.of("low", "medium", "high"), "medium"), Levels.openAi(OpenAiThinking.REASONING_EFFORT, null, null));
        assertEquals(new Levels(List.of("minimal", "low"), "minimal"),
                Levels.openAi(OpenAiThinking.REASONING_EFFORT, List.of("minimal", " low", ""), null));
        for (OpenAiThinking toggle : List.of(OpenAiThinking.ENABLE_THINKING, OpenAiThinking.CHAT_TEMPLATE, OpenAiThinking.THINKING_TYPE)) {
            assertEquals(new Levels(List.of("off", "on"), "on"), Levels.openAi(toggle, null, null), toggle.configName());
        }
        assertEquals("off", Levels.openAi(OpenAiThinking.CHAT_TEMPLATE, null, "off").defaultLevel());
        assertThrows(IllegalArgumentException.class, () -> Levels.openAi(OpenAiThinking.REASONING_EFFORT, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> Levels.openAi(OpenAiThinking.CHAT_TEMPLATE, null, "high"));
    }
}
