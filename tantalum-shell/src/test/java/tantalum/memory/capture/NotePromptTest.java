package tantalum.memory.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(List.of(new Note(Kind.PREFERENCE, "回答用简体中文", "以后用简体中文"), new Note(Kind.TASK, "下一步：记忆", "下一步做记忆"),
                new Note(Kind.NOTE, "在做 Tantalum", "我在做 Tantalum"), new Note(Kind.FACT, "没有原话的一条", "没有原话的一条")),
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
}
