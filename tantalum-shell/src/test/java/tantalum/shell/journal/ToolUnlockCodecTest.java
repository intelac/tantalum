package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.event.ContextAssembled;
import tantalum.value.id.CommandId;
import tantalum.value.model.ToolUnlock;
import tantalum.value.state.SessionContext;

/**
 * browser-on-demand 需求 2.4、4.2：按需出现的工具的规则写进组装事件和会话上下文，读回来一样；没有规则时不写出这个字段
 * （逐字节和原来一样由 {@link PluginParamsCodecTest} 管）；空表当作没有。
 */
class ToolUnlockCodecTest {

    private static final ToolUnlock BROWSER = new ToolUnlock(List.of("browser_navigate", "browser_tabs"),
            List.of("browser_click", "browser_type"));

    private final EventCodec codec = EventCodec.withBuiltins();

    @Test
    void rulesInAnAssembledContextSurviveARoundTrip() {
        ContextAssembled event = new ContextAssembled(new CommandId("c-1"), "system", List.of(), null, List.of(),
                List.of("browser_navigate", "browser_tabs"), List.of(), Map.of(), null, null, List.of(BROWSER));
        String written = codec.encode(event);
        assertTrue(written.endsWith(",\"unlocks\":[{\"after\":[\"browser_navigate\",\"browser_tabs\"],"
                + "\"add\":[\"browser_click\",\"browser_type\"]}]}"), written);
        ContextAssembled read = (ContextAssembled) codec.decode(ContextAssembled.TYPE, 2, written);
        assertEquals(event, read);
        assertEquals(written, codec.encode(read));
    }

    @Test
    void rulesInTheSessionContextSurviveARoundTrip() {
        SessionContext context = new SessionContext("system", List.of(), null, List.of(), List.of("browser_navigate"),
                List.of("browser_navigate"), List.of()).withUnlocks(List.of(BROWSER));
        String written = codec.mapper().writeValueAsString(context);
        SessionContext read = codec.mapper().readValue(written, SessionContext.class);
        assertEquals(context, read);
        assertEquals(List.of(BROWSER), read.unlockRules());
    }

    @Test
    void anEmptyListIsTheSameAsNone() {
        ContextAssembled event = new ContextAssembled(new CommandId("c-1"), "system", List.of(), null, List.of(),
                null, null, Map.of(), null, null, List.of());
        assertNull(event.unlocks());
        assertFalse(codec.encode(event).contains("unlocks"));
        assertEquals(List.of(), new SessionContext("s", List.of(), null, List.of()).withUnlocks(List.of()).unlockRules());
    }
}
