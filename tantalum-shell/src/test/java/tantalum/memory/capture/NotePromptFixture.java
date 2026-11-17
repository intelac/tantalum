package tantalum.memory.capture;

import static tantalum.memory.capture.NotePrompt.*;
import tantalum.memory.capture.NotePrompt.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class NotePromptFixture {

    private NotePromptFixture() {}

    public static NotePrompt.Note note(Kind kind, String text, String quote) {
        return new NotePrompt.Note(kind, text, quote, null);
    }
}
