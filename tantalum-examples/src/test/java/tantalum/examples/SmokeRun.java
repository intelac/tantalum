package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.model.Prompt;

/** 冒烟测试共用：让真实模型读一个文件再回答，并记录每次模型调用的耗时。 */
final class SmokeRun {

    private SmokeRun() {}

    /** 包一层，记录耗时和每次调用的结果。 */
    static final class Timed implements ModelClient {
        final List<Long> millis = Collections.synchronizedList(new ArrayList<>());
        final List<ModelOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        private final ModelClient real;

        Timed(ModelClient real) {
            this.real = real;
        }

        @Override
        public ModelOutcome call(Prompt prompt, ChunkListener chunks) {
            long start = System.nanoTime();
            ModelOutcome outcome = real.call(prompt, chunks);
            millis.add((System.nanoTime() - start) / 1_000_000);
            outcomes.add(outcome);
            return outcome;
        }
    }

    static Timed run(String label, ModelClient client, Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.createDirectory(workspace.resolve("smoke"));
        Files.writeString(workspace.resolve("smoke/note.txt"), "The secret word is ZEBRA-4417.\n");
        Timed timed = new Timed(client);
        try (TestServer server = new TestServer(temp.resolve("data"), workspace, timed)) {
            assertEquals(200, server.post("smoke", "/api/sessions/s1/submissions",
                    "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"Use the read_file tool to read note.txt, then tell me the secret word.\"}").statusCode());
            String runs = "";
            for (int i = 0; i < 12_000 && !runs.contains("endReason"); i++) {
                Thread.sleep(50);
                runs = server.get("smoke", "/api/sessions/s1/runs").body();
            }
            System.out.println("[smoke " + label + "] model call millis=" + timed.millis + " runs=" + runs);
            assertTrue(runs.contains("COMPLETED"), runs);
            int start = runs.indexOf("s1_");
            String run = runs.substring(start, runs.indexOf('"', start));
            String last = server.get("smoke", "/api/runs/" + run + "/turns/" + timed.millis.size()).body();
            assertTrue(last.contains("ZEBRA-4417"), last);
            assertTrue(timed.millis.size() >= 2, "the model must have called the tool");
        }
        return timed;
    }
}
