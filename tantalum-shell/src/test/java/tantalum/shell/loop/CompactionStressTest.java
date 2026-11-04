package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.Replay;
import tantalum.kernel.compaction.SummaryPrompt;
import tantalum.kernel.compaction.TokenEstimate;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.store.SessionStore;
import tantalum.shell.web.TestServer;
import tantalum.value.command.CallModel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 一个会话 500 条消息、窗口设小，经历多次自动压缩；另一个用户同时跑自己的运行。不用真实模型。
 * 检查的不变量：每次压缩后发给模型的视图确实变小且始终有界；回放得到的 prompt 与实时逐个相同；
 * 重启后由回放重建的对话视图逐字节相同且不再调用模型；另一个用户的 prompt 里没有这个会话的内容。
 */
class CompactionStressTest {

    /** 250 次提问加 250 次回复。 */
    private static final int TURNS = 250;
    private static final int OTHER_TURNS = 20;
    /**
     * 窗口 5000、预留 500：阈值 4500；保留 600。工具说明也计入估算：core-tools 加了 4 个内置工具后窗口从 4000 加到 5000，
     * 让另一个用户的短会话仍不触发压缩。
     */
    private static final int THRESHOLD = 4500;
    private static final String[] SETTINGS = {
        "--tantalum.claude.context-window=5000",
        "--tantalum.claude.max-tokens=500",
        "--tantalum.compaction.reserve-tokens=500",
        "--tantalum.compaction.keep-recent-tokens=600",
        "--tantalum.claude.concurrency=4"
    };
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    /** 无状态：按 prompt 决定回复，用量按估算报，这样锚点才像真的。摘要调用返回一句话，普通调用回一句带编号的答案。 */
    static final class CountingModel implements ModelClient {
        final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ModelOutcome call(Prompt prompt, ChunkListener chunks) {
            prompts.add(prompt);
            long size = TokenEstimate.LEGACY.ofText(prompt.system()) + TokenEstimate.LEGACY.ofTools(prompt.tools())
                    + TokenEstimate.LEGACY.ofMessages(prompt.messages());
            String text;
            if (!prompt.output().toolsAllowed()) {
                text = "## Goal\nKeep counting for " + lastUser(prompt) + ".\n## Key context\n" + lastUser(prompt);
            } else {
                text = "Reply to " + lastUser(prompt) + ". " + "filler ".repeat(20);
                chunks.onText(text);
            }
            return new ModelOutcome.Replied(List.of(new TextBlock(text)), StopReason.END_TURN, "counting",
                    new TokenUsage(size, 40, 0, 0));
        }

