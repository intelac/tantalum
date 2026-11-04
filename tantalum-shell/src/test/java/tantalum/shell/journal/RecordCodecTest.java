package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.command.CallModel;
import tantalum.value.decision.HookReported;
import tantalum.value.decision.PhaseChanged;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.StepRecord;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.TextBlock;
import tantalum.value.model.Role;

/** record-results 任务 2–4：结果行编码的边角。正常路径由 {@code RecordRoundTripTest} 用真实内核覆盖。 */
class RecordCodecTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final RunRef RUN = new RunRef(USER, SESSION, new RunId("s_0"));
    private static final String LONG = "长".repeat(100);

    private final EventCodec events = EventCodec.withBuiltins();
    private final RecordCodec codec = new RecordCodec(events);

    private static StepRecord appending(long seq, String text) {
        SessionChange change = new SessionChange(SESSION, USER, false, List.of(Message.user(text)),
                null, null, null, null, null, null, null);
        return new StepRecord(seq, change, List.of(), List.of(), null, null, List.of(), List.of(new PhaseChanged(RUN, "Deciding")));
    }

    private static StepRecord calling(long seq, Prompt prompt) {
        return new StepRecord(seq, null, List.of(), List.of(), null, null,
                List.of(new CallModel(new CommandId("s_" + seq + "-0"), RUN, prompt, List.of())), List.of());
    }

    @Test
    void aLongTextIsWrittenOnceAndReferencedAfterwards() {
        RecordMemo writer = RecordMemo.writer();
        String first = codec.encode(appending(1, LONG), writer);
        String second = codec.encode(appending(2, LONG), writer);

        assertTrue(first.contains(LONG));
        assertFalse(second.contains(LONG));
        assertTrue(second.getBytes(StandardCharsets.UTF_8).length < first.getBytes(StandardCharsets.UTF_8).length - 200);

        RecordMemo reader = RecordMemo.reader();
        codec.decode(first, reader);
        assertEquals(appending(2, LONG), codec.decode(second, reader).record());
    }

    @Test
    void aTextFromAnEventLineIsReferencedToo() {
        RecordMemo writer = RecordMemo.writer();
        writer.see(events.mapper().valueToTree(Message.user(LONG)));
        assertFalse(codec.encode(appending(1, LONG), writer).contains(LONG));
    }

    @Test
    void textsThatLookLikeReferencesSurvive() {
        String fake = "\u0000#" + "0".repeat(64);
        for (String text : List.of(fake, "\u0000", "\u0000\u0000x", "\u0000" + LONG)) {
            RecordMemo writer = RecordMemo.writer();
            String line = codec.encode(appending(1, text), writer);
            assertEquals(appending(1, text), codec.decode(line, RecordMemo.reader()).record(), text);
        }
    }

    @Test
    void aReferenceToATextNotEarlierInTheFileIsCorrupt() {
        RecordMemo writer = RecordMemo.writer();
        codec.encode(appending(1, LONG), writer);
        String second = codec.encode(appending(2, LONG), writer);

        assertThrows(IllegalArgumentException.class, () -> codec.decode(second, RecordMemo.reader()));
    }

    @Test
    void aBlindWriterWritesEverythingOut() {
        RecordMemo blind = RecordMemo.blind();
        Prompt prompt = new Prompt("system", List.of(), List.of(Message.user("q")));
        String line = codec.encode(calling(1, prompt), blind);

        RecordMemo reader = RecordMemo.reader();
        assertEquals(calling(1, prompt), codec.decode(line, reader).record());
        assertTrue(line.contains("\"keep\":0"));
    }

    @Test
    void aRequestKeepsThePrefixOfTheLastOne() {
        Message question = Message.user("q");
        Message answer = new Message(Role.ASSISTANT, List.of(new TextBlock("a")), false);
        Prompt first = new Prompt("system", List.of(), List.of(question));
        Prompt second = new Prompt("system", List.of(), List.of(question, answer, Message.user("next")));
        RecordMemo writer = RecordMemo.writer();
        String one = codec.encode(calling(1, first), writer);
        String two = codec.encode(calling(2, second), writer);

        assertTrue(two.contains("\"keep\":1"));
        assertFalse(two.contains("\"system\""));
        RecordMemo reader = RecordMemo.reader();
        codec.decode(one, reader);
        RecordCodec.Decoded decoded = codec.decode(two, reader);
        assertEquals(calling(2, second), decoded.record());
        assertTrue(decoded.fingerprintMismatches().isEmpty());
    }

    @Test
    void theFingerprintIsTheHashOfTheRequestAsTheApiWritesIt() throws Exception {
        Prompt prompt = new Prompt("system", List.of(), List.of(Message.user("q")));
        byte[] json = events.mapper().writeValueAsBytes(prompt);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));

        assertEquals(expected, codec.fingerprint(prompt));
        assertTrue(codec.encode(calling(1, prompt), RecordMemo.writer()).contains(expected));
    }

    @Test
    void aChangedRequestIsReportedAsAMismatch() {
        Prompt prompt = new Prompt("system", List.of(), List.of(Message.user("q")));
        String line = codec.encode(calling(1, prompt), RecordMemo.writer()).replace(codec.fingerprint(prompt), "0".repeat(64));

        assertEquals(java.util.Set.of(new CommandId("s_1-0")), codec.decode(line, RecordMemo.reader()).fingerprintMismatches());
    }

    @Test
    void anUnknownFactIsKeptAsItIs() {
        String line = codec.encode(new StepRecord(1, null, List.of(), List.of(), null, null, List.of(),
                List.of(new HookReported(RUN, "h", "d"))), RecordMemo.writer()).replace("HookReported", "FromTheFuture");

        StepRecord record = codec.decode(line, RecordMemo.reader()).record();

        UnknownFact unknown = assertInstanceOf(UnknownFact.class, record.facts().getFirst());
        assertEquals("FromTheFuture", unknown.type());
        assertEquals(RUN, unknown.run());
        assertEquals(line, codec.encode(record, RecordMemo.writer()), "written back byte for byte");
    }

    @Test
    void anUnknownCommandOrFieldIsCorrupt() {
        String line = codec.encode(new StepRecord(1, null, List.of(), List.of(), null, null,
                List.of(new tantalum.value.command.AssembleContext(new CommandId("c"), RUN)), List.of()), RecordMemo.writer());

        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(line.replace("AssembleContext", "Teleport"), RecordMemo.reader()));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(line.replace("\"run\":{", "\"surprise\":1,\"run\":{"), RecordMemo.reader()));
    }
}
