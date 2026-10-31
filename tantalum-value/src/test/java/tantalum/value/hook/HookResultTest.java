package tantalum.value.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;
import tantalum.value.model.ToolSpec;

class HookResultTest {

    @Test
    void plainAllowCarriesNoChange() {
        assertEquals(PromptPatch.NONE, Allow.PLAIN.patch());
    }

    @Test
    void allowCanCarryData() {
        ToolSpec tool = new ToolSpec("t", "d", new Json.Null());
        Allow allow = new Allow(new PromptPatch("extra", List.of(tool)));
        assertEquals("extra", allow.patch().systemAppend());
        assertEquals(List.of(tool), allow.patch().addTools());
    }

    @Test
    void requiredFieldsAreChecked() {
        assertThrows(NullPointerException.class, () -> new Allow(null));
        assertThrows(NullPointerException.class, () -> new Stop(null));
        assertThrows(NullPointerException.class, () -> new NeedWork(null, new Json.Null()));
        assertThrows(NullPointerException.class, () -> new NeedWork("w", null));
        assertThrows(NullPointerException.class, () -> new PromptPatch(null, List.of()));
    }
}