        /** 最新一条用户亲笔消息的第一行；摘要调用里它在指令之前。 */
        static String lastUser(Prompt prompt) {
            List<Message> messages = prompt.messages();
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message message = messages.get(i);
                if (message.role() == Role.USER && message.content().getFirst() instanceof TextBlock text
                        && text.text().startsWith("message ")) {
                    return text.text().split("\n", 2)[0];
                }
            }
            return "nothing";
        }
    }

    private static String text(String user, int n) {
        return "message " + String.format("%03d", n) + " from " + user + "\n" + ("lorem ipsum " + user + " ").repeat(10);
    }

    private static void waitForRuns(TestServer server, String user, String session, int count) throws Exception {
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            JsonNode runs = JSON.readTree(server.get(user, "/api/sessions/" + session + "/runs").body()).get("runs");
            if (runs != null && runs.size() == count && runs.get(count - 1).has("endReason")) {
                assertEquals("COMPLETED", runs.get(count - 1).get("endReason").asString(), runs.get(count - 1).toString());
                return;
            }
            Thread.sleep(3);
        }
        throw new AssertionError("run " + count + " of " + session + " did not end");
    }

    private static void converse(TestServer server, String user, String session, int turns) throws Exception {
        for (int n = 1; n <= turns; n++) {
            String body = JSON.writeValueAsString(java.util.Map.of("requestId", "r-" + session + "-" + n, "mode", "prompt",
                    "text", text(user, n)));
            assertEquals(200, server.post(user, "/api/sessions/" + session + "/submissions", body).statusCode());
            waitForRuns(server, user, session, n);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void fiveHundredMessagesCompactRepeatedlyAndReplayExactly() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path data = temp.resolve("data");
        CountingModel model = new CountingModel();
        String longBody;
        String otherBody;
        try (TestServer server = new TestServer(data, workspace, model, SETTINGS)) {
            server.get("alice", "/api/sessions");
            server.get("bob", "/api/sessions");
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            Thread other = Thread.ofVirtual().start(() -> {
                try {
                    converse(server, "bob", "bob-short", OTHER_TURNS);
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
            converse(server, "alice", "alice-long", TURNS);
            other.join();
            assertTrue(failures.isEmpty(), failures.toString());
            longBody = history(server, "alice", "alice-long");
            otherBody = history(server, "bob", "bob-short");
        }

        // 1. 压缩发生了很多次，且每次让视图变小；普通调用的视图始终有界。
        JsonNode items = JSON.readTree(longBody);
        int compactions = 0;
        int users = 0;
        int texts = 0;
        for (JsonNode item : items) {
            switch (item.get("kind").asString()) {
                case "compaction" -> {
                    compactions++;
                    assertTrue(item.path("failure").isMissingNode() || item.path("failure").isNull(), item.toString());
                    assertTrue(item.get("estimateAfter").asLong() < item.get("estimateBefore").asLong(), item.toString());
                    assertEquals("AUTO", item.get("trigger").asString());
                }
                case "user" -> users++;
                case "text" -> texts++;
                default -> { }
            }
        }
        assertTrue(compactions >= 5, "only " + compactions + " compactions over " + TURNS + " turns");
        assertEquals(TURNS, users, "every original user message is still shown");
        assertEquals(TURNS, texts, "every original reply is still shown; summaries are not replies");

        List<Prompt> alice = model.prompts.stream().filter(prompt -> CountingModel.lastUser(prompt).contains("alice")).toList();
        int summaryCalls = 0;
        int maxMessages = 0;
        for (Prompt prompt : alice) {
            if (!prompt.output().toolsAllowed()) {
                summaryCalls++;
                assertEquals(8192, prompt.output().maxTokens(), "summary-max-tokens default");
                assertTrue(((TextBlock) prompt.messages().getLast().content().getFirst()).text().contains("## Goal"));
            } else {
                maxMessages = Math.max(maxMessages, prompt.messages().size());
                long size = TokenEstimate.LEGACY.ofMessages(prompt.messages());
                assertTrue(size < THRESHOLD + 200, "a normal call carried " + size + " estimated tokens");
            }
        }
        assertEquals(compactions, summaryCalls);
        assertTrue(maxMessages < 80, "the view kept growing: " + maxMessages + " messages in one prompt");
        assertTrue(alice.stream().filter(prompt -> prompt.output().toolsAllowed())
                .anyMatch(prompt -> prompt.messages().stream().anyMatch(SummaryPrompt::isSummary)), "post-compaction prompts start with a summary");

        // 2. 另一个用户的 prompt 里没有 alice 的内容，也没有压缩。
        List<Prompt> bob = model.prompts.stream().filter(prompt -> CountingModel.lastUser(prompt).contains("bob")).toList();
        assertEquals(OTHER_TURNS, bob.size());
        for (Prompt prompt : bob) {
            assertTrue(prompt.output().toolsAllowed());
            for (Message message : prompt.messages()) {
                for (ContentBlock block : message.content()) {
                    assertTrue(!(block instanceof TextBlock text) || !text.text().contains("alice"), "bob saw alice's text");
                }
            }
        }

        // 3. 回放：每一次 CallModel（含摘要调用）的 prompt 与实时逐个相同。
        List<EventEnvelope> journal;
        journal = new SessionStore(data, EventCodec.withBuiltins()).readAll();
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Kernel kernel = new Kernel(table, new HookChain(List.of()), (tool, args) -> false, Limits.DEFAULT);
        List<Prompt> replayed = Replay.run(kernel, journal).commands().stream()
                .filter(CallModel.class::isInstance).map(command -> ((CallModel) command).prompt()).toList();
        List<Prompt> live = List.copyOf(model.prompts);
        assertEquals(live.size(), replayed.size());
        // 两个用户的调用交错，但每个会话内部顺序一致：按会话分别比较。
        assertEquals(live.stream().filter(prompt -> CountingModel.lastUser(prompt).contains("alice")).toList(),
                replayed.stream().filter(prompt -> CountingModel.lastUser(prompt).contains("alice")).toList(), "alice's prompts differ on replay");
        assertEquals(bob, replayed.stream().filter(prompt -> CountingModel.lastUser(prompt).contains("bob")).toList());

        // 4. 重启：视图逐字节相同，模型没被调用。
        CountingModel mustNotRun = new CountingModel();
        try (TestServer restarted = new TestServer(data, workspace, mustNotRun, SETTINGS)) {
            assertEquals(longBody, history(restarted, "alice", "alice-long"));
            assertEquals(otherBody, history(restarted, "bob", "bob-short"));
            Thread.sleep(100);
            assertEquals(0, mustNotRun.prompts.size());
        }
        System.out.println("[compaction stress] turns=" + TURNS + " compactions=" + compactions + " modelCalls=" + model.prompts.size()
                + " events=" + journal.size() + " maxMessagesInPrompt=" + maxMessages);
    }

    /** 接口分页（任务 35）：从最近一页往前翻到头，拼成完整历史，返回条目数组的 JSON。 */
    private static String history(TestServer server, String user, String session) throws Exception {
        tools.jackson.databind.node.ArrayNode all = JSON.createArrayNode();
        String query = "";
        while (true) {
            JsonNode page = JSON.readTree(server.get(user, "/api/sessions/" + session + "/messages" + query).body());
            tools.jackson.databind.node.ArrayNode items = JSON.createArrayNode();
            page.get("items").forEach(items::add);
            items.addAll(all);
            all = items;
            if (!page.get("hasMore").asBoolean()) {
                return all.toString();
            }
            query = "?before=" + page.get("start").asInt();
        }
    }
}
