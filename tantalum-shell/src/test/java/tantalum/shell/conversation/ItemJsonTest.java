package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.shell.conversation.TranscriptItem.RunStatus;
import tantalum.shell.conversation.TranscriptItem.ToolStep;
import tantalum.shell.conversation.TranscriptItem.UserMessage;
import tantalum.shell.journal.EventCodec;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.EndReason;
import tools.jackson.databind.json.JsonMapper;

class ItemJsonTest {

    private static final JsonMapper MAPPER = EventCodec.withBuiltins().mapper();
    private static final SessionId SESSION = new SessionId("s");
    private static final RunId RUN = new RunId("run-0");
    private static final Instant T0 = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    void toolStepHasKindFlatIdsAndJournalShapedBlocks() {
        ToolCallBlock call = new ToolCallBlock("c1", "read_file", "{\"path\":\"a\"}",
                new Json.Obj(Map.of("path", new Json.Str("a"))));
        ToolStep step = ToolStep.waiting("2.0", SESSION, RUN, T0, call).running("g2", T0)
                .finished(new ToolResultBlock("c1", "body", false, null), T0.plusSeconds(3));

        Map<String, Object> data = ItemJson.toMap(MAPPER, step);

        assertEquals(List.of("kind", "id", "session", "run"), List.copyOf(data.keySet()).subList(0, 4));
        assertEquals("tool", data.get("kind"));
        assertEquals("2.0", data.get("id"));
        assertEquals("s", data.get("session"));
        assertEquals("run-0", data.get("run"));
        assertEquals("DONE", data.get("status"));
        assertEquals("g2", data.get("group"));
        assertEquals("2026-10-31T00:00:00Z", data.get("startedAt"));
        assertEquals("2026-10-31T00:00:03Z", data.get("endedAt"));
        Map<?, ?> callData = (Map<?, ?>) data.get("call");
        assertEquals("toolCall", callData.get("kind"));
        assertEquals(Map.of("path", "a"), callData.get("arguments"));
        Map<?, ?> result = (Map<?, ?>) data.get("result");
        assertEquals("toolResult", result.get("kind"));
        assertEquals("body", result.get("text"));
        assertFalse(data.containsKey("elapsed"));
    }

    @Test
    void queuedUserMessageHasNoRun() {
        Map<String, Object> data = ItemJson.toMap(MAPPER, new UserMessage("5.0", SESSION, null, T0, "later", true));
        assertEquals("user", data.get("kind"));
        assertFalse(data.containsKey("run"));
        assertEquals(true, data.get("queued"));
        assertEquals("later", data.get("text"));
    }

    @Test
    void runStatusSerializesEnumsAsNames() {
        RunStatus status = RunStatus.started(SESSION, RUN, T0, "AwaitingContext").ended(EndReason.FAILED, "boom", T0.plusSeconds(1));
        String json = MAPPER.writeValueAsString(ItemJson.toMap(MAPPER, status));
        assertTrue(json.contains("\"kind\":\"run\""), json);
        assertTrue(json.contains("\"endReason\":\"FAILED\""), json);
        assertTrue(json.contains("\"detail\":\"boom\""), json);
        assertFalse(json.contains("failure"), json);
    }
}
