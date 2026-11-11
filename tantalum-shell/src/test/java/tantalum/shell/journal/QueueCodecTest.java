package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tantalum.value.decision.Fact;
import tantalum.value.decision.QueueEdited;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.QueueChanged;
import tantalum.value.event.QueueChanged.Action;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;

/**
 * input-area 需求 1.11：改排队提问的事件和它的事实写了能读回，两次写出的字节相同。
 * 旧会话逐字节重写由 {@link ModelChoiceCodecTest#withoutAChoiceTheOldSessionIsWrittenByteForByte} 守着。
 */
class QueueCodecTest {

    private static final Principal USER = new Principal("alice");
    private static final SessionId SESSION = new SessionId("s-1");
    private static final RunRef RUN = new RunRef(USER, SESSION, RunId.of(SESSION, 0));

    private final EventCodec codec = EventCodec.withBuiltins();

    @ParameterizedTest
    @EnumSource(Action.class)
    void theEventReadsBack(Action action) {
        QueueChanged changed = new QueueChanged(new SubmissionId("s"), new RequestId("r"), action, 2, "原来的文字", List.of(),
                action == Action.EDIT ? "新的文字" : null);
        assertEquals(changed, codec.decode(QueueChanged.TYPE, 1, codec.encode(changed)));
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void theFactReadsBackAndIsWrittenTheSameTwice(Action action) {
        RecordCodec records = new RecordCodec(codec);
        List<Fact> facts = List.of(new QueueEdited(RUN, action, 1), new QueueEdited(RUN, action, -1));
        StepRecord record = new StepRecord(7, null, List.of(), List.of(), null, null, List.of(), facts);
        String line = records.encode(record, RecordMemo.writer(USER, SESSION));
        assertEquals(line, records.encode(record, RecordMemo.writer(USER, SESSION)));
        assertTrue(line.contains("\"type\":\"QueueEdited\""), line);
        assertEquals(record, records.decode(line, RecordMemo.reader(USER, SESSION)).record());
    }
}
