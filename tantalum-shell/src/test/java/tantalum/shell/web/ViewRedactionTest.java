package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** memory-final-plan 第 13 节：只读查看时遮掉个人插件的五类内容，别的原样。 */
class ViewRedactionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ViewRedaction redaction = new ViewRedaction(Set.of("memory"), Set.of("memory_read"), Set.of("memory-index"));

    @Test
    void personalContentIsHiddenAndTheRestIsKept() {
        JsonNode tree = JSON.readTree("""
                {"items":[
                  {"kind":"user","text":"hi","attached":[{"kind":"attached","plugin":"memory","text":"secret note"},
                                                         {"kind":"attached","plugin":"other","text":"public note"}]},
                  {"kind":"tool","call":{"kind":"toolCall","id":"c1","name":"memory_read","rawArguments":"{\\"name\\":\\"x\\"}","arguments":{"name":"x"}},
                   "result":{"kind":"toolResult","toolCallId":"c1","text":"secret body"}},
                  {"kind":"tool","call":{"kind":"toolCall","id":"c2","name":"read_file","rawArguments":"{}","arguments":{}},
                   "result":{"kind":"toolResult","toolCallId":"c2","text":"file body"}},
                  {"name":"memory-index","body":"- [x](x.md) — secret"},
                  {"system":"Base.\\n\\n<memory-index>\\n- [x](x.md) — secret\\n</memory-index>\\n\\n<local-workspace>\\nkept\\n</local-workspace>"},
                  {"call":"c3","name":"memory_read","rawArguments":"{}","result":"secret result"},
                  {"text":"see <attached plugin=\\"memory\\">\\nsecret\\n</attached> done","plugin":{"nested":"object"}}
                ]}""");
        String out = JSON.writeValueAsString(redaction.redact(tree));
        assertFalse(out.contains("secret"), out);
        assertTrue(out.contains("public note") && out.contains("file body") && out.contains("kept") && out.contains("Base."), out);
        assertEquals(6, out.split(java.util.regex.Pattern.quote(ViewRedaction.HIDDEN), -1).length - 1, out);
    }

    @Test
    void withoutPersonalPluginsNothingChanges() {
        ViewRedaction none = new ViewRedaction(Set.of(), Set.of(), Set.of());
        assertTrue(none.idle());
        JsonNode tree = JSON.readTree("{\"a\":\"<memory-index>\\nx\\n</memory-index>\"}");
        assertEquals(tree.toString(), none.redact(tree).toString());
    }
}
