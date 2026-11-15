package tantalum.shell.memory.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.trace.TraceRecord;
import tantalum.shell.trace.TraceRecord.Head;
import tantalum.shell.trace.TraceRecord.ModelRequest;
import tantalum.shell.trace.TraceRecord.ToolCall;
import tantalum.shell.trace.TraceRecord.UserInput;
import tantalum.value.id.RunId;

/** memory-capture 需求 1.1、2.2、5.2、5.4：摘出的文字、截短、遮盖、去掉回忆块、留头留尾；模型回答里的要点。 */
class RunExcerptTest {

    private static final RunId RUN = new RunId("s1_5");
    private static final Instant T = Instant.parse("2026-11-15T00:00:00Z");

    private static Head head(String id, String pos) {
        return new Head(id, pos, RUN, 1, 1, null, T, T);
    }

    private static ModelRequest answer(String id, String pos, String text) {
        return new ModelRequest(head(id, pos), 1, ModelRequest.Purpose.NORMAL, ModelRequest.Status.DONE, List.of(), 3, "local", "qwen", null, 0,
                null, null, text, null, null, null, null, List.of());
    }

    private static ToolCall tool(String id, String pos, String args, String result, ToolCall.Status status, String code) {
        return new ToolCall(head(id, pos), id, "read_file", null, args, null, status, result, code, null, "g", T, T, "m1");
    }

    @Test
    void theExcerptHasTheUserToolsAndTheLastAnswerWithSecretsMaskedAndRecallStripped() {
        String key = "sk-" + "ant-" + "api03-" + "A".repeat(40);
        List<TraceRecord> records = List.of(
                new UserInput(head("u", "5.0"), UserInput.Mode.PROMPT, "<recall>old note</recall>读一下 README", TraceRecord.Source.USER),
                answer("m1", "6.0", "我先读文件"),
                tool("t1", "7.0", "{\"path\":\"README.md\"}", "key=" + key + "\n" + "x".repeat(800), ToolCall.Status.DONE, null),
                tool("t2", "7.1", "{\"path\":\"nope\"}", null, ToolCall.Status.ERROR, "NOT_FOUND"),
                answer("m2", "8.0", "README 讲的是 Tantalum。"),
                new UserInput(head("c", "8.1"), UserInput.Mode.CONTINUE, "plugin continuation", TraceRecord.Source.plugin("cite")));
        String excerpt = RunExcerpt.of(records, 20_000);
        assertTrue(excerpt.startsWith("User: 读一下 README"), excerpt);
        assertFalse(excerpt.contains("old note"), "recall blocks are not noted again");
        assertFalse(excerpt.contains(key), "secrets in tool results are masked");
        assertTrue(excerpt.contains("Tool read_file({\"path\":\"nope\"}) failed: NOT_FOUND"));
        assertTrue(excerpt.endsWith("Assistant: README 讲的是 Tantalum。"), "only the last answer");
        assertFalse(excerpt.contains("我先读文件"));
        assertFalse(excerpt.contains("plugin continuation"));
        assertTrue(excerpt.contains("…"), "long results are cut");
    }

    @Test
    void longExcerptsKeepTheHeadAndTheTail() {
        String cut = RunExcerpt.middleCut("A".repeat(500) + "B".repeat(500), 200);
        assertTrue(cut.startsWith("A".repeat(80)));
        assertTrue(cut.endsWith("B".repeat(80)));
        assertTrue(cut.contains("chars omitted"));
        assertEquals("short", RunExcerpt.middleCut("short", 200));
    }

    @Test
    void bulletsComeFromTheReplyAndNothingMeansNoNote() {
        assertEquals(List.of(), NotePrompt.bullets("NOTHING"));
        assertEquals(List.of(), NotePrompt.bullets("  nothing \n"));
        assertEquals(List.of("- 用户在做 Tantalum", "- 下一步：记忆"),
                NotePrompt.bullets("# Notes\n- 用户在做 Tantalum\n* 下一步：记忆\n喜欢简体中文\n"));
        assertEquals(List.of(), NotePrompt.bullets("好的，以后都用简体中文，尽量简洁。"), "a reply that answers the user instead of noting is dropped");
        assertEquals(NotePrompt.MAX_BULLETS, NotePrompt.bullets("- note a\n- note b\n- note c\n- note d\n- note e\n- note f\n- note g\n- note h").size(), "at most six");
        assertEquals(List.of("- 第 1 节讲长期记忆"), NotePrompt.bullets("- 第 1 节标题是：\n- 第 1 节讲长期记忆"), "half sentences ending in a colon go");
        assertEquals(List.of("- 用户在准备下周的分享"), NotePrompt.bullets("- ```markdown\n- 用户在准备下周的分享\n- ```\n- 可追溯"), "code fences and fragments go");
    }
}
