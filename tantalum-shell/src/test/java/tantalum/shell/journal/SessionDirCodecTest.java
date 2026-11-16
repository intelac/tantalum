package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.SessionRenamed;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.OwnerRef;

/**
 * workspaces 任务 1、2（W3、需求 2.1、2.7、2.8）：提问带的目录和会话变化里的目录写了能读回；没有目录时一个字也不多写。
 * 旧会话逐字节重写由 {@link ModelChoiceCodecTest#withoutAChoiceTheOldSessionIsWrittenByteForByte} 守着。
 */
class SessionDirCodecTest {

    private static final Principal USER = new Principal("alice");
    private static final SessionId SESSION = new SessionId("s-1");

    private final EventCodec codec = EventCodec.withBuiltins();

    private static UserPrompt prompt(String dir) {
        return new UserPrompt(new SubmissionId("s"), new RequestId("r"), new OwnerRef.OfSession(SESSION), "hi", List.of(), null, dir);
    }

    @Test
    void aPromptWithADirectoryReadsBack() {
        UserPrompt prompt = prompt("C:\\APPL\\Quattro");
        assertEquals(prompt, codec.decode(UserPrompt.TYPE, 1, codec.encode(prompt)));
    }

    @Test
    void aPromptWithoutADirectoryWritesNoField() {
        String written = codec.encode(prompt(null));
        assertFalse(written.contains("dir"), written);
        assertNull(((UserPrompt) codec.decode(UserPrompt.TYPE, 1, codec.encode(prompt(null)))).dir());
    }

    @Test
    void theSessionChangeCarriesTheDirectory() {
        RecordCodec records = new RecordCodec(codec);
        SessionChange change = new SessionChange(SESSION, USER, true, List.of(), null, null, null, null, null, null, null, null, null,
                null, null, new SessionChange.Changed<>("/home/alice/app"));
        StepRecord record = new StepRecord(0, change, List.of(), List.of(), null, null, List.of(), List.of());
        String line = records.encode(record, RecordMemo.writer(USER, SESSION));
        assertTrue(line.contains("\"dir\":\"/home/alice/app\""), line);
        assertEquals(record, records.decode(line, RecordMemo.reader(USER, SESSION)).record());
    }

    /** “不进记忆”是会话状态的一部分（memory-final-plan 第 11 节）：写进账，重启、回收再载入后还在。 */
    @Test
    void theSessionChangeCarriesOffTheRecord() {
        RecordCodec records = new RecordCodec(codec);
        SessionChange change = new SessionChange(SESSION, USER, true, List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null, new SessionChange.Changed<>(true));
        StepRecord record = new StepRecord(0, change, List.of(), List.of(), null, null, List.of(), List.of());
        String line = records.encode(record, RecordMemo.writer(USER, SESSION));
        assertTrue(line.contains("\"offRecord\":true"), line);
        assertEquals(record, records.decode(line, RecordMemo.reader(USER, SESSION)).record());
    }

    @Test
    void anUnchangedDirectoryIsNotWritten() {
        RecordCodec records = new RecordCodec(codec);
        SessionChange change = new SessionChange(SESSION, USER, true, List.of(), null, null, null, null, null, null, null);
        String line = records.encode(new StepRecord(0, change, List.of(), List.of(), null, null, List.of(), List.of()),
                RecordMemo.writer(USER, SESSION));
        assertFalse(line.contains("\"dir\""), line);
    }

    @Test
    void aRenameReadsBack() {
        SessionRenamed renamed = new SessionRenamed(new SubmissionId("s"), new RequestId("r"), "Quattro review");
        assertEquals(renamed, codec.decode(SessionRenamed.TYPE, 1, codec.encode(renamed)));
    }
}
