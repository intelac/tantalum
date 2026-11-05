package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.KernelState;

/**
 * one-line-journal 需求 3.3、3.4、3.7：删了插件，旧对话照会话文件原样还原，不需要插件还在；
 * 之后的新一步不再配上它的工具和钩子，并告诉模型这个工具不能用了。
 */
class PluginRemovalTest {

    /** 插件提供的工具。 */
    static final class Fetch implements Tool {
        @Override public String name() { return "fetch_page"; }
        @Override public String description() { return "Fetch a page."; }
        @Override public Json schema() { return new Json.Obj(Map.of("type", new Json.Str("object"))); }
        @Override public ToolOutput run(ToolContext context, Json arguments) { return ToolOutput.ok("the page says hello"); }
    }

    /** 同一个插件的钩子：数一下运行结束前被调用了几次。 */
    static final class Notes implements KernelHook {
        static final AtomicInteger CALLS = new AtomicInteger();
        @Override public String name() { return "notes"; }
        @Override public HookResult beforeRunEnd(KernelState state, HookContext context) {
            CALLS.incrementAndGet();
            return Allow.PLAIN;
        }
    }

    @TempDir
    Path temp;

    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());

    /** 第一个问题先要一次工具，看到结果再回答；其余直接回答。 */
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        boolean sawResult = prompt.messages().stream().flatMap(message -> message.content().stream())
                .anyMatch(ToolResultBlock.class::isInstance);
        boolean first = prompt.messages().toString().contains("fetch it") && !sawResult;
        List<ContentBlock> reply = first
                ? List.of(new ToolCallBlock("call-1", "fetch_page", "{}", new Json.Obj(Map.of())))
                : List.of(new TextBlock("done"));
        return new ModelOutcome.Replied(reply, first ? StopReason.TOOL_USE : StopReason.END_TURN, "m", null);
    };

    private TestServer server(boolean withPlugin) throws Exception {
        Files.createDirectories(temp.resolve("ws"));
        return withPlugin
                ? new TestServer(temp.resolve("data"), temp.resolve("ws"), model, context -> {
                    context.registerBean("fetch", Tool.class, Fetch::new);
                    context.registerBean("notes", KernelHook.class, Notes::new);
                })
                : new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
    }

    private static void ask(TestServer server, String request, String text, int runs) throws Exception {
        String body = "{\"requestId\":\"" + request + "\",\"mode\":\"prompt\",\"text\":\"" + text + "\"}";
        assertEquals(200, server.post("alice", "/api/sessions/s1/submissions", body).statusCode());
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            String listed = server.get("alice", "/api/sessions/s1/runs").body();
            if (listed.split("COMPLETED", -1).length - 1 == runs) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("run " + runs + " did not finish");
    }

    @Test
    void anOldConversationOutlivesItsPluginAndTheNextStepNoLongerUsesIt() throws Exception {
        String messages;
        String trace;
        int hookCalls;
        try (TestServer server = server(true)) {
            ask(server, "r1", "fetch it", 1);
            messages = server.get("alice", "/api/sessions/s1/messages").body();
            trace = server.get("alice", "/api/sessions/s1/trace").body();
            hookCalls = Notes.CALLS.get();
        }
        assertTrue(messages.contains("fetch_page"), messages);
        assertTrue(hookCalls > 0, "the plugin's hook ran while it was installed");

        try (TestServer server = server(false)) {
            assertEquals(messages, server.get("alice", "/api/sessions/s1/messages").body(), "the old conversation is restored from the file");
            assertEquals(trace, server.get("alice", "/api/sessions/s1/trace").body(), "so is its trace");

            prompts.clear();
            ask(server, "r2", "and now?", 2);
        }
        assertEquals(hookCalls, Notes.CALLS.get(), "the removed plugin's hook does not run again");
        Prompt next = prompts.getFirst();
        List<ToolChangeBlock> changes = next.messages().stream().map(Message::content).flatMap(List::stream)
                .filter(ToolChangeBlock.class::isInstance).map(ToolChangeBlock.class::cast).toList();
        // 会话的工具列表从头到尾不变（core-tools C14，思考签名和缓存绑定它）；去掉的工具靠这条变化说明告诉模型
        assertEquals(List.of(new ToolChangeBlock(List.of(), List.of("fetch_page"))), changes, "the model is told the tool is gone");
    }
}
