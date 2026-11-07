package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /** waiting-state 需求 2.1、2.3：等人阶段（问题、位置、期限）和两个事实进结果行，读回相同；两次写出的字节相同。 */
    @Test
    void awaitingAnswerAndQuestionFactsRoundTrip() {
        tantalum.value.question.Question question = new tantalum.value.question.Question("approval-c1",
                tantalum.value.text.LocalText.en("Allow bash?").zh("允许调用 bash 吗？").ja("bash を許可しますか？"),
                tantalum.value.text.LocalText.of("{\"command\":\"ls\"}"),
                List.of(new tantalum.value.question.Question.Choice("once", tantalum.value.text.LocalText.of("Allow once")),
                        new tantalum.value.question.Question.Choice("deny", tantalum.value.text.LocalText.en("Deny").zh("拒绝"))),
                false, java.time.Instant.parse("2026-11-07T09:10:00Z"));
        tantalum.value.state.RunState waiting = tantalum.value.state.RunState.start(RUN, new tantalum.value.state.OwnerRef.OfSession(SESSION))
                .withPhase(new tantalum.value.state.AwaitingAnswer(question, tantalum.value.hook.HookPoint.BEFORE_TOOL_CALL, null,
                        new CommandId("s_5-0")));
        tantalum.value.state.RunState askUser = tantalum.value.state.RunState.start(RUN, new tantalum.value.state.OwnerRef.OfSession(SESSION))
                .withPhase(new tantalum.value.state.AwaitingAnswer(new tantalum.value.question.Question("ask-a1",
                        tantalum.value.text.LocalText.of("Delete?"), null, List.of(), true, null), null, "a1", null));
        List<tantalum.value.decision.Fact> facts = List.of(
                new tantalum.value.decision.QuestionAsked(RUN, "toolApproval", question),
                new tantalum.value.decision.QuestionSettled(RUN, "approval-c1", new tantalum.value.question.Answer("once", null),
                        tantalum.value.decision.QuestionSettled.Reason.ANSWERED),
                new tantalum.value.decision.QuestionSettled(RUN, "ask-a1", null, tantalum.value.decision.QuestionSettled.Reason.EXPIRED));
        StepRecord record = new StepRecord(5, null, List.of(waiting, askUser), List.of(), null, null, List.of(), facts);

        String line = codec.encode(record, RecordMemo.writer());
        assertEquals(line, codec.encode(record, RecordMemo.writer()));
        assertTrue(line.contains("\"kind\":\"AwaitingAnswer\""), line);
        assertTrue(line.contains("\"byLanguage\":{\"EN\":\"Allow bash?\",\"ZH\":\"允许调用 bash 吗？\",\"JA\":\"bash を許可しますか？\"}"), line);
        assertTrue(line.contains("\"deadline\":\"2026-11-07T09:10:00Z\""), line);
        assertEquals(record, codec.decode(line, RecordMemo.reader()).record());
    }

    /** A4：插件工作记在会话里的东西（如“这个会话里一直允许”的工具）写进结果行，恢复时原样读回；没变时不写。 */
    @Test
    void sessionNotesRoundTrip() {
        java.util.Map<String, tantalum.value.json.Json.Obj> notes = java.util.Map.of("approval", new tantalum.value.json.Json.Obj(
                java.util.Map.of("write_file", new tantalum.value.json.Json.Bool(true))));
        SessionChange change = new SessionChange(SESSION, USER, false, List.of(), null, null, null, null, null, null, null, null,
                new SessionChange.Changed<>(notes));
        StepRecord record = new StepRecord(3, change, List.of(), List.of(), null, null, List.of(), List.of());
        String line = codec.encode(record, RecordMemo.writer());
        assertTrue(line.contains("\"notes\":{\"approval\":{\"write_file\":true}}"), line);
        assertEquals(record, codec.decode(line, RecordMemo.reader()).record());
        assertFalse(codec.encode(appending(4, "x"), RecordMemo.writer()).contains("notes"));
    }

    /** hook-patch：钩子补丁的状态随上下文记进 journal；没有钩子追加过东西时不写出，旧行逐字节不变。 */
    @Test
    void hookStateRoundTripsAndIsAbsentWhenNone() {
        tantalum.value.model.ToolSpec cite = new tantalum.value.model.ToolSpec("cite", "cite sources", new tantalum.value.json.Json.Null());
        tantalum.value.state.SessionContext plain = new tantalum.value.state.SessionContext("sys", List.of(), null, List.of());
        tantalum.value.state.SessionContext.HookState hooks = new tantalum.value.state.SessionContext.HookState("be brief",
                List.of(new tantalum.value.state.SessionContext.HookState.HookTool("style", cite)), List.of("cite"));
        SessionChange first = new SessionChange(SESSION, USER, true, List.of(), null, null, null, new SessionChange.Changed<>(plain),
                null, null, null, null, null);
        SessionChange second = new SessionChange(SESSION, USER, false,
                List.of(new Message(Role.SYSTEM, List.of(new tantalum.value.model.HookTextBlock("style", "be brief")), false)),
                null, null, null, new SessionChange.Changed<>(plain.withHooks(hooks)), null, null, null, null, null);
        RecordMemo writer = RecordMemo.writer();
        String one = codec.encode(new StepRecord(1, first, List.of(), List.of(), null, null, List.of(), List.of()), writer);
        assertFalse(one.contains("hooks"), one);
        StepRecord record = new StepRecord(2, second, List.of(), List.of(), null, null, List.of(), List.of());
        String two = codec.encode(record, writer);
        assertTrue(two.contains("\"hooks\":{\"text\":\"be brief\",\"tools\":[{\"hook\":\"style\",\"tool\":"), two);
        assertTrue(two.contains("\"kind\":\"hookText\",\"hook\":\"style\",\"text\":\"be brief\""), two);
        RecordMemo reader = RecordMemo.reader();
        codec.decode(one, reader);
        assertEquals(record, codec.decode(two, reader).record());
    }

    @Test
    void aTextFromAnEventLineIsReferencedToo() {
        RecordMemo writer = RecordMemo.writer();
        writer.see(events.mapper().valueToTree(Message.user(LONG)));
        assertFalse(codec.encode(appending(1, LONG), writer).contains(LONG));
    }

    @Test
    void textsThatLookLikeReferencesSurvive() {
        String fake = "\u0000#" + "0".repeat(32);
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
        assertEquals(codec.fingerprint(second), decoded.fingerprints().get(new CommandId("s_2-0")), "the rebuilt request matches what was sent");
    }

    @Test
    void theFingerprintIsTheHashOfTheRequestAsTheApiWritesIt() throws Exception {
        Prompt prompt = new Prompt("system", List.of(), List.of(Message.user("q")));
        byte[] json = events.mapper().writeValueAsBytes(prompt);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json)).substring(0, 32);

        assertEquals(expected, codec.fingerprint(prompt));
        assertTrue(codec.encode(calling(1, prompt), RecordMemo.writer()).contains(expected));
    }

    @Test
    void theRecordedFingerprintIsKeptForTheRequestDetail() {
        Prompt prompt = new Prompt("system", List.of(), List.of(Message.user("q")));
        String line = codec.encode(calling(1, prompt), RecordMemo.writer()).replace(codec.fingerprint(prompt), "0".repeat(32));

        RecordCodec.Decoded decoded = codec.decode(line, RecordMemo.reader());
        assertEquals("0".repeat(32), decoded.fingerprints().get(new CommandId("s_1-0")));
        assertNotEquals(codec.fingerprint(((CallModel) decoded.record().commands().getFirst()).prompt()),
                decoded.fingerprints().get(new CommandId("s_1-0")));
    }

    /** 这一步追加的助手消息就是事件里的模型回复：结果行只写“来自事件”，不再写内容。 */
    @Test
    void theReplyOfThisStepIsTakenFromItsEvent() {
        List<tantalum.value.model.ContentBlock> content = List.of(new TextBlock("x".repeat(300)), new TextBlock("short"));
        tantalum.value.event.EventEnvelope event = new tantalum.value.event.EventEnvelope(4, java.time.Instant.EPOCH, USER, SESSION,
                RUN.run(), new tantalum.value.event.ModelReplied(new CommandId("s_3-0"), content,
                tantalum.value.model.StopReason.END_TURN, "m", null, null, null, null));
        Message reply = new Message(Role.ASSISTANT, content, false);
        StepRecord record = new StepRecord(4, new SessionChange(SESSION, USER, false, List.of(reply), null, null, null, null, null, null, null),
                List.of(), List.of(), null, null, List.of(), List.of());
        RecordMemo writer = RecordMemo.writer();
        writer.seeEvent(event);
        String line = codec.encode(record, writer);

        assertTrue(line.contains("\"fromEvent\":true") && !line.contains("short"), line);
        RecordMemo reader = RecordMemo.reader();
        reader.seeEvent(event);
        assertEquals(record, codec.decode(line, reader).record());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(line, RecordMemo.reader()), "the event is needed");
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

    /** 工具参数里的数字原样保留（块的哈希要求读出来再写一遍逐字节相同）。 */
    @Test
    void numbersInToolArgumentsSurviveExactly() {
        tantalum.value.json.Json arguments = new tantalum.value.json.Json.Obj(java.util.Map.of(
                "price", new tantalum.value.json.Json.Num(new java.math.BigDecimal("1.50")),
                "big", new tantalum.value.json.Json.Num(new java.math.BigDecimal("12345678901234567890")),
                "small", new tantalum.value.json.Json.Num(new java.math.BigDecimal("1E-7"))));
        Message call = new Message(Role.ASSISTANT, List.of(new tantalum.value.model.ToolCallBlock("c1", "tool", "{}", arguments),
                new TextBlock("x".repeat(200))), false);
        SessionChange change = new SessionChange(SESSION, USER, false, List.of(call, call), null, null, null, null, null, null, null);
        StepRecord record = new StepRecord(1, change, List.of(), List.of(), null, null, List.of(), List.of());
        RecordMemo writer = RecordMemo.writer();
        String line = codec.encode(record, writer);

        assertTrue(line.contains("\u0000@") || line.contains("\\u0000@"), "the second copy of the message's blocks is a reference: " + line);
        assertEquals(record, codec.decode(line, RecordMemo.reader()).record());
    }

    /** 压缩时裁剪过的工具结果是原文的开头加结尾：写成原文的切片，读出来逐字相同。 */
    @Test
    void aTrimmedTextIsWrittenAsASliceOfItsOriginal() {
        String original = "line of output 🙂 ".repeat(200);
        String trimmed = original.substring(0, 500) + "\n… [2000 bytes omitted] …\n" + original.substring(original.length() - 300);
        RecordMemo writer = RecordMemo.writer();
        String first = codec.encode(appending(1, original), writer);
        String second = codec.encode(appending(2, trimmed), writer);

        assertTrue(second.length() < 500, second);
        RecordMemo reader = RecordMemo.reader();
        codec.decode(first, reader);
        assertEquals(appending(2, trimmed), codec.decode(second, reader).record());
    }

    /** 同一步的运行、命令和判断指向同一个运行：只写一次。 */
    @Test
    void theRunOfAStepIsWrittenOnce() {
        StepRecord record = new StepRecord(1, null, List.of(), List.of(), null, null,
                List.of(new tantalum.value.command.AssembleContext(new CommandId("c"), RUN)),
                List.of(new PhaseChanged(RUN, "AwaitingContext"), new HookReported(RUN, "h", "d")));
        String line = codec.encode(record, RecordMemo.writer());

        assertEquals(1, line.split("\"principal\"", -1).length - 1, line);
        assertEquals(record, codec.decode(line, RecordMemo.reader()).record());
    }
}
