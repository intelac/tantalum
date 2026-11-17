package tantalum.memory.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.memory.capture.NotePrompt.Kind;
import tantalum.memory.capture.NotePrompt.Note;

/** memory-capture 需求 2.2、5.4；memory-final-plan 第 7 节：每条笔记一行，带种类和原话；没有原话的不要。 */
class NotePromptTest {

    @Test
    void notesComeWithAKindAndAQuoteAndNothingMeansNoNote() {
        assertEquals(List.of(), NotePrompt.notes("NOTHING"));
        assertEquals(List.of(), NotePrompt.notes("  nothing \n"));
        assertEquals(List.of(NotePromptFixture.note(Kind.PREFERENCE, "回答用简体中文", "以后用简体中文"), NotePromptFixture.note(Kind.TASK, "下一步：记忆", "下一步做记忆"),
                NotePromptFixture.note(Kind.NOTE, "在做 Tantalum", "我在做 Tantalum"), NotePromptFixture.note(Kind.FACT, "没有原话的一条", "没有原话的一条")),
                NotePrompt.notes("# Notes\n- [preference] 回答用简体中文 «以后用简体中文»\n* [task] 下一步：记忆 “下一步做记忆”\n"
                        + "- 在做 Tantalum \"我在做 Tantalum\"\n- [fact] 没有原话的一条\n喜欢简体中文\n"),
                "a line without a quote uses its own text as the quote, which then has to be found in the user's words");
        assertEquals(Kind.NOTE, NotePrompt.notes("- [whatever] 要点 «原话原话»").getFirst().kind(), "an unknown kind is a plain note");
        assertEquals(List.of(), NotePrompt.notes("好的，以后都用简体中文，尽量简洁。"), "a reply that answers the user instead of noting is dropped");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            many.append("- [note] note ").append(i).append(" «words ").append(i).append("»\n");
        }
        assertEquals(NotePrompt.MAX_BULLETS, NotePrompt.notes(many.toString()).size(), "at most six");
    }

    /** 开着的事项随这一轮给模型；模型用编号关掉办完的，没给原话的不算。 */
    @Test
    void openItemsAreShownAndClosedById() {
        String prompt = NotePrompt.user("标题", "2026-11-17", "User: 修好了吗", List.of(new NotePrompt.Open("mm1abc", "折扣测试没过，\n要排查")));
        assertTrue(prompt.contains("</turn>\n\n<open-items>\n- mm1abc: 折扣测试没过， 要排查\n</open-items>"), prompt);
        assertFalse(NotePrompt.user("标题", "2026-11-17", "User: 你好").contains("open-items"), "no list when nothing is open");
        assertEquals(List.of(NotePromptFixture.note(Kind.DONE, "mm1abc", "8 个测试全部通过"), NotePromptFixture.note(Kind.DONE, "mm2def", "不做了")),
                NotePrompt.notes("- [done] mm1abc «8 个测试全部通过»\n- [done] mm2def: 折扣那件事 «不做了»\n- [done] mm3ghi"));
    }

    /** 模型在一行末尾写 (replaces 编号)：读成这条笔记取代的那条已有说法；没写为 null。全角括号也认。 */
    @Test
    void aNoteCanNameTheEntryItReplaces() {
        List<NotePrompt.Note> notes = NotePrompt.notes("""
                - [preference] 以后回答都用中文 «以后回答都用中文» (replaces mmw1abc)
                - [decision] 发布推迟到 12 月 8 日 «推迟到 12 月 8 日»（replaces mmw2def）
                - [fact] 负责库存系统 «我负责库存系统»
                """);
        assertEquals(new NotePrompt.Note(NotePrompt.Kind.PREFERENCE, "以后回答都用中文", "以后回答都用中文", "mmw1abc"), notes.get(0));
        assertEquals("mmw2def", notes.get(1).replaces());
        assertEquals("推迟到 12 月 8 日", notes.get(1).quote());
        assertEquals(null, notes.get(2).replaces());
        assertTrue(NotePrompt.system().contains("(replaces id)"));
    }
}
