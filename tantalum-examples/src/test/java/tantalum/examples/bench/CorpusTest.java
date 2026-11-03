package tantalum.examples.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.conversation.TranscriptReplay;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.store.SessionStore;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;

class CorpusTest {

    private static final int EVENTS = 5_000;

    @TempDir
    Path temp;

    private static Kernel kernel() {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        return new Kernel(table, new HookChain(List.of()), (tool, args) -> true, Limits.DEFAULT);
    }

    @Test
    void generatedJournalReplaysWithEveryRunEndedAndTheLongSessionComplete() throws Exception {
        Corpus.Manifest manifest = Corpus.generate(temp, EVENTS, 1);
        assertEquals(manifest, Corpus.readManifest(temp));
        assertTrue(manifest.events() >= EVENTS, "only " + manifest.events());
        assertNotNull(manifest.longRun());
        assertNotNull(manifest.quietRun());

        {
            SessionStore store = new SessionStore(temp, EventCodec.withBuiltins());
            List<EventEnvelope> events = store.readAll();
            assertEquals(manifest.events(), events.size());
            Transcript transcript = new Transcript();
            KernelState state = TranscriptReplay.run(transcript, kernel(), events);
            assertTrue(state.runs().values().stream().allMatch(run -> run.isEnded()));
            assertTrue(state.runs().values().stream()
                    .noneMatch(run -> ((tantalum.value.state.Ended) run.phase()).reason().name().equals("FAILED")));
            assertTrue(events.stream().map(envelope -> envelope.principal().value()).distinct().count() >= 3);
            assertTrue(state.sessions().get(new SessionId(manifest.longSession())).compaction() != null,
                    "the long session should have been compacted");
            List<TranscriptItem> items = transcript.items(new SessionId(manifest.longSession()));
            assertTrue(items.size() >= 500, "long session has only " + items.size() + " items");
        }
    }

    @Test
    void sameSeedGivesTheSameEvents() throws Exception {
        Corpus.generate(temp.resolve("a"), 1_000, 7);
        Corpus.generate(temp.resolve("b"), 1_000, 7);
        assertEquals(new SessionStore(temp.resolve("a"), EventCodec.withBuiltins()).readAll(),
                new SessionStore(temp.resolve("b"), EventCodec.withBuiltins()).readAll());
    }
}
