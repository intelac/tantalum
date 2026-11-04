package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/** record-results 任务 10（需求 9.1）：第 1 步冷载入时比较内核重放和结果行，不一致的会话在列表上标出。 */
class ReplayMismatchTest {

    private static final String REPLY = "the reply as the model wrote it";

    @TempDir
    Path temp;

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        return new ModelOutcome.Replied(List.of(new TextBlock(REPLY)), StopReason.END_TURN, "m", null);
    };

    private TestServer server() throws Exception {
        Files.createDirectories(temp.resolve("workspace"));
        return new TestServer(temp.resolve("data"), temp.resolve("workspace"), model);
    }

    private Path file() {
        return new SessionPaths(temp.resolve("data")).sessionFile(new Principal("alice"), new SessionId("s1"));
    }

    private void ask(TestServer server, String request, int calls) throws Exception {
        assertEquals(200, server.post("alice", "/api/sessions/s1/submissions",
                "{\"requestId\":\"" + request + "\",\"mode\":\"prompt\",\"text\":\"hello\"}").statusCode());
        for (int i = 0; i < 500 && (prompts.size() < calls || server.get("alice", "/api/sessions").body().contains("\"runStatus\":\"Awaiting")); i++) {
            Thread.sleep(10);
        }
        Thread.sleep(100);
    }

    /** 重启后第二次提问让会话冷载入，比较在那时发生。 */
    private String sessionsAfterReload() throws Exception {
        try (TestServer server = server()) {
            ask(server, "r2", 1);
            return server.get("alice", "/api/sessions").body();
        }
    }

    @Test
    void aSessionWhoseResultsAgreeIsNotMarked() throws Exception {
        try (TestServer server = server()) {
            ask(server, "r1", 1);
        }
        prompts.clear();
        String sessions = sessionsAfterReload();
        assertFalse(sessions.contains("replayMismatch"), sessions);
    }

    @Test
    void aResultLineThatNoLongerMatchesTheKernelIsMarked() throws Exception {
        try (TestServer server = server()) {
            ask(server, "r1", 1);
        }
        String content = Files.readString(file());
        List<String> lines = content.lines().map(line -> line.startsWith("{\"result\"") ? line.replace(REPLY, "a reply edited later") : line)
                .toList();
        assertTrue(lines.stream().anyMatch(line -> line.contains("a reply edited later")), content);
        Files.write(file(), lines);
        prompts.clear();

        String sessions = sessionsAfterReload();

        assertTrue(sessions.contains("\"replayMismatch\":true"), sessions);
        assertEquals(REPLY, ((TextBlock) prompts.getFirst().messages().get(1).content().getFirst()).text(),
                "step 1 still restores by replaying the kernel");
    }
}
