package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static tantalum.shell.conversation.TranscriptFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.trace.Trace;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.event.Cancel;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.UserPromptFixture;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SubmissionId;

/**
 * 手动压缩那一轮里选了并入的话（审查）：压缩照常结束时它开了新的一轮，对话页和轨迹都挂在新的一轮上；你停了压缩时它只进历史，
 * 照旧挂在压缩那一轮上。
 */
class CompactionJoinTest {

    private static RunId compactWithAJoin(TranscriptFixture fx) {
        for (int i = 1; i <= 3; i++) {
            RunId run = WireFormatTest.prompt(fx, WireFormatTest.block("q" + i), "sys", List.of());
            fx.feed(run, fx.reply(text(WireFormatTest.block("a" + i))));
        }
        fx.feed(null, new CompactRequested(new SubmissionId("c"), new RequestId("c"), null));
        RunId compaction = RunId.of(TranscriptFixture.SESSION, fx.lastSeq());
        assertEquals(compaction, fx.state().sessions().get(TranscriptFixture.SESSION).activeRun(), "the compaction is running");
        fx.feed(compaction, UserPromptFixture.join(new SubmissionId("j"), new RequestId("j"), "and one more thing"));
        return compaction;
    }

    private static TranscriptItem.UserMessage joined(TranscriptFixture fx) {
        return fx.items(TranscriptItem.UserMessage.class).stream().filter(message -> message.text().equals("and one more thing")).findFirst()
                .orElseThrow();
    }

    private static TraceRecord.UserInput traced(TranscriptFixture fx) {
        Trace trace = new Trace();
        java.util.Map<String, TraceRecord> records = new java.util.LinkedHashMap<>();
        for (int i = 0; i < fx.envelopes.size(); i++) {
            trace.apply(fx.envelopes.get(i), fx.befores.get(i), fx.decisions.get(i)).forEach(record -> records.put(record.id(), record));
        }
        return records.values().stream().filter(TraceRecord.UserInput.class::isInstance).map(TraceRecord.UserInput.class::cast)
                .filter(input -> "and one more thing".equals(input.text())).findFirst().orElseThrow();
    }

    @Test
    void whenTheCompactionEndsTheJoinedPromptBelongsToTheRunItStarted() {
        TranscriptFixture fx = new TranscriptFixture();
        RunId compaction = compactWithAJoin(fx);
        fx.feed(compaction, fx.reply(text("Goal: q1")));
        RunId next = fx.state().sessions().get(TranscriptFixture.SESSION).activeRun();
        assertNotEquals(compaction, next, "the joined prompt started a run");

        assertEquals(next, joined(fx).run());
        assertEquals(next, traced(fx).head().run());
        assertEquals(TraceRecord.UserInput.Mode.FOLLOW_UP, traced(fx).mode());
    }

    @Test
    void whenYouStopTheCompactionTheJoinedPromptStaysWithIt() {
        TranscriptFixture fx = new TranscriptFixture();
        RunId compaction = compactWithAJoin(fx);
        fx.feed(compaction, new Cancel(new RequestId("x")));

        assertEquals(null, fx.state().sessions().get(TranscriptFixture.SESSION).activeRun(), "nothing started");
        assertEquals(compaction, joined(fx).run());
        assertEquals(compaction, traced(fx).head().run());
    }
}
