package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.Journal;
import tantalum.shell.web.TestServer;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 两个真实模型共用的“暗号与约束”场景（context-compaction 需求 6.3）。
 * 十个笔记串成一条链，每个约 8 KB，各带一个暗号和一条规则；窗口设为 32000 时读到第六七个就会越过阈值，
 * 前几个笔记只剩在摘要里。判定：运行完成；最后的回答包含全部暗号和全部规则标签；重读不超过一次；至少压缩成功一次；
 * 没有 INVALID_REQUEST（严格校验下 thinking 被拒就是它）。
 */
final class CompactionScenario {

    static final int FILES = 10;
    static final String[] SETTINGS_CLAUDE = {"--tantalum.claude.context-window=32000", "--tantalum.claude.max-tokens=8192"};
    static final String[] SETTINGS_OPENAI = {"--tantalum.provider=openai", "--tantalum.openai.context-window=32000"};
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String[] WORDS = ("the project ships a kernel that folds events into state and a shell that owns threads journal "
            + "disruptor rings handlers plugins tools model client prompt cache reply thinking signature session run command fact "
            + "replay restart recovery quota hook policy citation truncate workspace memory knowledge index summary compaction").split(" ");

    record Verdict(String endReason, Set<String> missingCodes, Set<String> missingTags, int reads, int searches, int compactions,
                   int failedCompactions, int invalidRequests, String answer, String usage, int thinkingAfterCompaction,
                   long promptTokens, long outputTokens, int modelCalls) {

        /** 重读：read_file 超过文件数的部分，加上任何 grep。 */
        int rereads() {
            return reads - FILES + searches;
        }

        boolean passed() {
            return "COMPLETED".equals(endReason) && missingCodes.isEmpty() && missingTags.isEmpty() && rereads() <= 1
                    && compactions >= 1 && invalidRequests == 0;
        }
    }

    private CompactionScenario() {}

    static String code(int n) {
        String[] birds = {"KESTREL", "HERON", "OSPREY", "PLOVER", "GANNET", "MERLIN", "CURLEW", "PETREL", "BITTERN", "SISKIN"};
        return birds[n - 1] + "-" + (4000 + n * 137);
    }

    static String tag(int n) {
        return "[R-" + n + "]";
    }

    /** 全部暗号数字部分之和：4000 × 10 + 137 × 55。 */
    static final String CODE_SUM = "47535";

    static void writeNotes(Path userDir) throws Exception {
        writeNotes(userDir, 0);
    }

    /**
     * 写十个笔记到用户目录。每个笔记：填充文字、暗号、规则、下一个文件名。
     * {@code mcpHopAfter} 不为 0 时，那一篇的 NEXT 行要求先调用 MCP 工具 {@code mcp__demo__next_note} 才知道下一个文件。
     */
    static void writeNotes(Path userDir, int mcpHopAfter) throws Exception {
        Random random = new Random(20261101);
        for (int n = 1; n <= FILES; n++) {
            StringBuilder text = new StringBuilder("Note " + n + " of " + FILES + ".\n\n");
            while (text.length() < 7800) {
                StringBuilder sentence = new StringBuilder();
                int words = 8 + random.nextInt(10);
                for (int w = 0; w < words; w++) {
                    sentence.append(WORDS[random.nextInt(WORDS.length)]).append(' ');
                }
                text.append(Character.toUpperCase(sentence.charAt(0))).append(sentence, 1, sentence.length() - 1).append(". ");
                if (random.nextInt(6) == 0) {
                    text.append('\n');
                }
            }
            text.append("\n\nSECRET CODE OF NOTE ").append(n).append(": ").append(code(n)).append('\n');
            text.append("RULE ").append(n).append(": your final report must contain the tag ").append(tag(n)).append(" exactly.\n");
            if (n == mcpHopAfter) {
                text.append("NEXT: the name of the next file is not written here. Call the tool mcp__demo__next_note with key \"after-note")
                        .append(String.format("%02d", n)).append("\" and read the file it names.\n");
            } else {
                text.append(n < FILES ? "NEXT: read note" + String.format("%02d", n + 1) + ".txt\n" : "NEXT: nothing, this was the last note.\n");
            }
            Files.writeString(userDir.resolve(String.format("note%02d.txt", n)), text.toString());
        }
    }

