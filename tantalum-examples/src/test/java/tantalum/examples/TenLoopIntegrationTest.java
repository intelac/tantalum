package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.Replay;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.store.SessionStore;
import tantalum.shell.web.TestServer;
import tantalum.value.command.CallModel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.json.Json;
import tantalum.value.model.Prompt;
import tantalum.value.model.ToolCallBlock;

/** 需求 8 第 4 条：经过真实的环、journal、Handler、Web 接口和 SSE 跑完设计图上的十圈。 */
class TenLoopIntegrationTest {

    private static final List<String> PHASES = List.of(
            "AwaitingContext", "AwaitingModel", "AwaitingTools", "AwaitingModel", "AwaitingTools",
            "AwaitingModel", "AwaitingTools", "AwaitingModel", "AwaitingWork", "AwaitingModel", "Ended");

    @TempDir
    Path temp;

    private static Tool stub(String name, ToolOutput output) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Json schema() { return new Json.Obj(Map.of("type", new Json.Str("object"))); }
            @Override public ToolOutput run(tantalum.shell.plugin.ToolContext context, Json arguments) { return output; }
        };
    }

    private static ToolCallBlock call(String id, String name, String raw, Json args) {
        return new ToolCallBlock(id, name, raw, args);
    }

    private static void plugins(GenericApplicationContext context, Path workspace) {
        context.registerBean("symbolSearch", Tool.class, () -> stub("symbol_search", ToolOutput.error("index still building")));
        context.registerBean("readSymbol", Tool.class, () -> stub("read_symbol", ToolOutput.ok("COMPUTE INTEREST = RATE")));
        context.registerBean("citationCheck", KernelHook.class, CitationCheckHook::new);
        context.registerBean("citationWork", WorkType.class, CitationWork::new);
        context.registerBean("truncate", AfterToolHook.class, () -> new TruncateOutputHook(10_000));
    }

    @Test
    void tenTurnsThroughTheRealStackAndReplayMatches() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.createDirectory(workspace.resolve("alice"));
        Path data = temp.resolve("data");
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                ScriptedModelClient.tools(call("1", "symbol_search", "{}", new Json.Obj(Map.of()))),
                ScriptedModelClient.tools(call("2", "grep", "{\"pattern\":\"COMPUTE\"}",
                        new Json.Obj(Map.of("pattern", new Json.Str("COMPUTE"))))),
                ScriptedModelClient.tools(call("3", "read_symbol", "{}", new Json.Obj(Map.of()))),
                // 没有读过 INTEREST.cbl（grep 因为没连电脑而失败），引用核对让模型改正一次（A3：只看会话里读到的）
                ScriptedModelClient.answer("It is in INTEREST.cbl:1"),
                ScriptedModelClient.answer("The COMPUTE INTEREST statement calculates it.")));

        String run;
        String turnsBefore;
        try (TestServer server = new TestServer(data, workspace, model, context -> plugins(context, workspace))) {
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            HttpResponse<InputStream> stream = server.http.send(server.request("alice", "/api/events").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            Thread.ofVirtual().start(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(stream.body()))) {
                    in.lines().forEach(events::add);
                } catch (Exception closed) {
                    // 服务关闭时连接断开。
                }
            });

            HttpResponse<String> submitted = server.post("alice", "/api/sessions/s1/submissions",
                    "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"where is the interest calculated?\"}");
            assertEquals(200, submitted.statusCode(), submitted.body());

            String runs = "";
            for (int i = 0; i < 1000 && !runs.contains("COMPLETED"); i++) {
                Thread.sleep(10);
                runs = server.get("alice", "/api/sessions/s1/runs").body();
            }
            assertTrue(runs.contains("COMPLETED"), runs);
            int start = runs.indexOf("s1_");
            run = runs.substring(start, runs.indexOf('"', start));

            for (int i = 0; i < 500 && phases(events).size() < PHASES.size(); i++) {
                Thread.sleep(10);
            }
            assertEquals(PHASES, phases(events));
            assertTrue(String.join("\n", events).contains("event:chunk"));

            turnsBefore = server.get("alice", "/api/runs/" + run + "/turns/4").body();
            assertTrue(turnsBefore.contains("It is in INTEREST.cbl:1"), turnsBefore);
            stream.body().close();
        }

        assertEquals(5, model.prompts.size());

        List<EventEnvelope> journal;
        journal = new SessionStore(data, EventCodec.withBuiltins()).readAll();
        assertEquals(List.of("UserPrompt", "ContextAssembled", "ModelReplied", "ToolFinished", "ModelReplied",
                "ToolFinished", "ModelReplied", "ToolFinished", "ModelReplied", "WorkDone", "ModelReplied"),
                journal.stream().map(envelope -> envelope.event().type()).toList());

        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Kernel kernel = new Kernel(table, new HookChain(List.of(new CitationCheckHook())), (tool, args) -> false, Limits.DEFAULT);
        Replay.Result replay = Replay.run(kernel, journal);
        List<Prompt> replayed = replay.commands().stream().filter(CallModel.class::isInstance)
                .map(command -> ((CallModel) command).prompt()).toList();
        assertEquals(model.prompts, replayed);

        ScriptedModelClient unused = new ScriptedModelClient(List.of());
        try (TestServer again = new TestServer(data, workspace, unused, context -> plugins(context, workspace))) {
            assertTrue(again.get("alice", "/api/runs/" + run).body().contains("COMPLETED"));
            assertEquals(turnsBefore, again.get("alice", "/api/runs/" + run + "/turns/4").body());
            assertEquals(0, unused.prompts.size());
        }
    }

    /** 只看 {@code PhaseChanged} 事实；{@code item} 里的运行状态也带 {@code phase}，不算。 */
    private static List<String> phases(List<String> lines) {
        List<String> phases = new ArrayList<>();
        synchronized (lines) {
            String event = "";
            for (String line : lines) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                }
                int at = line.indexOf("\"phase\":\"");
                if (line.startsWith("data:") && at >= 0 && event.equals("PhaseChanged")) {
                    int from = at + 9;
                    phases.add(line.substring(from, line.indexOf('"', from)));
                }
            }
        }
        return phases;
    }
}
