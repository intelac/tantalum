package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.table.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.store.SessionCorruptException;
import tantalum.shell.store.SessionFile;
import tantalum.shell.store.SessionPaths;
import tantalum.shell.store.SessionStore;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.Awaiting;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;

class StartupTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final RunId RUN = RunId.of(SESSION, 0);

    @TempDir
    Path dir;

    private static Kernel kernel() {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        return new Kernel(table, new HookChain(List.of()), (tool, args) -> false, 10);
    }

    private void write(EventEnvelope envelope) throws Exception {
        try (SessionFile file = SessionFile.open(new SessionPaths(dir), envelope.principal(), envelope.session(), EventCodec.withBuiltins())) {
            file.append(List.of(envelope));
        }
    }

    private SessionStore store() {
        return new SessionStore(dir, EventCodec.withBuiltins());
    }

    private static EventEnvelope prompt() {
        return new EventEnvelope(0, Instant.EPOCH, USER, SESSION, null,
                new UserPrompt(new SubmissionId("sub"), new RequestId("req"), new OwnerRef.OfSession(SESSION), "hi"));
    }

    @Test
    void emptyJournalStartsEmpty() throws Exception {
        {
            Startup.Recovered recovered = Startup.recover(store(), kernel());
            assertTrue(recovered.state().runs().isEmpty());
        }
    }

    @Test
    void unfinishedRunStaysWaitingMarkedForRecoveryWithoutNewCommands() throws Exception {
        {
            write(prompt());
            write(new EventEnvelope(1, Instant.EPOCH, USER, SESSION, RUN,
                    new ContextAssembled(new CommandId("s_0-0"), "s", List.of())));
            Startup.Recovered recovered = Startup.recover(store(), kernel());

            RunState run = recovered.state().runs().get(RUN);
            assertTrue(run.pendingRecovery());
            assertEquals("AwaitingModel", ((Awaiting) run.phase()).resumeAt());
        }
    }

    @Test
    void finishedRunIsNotMarked() throws Exception {
        {
            write(prompt());
            write(new EventEnvelope(1, Instant.EPOCH, USER, SESSION, RUN, new Cancel(new RequestId("c"))));
            assertFalse(Startup.recover(store(), kernel()).state().runs().get(RUN).pendingRecovery());
        }
    }

    @Test
    void corruptJournalRefusesToStart() throws Exception {
        {
            write(prompt());
            write(new EventEnvelope(1, Instant.EPOCH, USER, SESSION, RUN, new Cancel(new RequestId("c"))));
            Path file = new SessionPaths(dir).sessionFile(USER, SESSION);
            List<String> lines = new java.util.ArrayList<>(Files.readAllLines(file));
            lines.set(1, "{\"seq\":0,\"at\":\"1970-01-01T00:00:00Z\",\"type\":\"Mystery\",\"version\":1,\"run\":null,\"payload\":{}}");
            Files.write(file, lines);
            assertThrows(SessionCorruptException.class, () -> Startup.recover(store(), kernel()));
        }
    }

    @Test
    void transcriptIsRebuiltWithTheToolChain() throws Exception {
        {
            ToolCallBlock call = new ToolCallBlock("c1", "read_file", "{}", new Json.Obj(Map.of()));
            write(prompt());
            write(new EventEnvelope(1, Instant.EPOCH, USER, SESSION, RUN,
                    new ContextAssembled(new CommandId("s_0-0"), "s", List.of())));
            write(new EventEnvelope(2, Instant.EPOCH.plusSeconds(5), USER, SESSION, RUN,
                    new ModelReplied(new CommandId("s_1-0"), List.of(call), StopReason.TOOL_USE, "m", null)));
            write(new EventEnvelope(3, Instant.EPOCH.plusSeconds(9), USER, SESSION, RUN,
                    new ToolFinished(new CommandId("s_2-0"), "c1", "file body", false)));

            Startup.Recovered recovered = Startup.recover(store(), kernel());

            List<TranscriptItem> items = recovered.transcript().items(SESSION);
            assertEquals(3, items.size());
            TranscriptItem.ToolStep step = (TranscriptItem.ToolStep) items.get(2);
            assertEquals(TranscriptItem.StepStatus.DONE, step.status());
            assertEquals("file body", step.result().text());
            assertEquals(Duration.ofSeconds(4), step.elapsed());
            assertEquals("AwaitingModel", ((TranscriptItem.RunStatus) items.get(1)).phase());
        }
    }
}