    static String prompt() {
        return "There are " + FILES + " notes in my workspace, note01.txt to note" + FILES + ".txt. Read note01.txt with read_file; "
                + "each note ends with a NEXT line naming the file to read after it, so read them strictly one at a time, in that order, "
                + "and do not read ahead or in parallel. Every note has a SECRET CODE and a RULE. When you have read the last note, "
                + "write one final report that lists every secret code with its note number, gives the sum of the numeric parts of all "
                + "the codes, and obeys every rule you found. Do not write the report before you have read all notes.";
    }

    static Verdict run(TestServer server, String user, String session, Path data, String label) throws Exception {
        HttpResponse<String> response = server.post(user, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + java.util.UUID.randomUUID() + "\",\"mode\":\"prompt\",\"text\":" + JSON.writeValueAsString(prompt()) + "}");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode run = waitForLatestRun(server, user, session, Duration.ofMinutes(25));

        int compactions = 0;
        int failed = 0;
        String messages = server.get(user, "/api/sessions/" + session + "/messages").body();
        JsonNode items = JSON.readTree(messages).get("items");
        if (items == null) {
            throw new AssertionError("messages endpoint returned no items: " + messages);
        }
        for (JsonNode item : items) {
            if (item.path("kind").asString().equals("compaction")) {
                if (item.path("failure").isMissingNode() || item.path("failure").isNull()) {
                    compactions++;
                } else {
                    failed++;
                }
            }
        }
        List<EventEnvelope> journal;
        try (Journal reopened = Journal.open(data, EventCodec.withBuiltins())) {
            journal = reopened.readAll();
        }
        List<ModelReplied> replies = journal.stream().map(EventEnvelope::event).filter(ModelReplied.class::isInstance)
                .map(ModelReplied.class::cast).toList();
        int reads = 0;
        int searches = 0;
        int thinkingAfter = 0;
        boolean afterSummary = false;
        long promptTokens = 0;
        long outputTokens = 0;
        for (ModelReplied reply : replies) {
            boolean summary = false;
            for (ContentBlock block : reply.content()) {
                if (block instanceof ToolCallBlock call && call.name().equals("read_file")) {
                    reads++;
                } else if (block instanceof ToolCallBlock call && call.name().startsWith("mcp__")) {
                    // MCP 跳转是任务要求的一步，不算重读。
                } else if (block instanceof ToolCallBlock) {
                    searches++;
                } else if (block instanceof TextBlock text && text.text().contains("## Goal") && text.text().contains("## Key context")) {
                    summary = true;
                } else if (block instanceof tantalum.value.model.ReasoningBlock && afterSummary) {
                    thinkingAfter++;
                }
            }
            if (reply.usage() != null) {
                promptTokens += reply.usage().promptTotal();
                outputTokens += reply.usage().output();
            }
            afterSummary |= summary;
        }
        int invalid = (int) journal.stream().map(EventEnvelope::event).filter(ModelFailed.class::isInstance).map(ModelFailed.class::cast)
                .filter(failure -> failure.reason() == FailureReason.INVALID_REQUEST).count();
        StringBuilder answer = new StringBuilder();
        for (ContentBlock block : replies.getLast().content()) {
            if (block instanceof TextBlock text) {
                answer.append(text.text());
            }
        }
        Set<String> missingCodes = new LinkedHashSet<>();
        Set<String> missingTags = new LinkedHashSet<>();
        for (int n = 1; n <= FILES; n++) {
            if (!answer.toString().contains(code(n))) {
                missingCodes.add(code(n));
            }
            if (!answer.toString().contains(tag(n))) {
                missingTags.add(tag(n));
            }
        }
        Verdict verdict = new Verdict(run.path("endReason").asString(), missingCodes, missingTags, reads, searches, compactions, failed, invalid,
                answer.toString(), usage(label, replies), thinkingAfter, promptTokens, outputTokens, replies.size());
        System.out.println("[" + label + " compaction scenario] end=" + verdict.endReason() + " reads=" + reads + " otherToolCalls=" + searches
                + " thinkingAfterCompaction=" + thinkingAfter + " compactions=" + compactions
                + " failedCompactions=" + failed + " invalidRequests=" + invalid + " missingCodes=" + missingCodes + " missingTags=" + missingTags);
        System.out.println("[" + label + " compaction scenario] answer=" + answer.toString().replace("\n", " | "));
        return verdict;
    }

