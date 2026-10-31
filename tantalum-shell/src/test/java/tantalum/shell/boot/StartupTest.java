package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import tantalum.shell.journal.Journal;
import tantalum.shell.journal.JournalCorruptException;
import tantalum.shell.loop.SubmissionLedger;
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
    private static final RunId RUN = new RunId("run-0");

    @TempDir
    Path dir;

    private static Kernel kernel() {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        return new Kernel(table, new HookChain(List.of()), (tool, args) -> false, 10);
    }

    private static EventEnvelope prompt() {
        return new EventEnvelope(0, Instant.EPOCH, USER, SESSION, null,
                new UserPrompt(new SubmissionId("sub"), new RequestId("req"), new OwnerRef.OfSession(SESSION), "hi"));
    }

    @Test
    void emptyJournalStartsEmpty() throws Exception {
        try (Journal journal = Journal.open(dir, EventCodec.withBuiltins())) {
            Startup.Recovered recovered = Startup.recover(journal, kernel(), new SubmissionLedger());
            assertEquals(0, recovered.nextSeq());
            assertTrue(recovered.state().runs().isEmpty());
        }
    }

    @Test
    void unfinishedRunStaysWaitingMarkedForRecoveryWithoutNewCommands() throws Exception {
        try (Journal journal = Journal.open(dir, EventCodec.withBuiltins())) {
            journal.append(prompt());
            journal.append(new EventEnvelope(1, Instant.EPOCH, USER, SESSION, RUN,
                    new ContextAssembled(new CommandId("0-0"), "s", List.of())));
            journal.commit();
            Startup.Recovered recovered = Startup.recover(journal, kernel(), new SubmissionLedger());

            RunState run = recovered.state().runs().get(RUN);
            assertTrue(run.pendingRecovery());
            assertEquals("AwaitingModel", ((Awaiting) run.phase()).resumeAt());
            assertEquals(2, recovered.nextSeq());
        }
    }

    @Test
    void finishedRunIsNotMarked() throws Exception {
        try (Journal journal = Journal.open(dir, EventCodec.withBuiltins())) {
            journal.append(prompt());
            journal.append(new EventEnvelope(1, Instant.EPOCH, USER, SESSION, RUN, new Cancel(new RequestId("c"))));
            journal.commit();
            assertFalse(Startup.recover(journal, kernel(), new SubmissionLedger()).state().runs().get(RUN).pendingRecovery());
        }
    }

    @Test
    void ledgerIsRebuilt() throws Exception {
        try (Journal journal = Journal.open(dir, EventCodec.withBuiltins())) {
            journal.append(prompt());
            journal.commit();
            SubmissionLedger ledger = new SubmissionLedger();
            Startup.recover(journal, kernel(), ledger);
            assertEquals(new SubmissionId("sub"), ledger.claim(USER, new RequestId("req")).submission());
        }
    }

    @Test
    void corruptJournalRefusesToStart() throws Exception {
        try (Journal journal = Journal.open(dir, EventCodec.withBuiltins())) {
            journal.append(prompt());
            journal.commit();
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(Journal.FILE));
                    Statement statement = connection.createStatement()) {
                statement.execute("UPDATE events SET type = 'Mystery'");
            }
            assertThrows(JournalCorruptException.class, () -> Startup.recover(journal, kernel(), new SubmissionLedger()));
        }
    }

    @Test
    void transcriptIsRebuiltWithTheToolChain() throws Exception {
        try (Journal journal = Journal.open(dir, EventCodec.withBuiltins())) {
            ToolCallBlock call = new ToolCallBlock("c1", "read_file", "{}", new Json.Obj(Map.of()));
            journal.append(prompt());
            journal.append(new EventEnvelope(1, Instant.EPOCH, USER, SESSION, RUN,
                    new ContextAssembled(new CommandId("0-0"), "s", List.of())));
            journal.append(new EventEnvelope(2, Instant.EPOCH.plusSeconds(5), USER, SESSION, RUN,
                    new ModelReplied(new CommandId("1-0"), List.of(call), StopReason.TOOL_USE, "m", null)));
            journal.append(new EventEnvelope(3, Instant.EPOCH.plusSeconds(9), USER, SESSION, RUN,
                    new ToolFinished(new CommandId("2-0"), "c1", "file body", false)));
            journal.commit();

            Startup.Recovered recovered = Startup.recover(journal, kernel(), new SubmissionLedger());

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
