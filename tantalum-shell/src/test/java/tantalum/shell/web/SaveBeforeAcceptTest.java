package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.loop.Intake;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;

/** one-line-journal 需求 2：提交那一步写进硬盘之后才回“已接受”；写不进去就不回。 */
class SaveBeforeAcceptTest {

    @TempDir
    Path temp;

    private final ModelClient model = (prompt, chunks) -> new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m",
            new TokenUsage(1, 1, 0, 0));

    private Path data() {
        return temp.resolve("data");
    }

    private TestServer server() throws Exception {
        Files.createDirectories(temp.resolve("workspace"));
        return new TestServer(data(), temp.resolve("workspace"), model);
    }

    private static HttpResponse<String> submit(TestServer server, String request) throws Exception {
        return server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"" + request + "\",\"mode\":\"prompt\",\"text\":\"hello\"}");
    }

    @Test
    void anAcceptedSubmissionIsAlreadyInTheFile() throws Exception {
        try (TestServer server = server()) {
            assertEquals(200, submit(server, "r-saved").statusCode());
            Path file = new SessionPaths(data()).sessionFile(new Principal("alice"), new SessionId("s1"));
            assertTrue(Files.readString(file).contains("\"r-saved\""), "the line is written before the answer");
        }
    }

    @Test
    void aSubmissionThatCannotBeSavedIsNotAccepted() throws Exception {
        Path userDir = new SessionPaths(data()).userDir(new Principal("alice"));
        Files.createDirectories(userDir);
        Files.setPosixFilePermissions(userDir, PosixFilePermissions.fromString("r-x------"));
        try (TestServer server = server()) {
            HttpResponse<String> first = submit(server, "r-lost");
            assertEquals(500, first.statusCode(), first.body());
            assertTrue(first.body().contains("保存失败"), first.body());
            assertEquals(503, submit(server, "r-lost").statusCode(), "the same request again: the original was not saved");
            assertEquals(503, submit(server, "r-next").statusCode(), "the process stopped handling events");
        } finally {
            Files.setPosixFilePermissions(userDir, PosixFilePermissions.fromString("rwx------"));
        }
        assertFalse(Files.exists(new SessionPaths(data()).sessionFile(new Principal("alice"), new SessionId("s1"))));
    }

    @Test
    void aSlowSaveAnswersBusyAndAFailedOneAnswersError() {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        Intake.Submission slow = new Intake.Submission(200, new SubmissionId("s"), false, pending);
        assertEquals(504, ApiController.waitSaved(slow, Duration.ofMillis(20)));

        pending.completeExceptionally(new IllegalStateException("disk full"));
        assertEquals(500, ApiController.waitSaved(slow, Duration.ofMillis(20)));

        Intake.Submission saved = new Intake.Submission(200, new SubmissionId("s"), false, CompletableFuture.completedFuture(null));
        assertEquals(200, ApiController.waitSaved(saved, Duration.ofMillis(20)));
    }
}