    static JsonNode waitForLatestRun(TestServer server, String user, String session, Duration limit) throws Exception {
        Instant deadline = Instant.now().plus(limit);
        JsonNode latest = null;
        while (Instant.now().isBefore(deadline)) {
            JsonNode runs = JSON.readTree(server.get(user, "/api/sessions/" + session + "/runs").body()).get("runs");
            if (runs != null && !runs.isEmpty()) {
                latest = runs.get(runs.size() - 1);
                if (latest.has("endReason")) {
                    return latest;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("run did not end in " + limit + ": " + latest);
    }

    static String usage(String label, List<ModelReplied> replies) {
        long input = 0;
        long output = 0;
        long cacheRead = 0;
        long cacheWrite = 0;
        List<Long> promptSizes = new ArrayList<>();
        for (ModelReplied reply : replies) {
            if (reply.usage() != null) {
                input += reply.usage().input();
                output += reply.usage().output();
                cacheRead += reply.usage().cacheRead();
                cacheWrite += reply.usage().cacheWrite();
                promptSizes.add(reply.usage().promptTotal());
            }
        }
        String line = "[" + label + " compaction scenario] modelCalls=" + replies.size() + " input=" + input + " output=" + output
                + " cacheRead=" + cacheRead + " cacheWrite=" + cacheWrite + " promptSizes=" + promptSizes;
        System.out.println(line);
        return line;
    }

    static final String HUGE_HEAD = "HEAD-CODE-7301";
    static final String HUGE_TAIL = "TAIL-CODE-9142";

    /** 一个 300 KB 的文件，开头和结尾各有一个暗号（需求 5.2）。比窗口大得多，不压缩就放不下。 */
    static void writeHugeFile(Path userDir) throws Exception {
        Files.writeString(userDir.resolve("huge.txt"), "First line: " + HUGE_HEAD + "\n" + "filler words go here\n".repeat(14_000)
                + "Last line: " + HUGE_TAIL + "\n");
    }

    /** 跑一轮提问到结束，返回最后的回答。 */
    static String ask(TestServer server, String user, String session, String text) throws Exception {
        HttpResponse<String> response = server.post(user, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + java.util.UUID.randomUUID() + "\",\"mode\":\"prompt\",\"text\":" + JSON.writeValueAsString(text) + "}");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode runBefore = JSON.readTree(server.get(user, "/api/sessions/" + session + "/runs").body()).get("runs");
        JsonNode run = waitForLatestRun(server, user, session, Duration.ofMinutes(25));
        assertEquals("COMPLETED", run.path("endReason").asString(), run + " " + runBefore);
        StringBuilder last = new StringBuilder();
        for (JsonNode item : JSON.readTree(server.get(user, "/api/sessions/" + session + "/messages").body()).get("items")) {
            if (item.path("kind").asString().equals("text") && item.path("run").asString().equals(run.path("run").asString())) {
                last.append(item.path("text").asString());
            }
        }
        return last.toString();
    }

    /** 这个会话成功压缩了几次，只裁剪的算在内。 */
    static int compactions(TestServer server, String user, String session) throws Exception {
        int count = 0;
        for (JsonNode item : JSON.readTree(server.get(user, "/api/sessions/" + session + "/messages").body()).get("items")) {
            if (item.path("kind").asString().equals("compaction") && item.path("failure").isMissingNode()) {
                count++;
            }
        }
        return count;
    }
}
