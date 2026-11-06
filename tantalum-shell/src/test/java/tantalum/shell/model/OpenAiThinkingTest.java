package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.json.JsonText;
import tantalum.value.json.Json;
import tools.jackson.databind.json.JsonMapper;

/** model-picker 任务 3（M5）：五种思考写法加进请求体的字段和期望文件一致；不认识的写法和档位直接报错。 */
class OpenAiThinkingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void eachThinkingWritesTheFieldsInTheExpectedFile() throws Exception {
        String expected;
        try (InputStream in = getClass().getResourceAsStream("/model/openai-thinking.txt")) {
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> actual = new ArrayList<>(List.of(expected.lines().findFirst().orElseThrow()));
        String[][] cases = {{"none", "medium"}, {"reasoning-effort", "low"}, {"reasoning-effort", "high"}, {"enable-thinking", "on"},
            {"enable-thinking", "off"}, {"chat-template", "on"}, {"chat-template", "off"}, {"thinking-type", "on"}, {"thinking-type", "off"}};
        for (String[] each : cases) {
            Json fields = new Json.Obj(OpenAiThinking.parse(each[0]).fields(each[1]));
            actual.add(each[0] + " " + each[1] + ": " + JSON.writeValueAsString(JsonText.toPlain(fields)));
        }
        assertEquals(expected, String.join("\n", actual) + "\n");
    }

    @Test
    void unknownThinkingOrLevelsAreRefused() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> OpenAiThinking.parse("foo"));
        assertTrue(unknown.getMessage().contains("chat-template"), unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> OpenAiThinking.CHAT_TEMPLATE.fields("high"));
        assertEquals(java.util.Map.of(), OpenAiThinking.CHAT_TEMPLATE.fields(null), "a model without levels sends nothing");
    }
}
