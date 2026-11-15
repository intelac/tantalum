package tantalum.memory.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** memory-capture 需求 2.2、5.4：模型的回答里只留要点。 */
class NotePromptTest {

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
