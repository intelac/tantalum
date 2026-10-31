package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.web.TestServer;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;

/** 非功能需求：被挂起的工具不延迟其他运行的事件处理。 */
class HungToolTest {

    @TempDir
    Path temp;

    @Test
    void otherRunsFinishWhileOneToolIsStuck() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        Tool stuck = new Tool() {
            @Override public String name() { return "stuck"; }
            @Override public String description() { return "never returns"; }
            @Override public Json schema() { return new Json.Obj(Map.of("type", new Json.Str("object"))); }
            @Override public ToolOutput run(tantalum.shell.plugin.ToolContext context, Json arguments) throws Exception {
                running.countDown();
                release.await();
                return ToolOutput.ok("late");
            }
        };
        ModelClient model = (prompt, chunks) -> {
            Message first = prompt.messages().getFirst();
            boolean wantsStuck = ((TextBlock) first.content().getFirst()).text().equals("use the stuck tool");
            if (wantsStuck && prompt.messages().size() == 1) {
                return new ModelOutcome.Replied(List.of(new ToolCallBlock("c", "stuck", "{}", new Json.Obj(Map.of()))),
                        StopReason.TOOL_USE, "m", null);
            }
            return new ModelOutcome.Replied(List.of(new TextBlock("done")), StopReason.END_TURN, "m", null);
        };
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), model,
                context -> context.registerBean("stuck", Tool.class, () -> stuck), "--tantalum.tool-timeout=120s")) {
            assertEquals(200, server.post("alice", "/api/sessions/hung/submissions",
                    "{\"requestId\":\"a\",\"mode\":\"prompt\",\"text\":\"use the stuck tool\"}").statusCode());
            assertTrue(running.await(10, TimeUnit.SECONDS));

            long start = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                assertEquals(200, server.post("bob", "/api/sessions/s" + i + "/submissions",
                        "{\"requestId\":\"b" + i + "\",\"mode\":\"prompt\",\"text\":\"hi\"}").statusCode());
            }
            for (int i = 0; i < 5; i++) {
                String body = "";
                for (int attempt = 0; attempt < 500 && !body.contains("COMPLETED"); attempt++) {
                    Thread.sleep(10);
                    body = server.get("bob", "/api/sessions/s" + i + "/runs").body();
                }
                assertTrue(body.contains("COMPLETED"), body);
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(millis < 5_000, "other runs took " + millis + " ms");
            assertTrue(server.get("alice", "/api/sessions/hung/runs").body().contains("AwaitingTools"));
            release.countDown();
        }
    }
}
