package tantalum.value.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tantalum.value.hook.Ask;
import tantalum.value.json.Json;
import tantalum.value.text.Language;
import tantalum.value.text.LocalText;

class QuestionTest {

    private static final Question APPROVAL = new Question("approval-1", LocalText.of("Allow bash?"), null,
            List.of(new Question.Choice("once", LocalText.of("Allow once")), new Question.Choice("deny", LocalText.of("Deny"))));

    // 需求 4.1：没有选项只有允许自由文本时才行；选项 id 不重复
    @Test
    void validation() {
        assertTrue(APPROVAL.detail().isEmpty());
        assertFalse(APPROVAL.freeText());
        assertThrows(IllegalArgumentException.class, () -> new Question("q", LocalText.of("t"), null, List.of(), false, null));
        assertTrue(new Question("q", LocalText.of("t"), null, List.of(), true, null).choices().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Question("q", LocalText.of("t"), null,
                List.of(new Question.Choice("a", LocalText.of("a")), new Question.Choice("a", LocalText.of("b")))));
    }

    // 需求 6.2：选项要是列出的；文字只有允许时才能有、不超 4000 字
    @Test
    void accepts() {
        assertTrue(APPROVAL.accepts(Answer.of("once")));
        assertFalse(APPROVAL.accepts(Answer.of("maybe")));
        assertFalse(APPROVAL.accepts(new Answer("once", "and a note")));
        assertFalse(APPROVAL.accepts(null));

        Question free = new Question("q", LocalText.of("Which?"), null,
                List.of(new Question.Choice("keep", LocalText.of("keep"))), true, null);
        assertTrue(free.accepts(new Answer(null, "archive to old/")));
        assertTrue(free.accepts(new Answer("keep", "x".repeat(Question.MAX_TEXT))));
        assertFalse(free.accepts(new Answer("keep", "x".repeat(Question.MAX_TEXT + 1))));
        assertThrows(IllegalArgumentException.class, () -> new Answer("", ""));
    }

    // D4：插件的问题不允许自由文本；期限给 Duration，不给时刻
    @Test
    void askRules() {
        assertNull(new Ask(APPROVAL).deadline());
        assertEquals(Duration.ofMinutes(10), new Ask(APPROVAL).after(Duration.ofMinutes(10)).deadline());
        Question free = new Question("q", LocalText.of("t"), null, List.of(), true, null);
        assertThrows(IllegalArgumentException.class, () -> new Ask(free));
        assertThrows(IllegalArgumentException.class, () -> new Ask(APPROVAL, Duration.ZERO));
    }

    // 需求 5.1、5.3：ask_user 的参数和结果
    @Test
    void askUserParse() {
        AskUser.Parsed parsed = AskUser.parse("toolu_1", obj(Map.of(
                "question", new Json.Str(" Delete the 3 backups? "),
                "choices", new Json.Arr(List.of(new Json.Str("delete"), new Json.Str("keep"))),
                "timeout_seconds", new Json.Num(BigDecimal.valueOf(600)))));
        assertEquals("ask-toolu_1", parsed.question().id());
        assertEquals("Delete the 3 backups?", parsed.question().title().in(Language.EN));
        assertEquals(List.of("delete", "keep"), parsed.question().choices().stream().map(Question.Choice::id).toList());
        assertTrue(parsed.question().freeText());
        assertNull(parsed.question().deadline());
        assertEquals(Duration.ofSeconds(600), parsed.timeout());

        AskUser.Parsed textOnly = AskUser.parse("toolu_2", obj(Map.of("question", new Json.Str("Name?"))));
        assertTrue(textOnly.question().choices().isEmpty());
        assertNull(textOnly.timeout());

        assertMessage("question", () -> AskUser.parse("c", obj(Map.of("question", new Json.Str("  ")))));
        assertThrows(IllegalArgumentException.class, () -> AskUser.parse("c", new Json.Str("x")));
        assertMessage("distinct", () -> AskUser.parse("c", obj(Map.of("question", new Json.Str("q"),
                "choices", new Json.Arr(List.of(new Json.Str("a"), new Json.Str("a")))))));
        assertMessage("choices", () -> AskUser.parse("c", obj(Map.of("question", new Json.Str("q"), "allow_text", new Json.Bool(false)))));
        assertMessage("timeout_seconds", () -> AskUser.parse("c", obj(Map.of("question", new Json.Str("q"),
                "timeout_seconds", new Json.Num(new BigDecimal("1.5"))))));
        List<Json> nine = IntStream.range(0, 9).<Json>mapToObj(i -> new Json.Str("c" + i)).toList();
        assertMessage("1 to 8", () -> AskUser.parse("c", obj(Map.of("question", new Json.Str("q"), "choices", new Json.Arr(nine)))));
    }

    @Test
    void askUserResult() {
        assertEquals("{\"choice\":\"keep\"}", AskUser.result(Answer.of("keep")));
        assertEquals("{\"choice\":\"keep\",\"text\":\"归档到 \\\"old/\\\"\\n\"}", AskUser.result(new Answer("keep", "归档到 \"old/\"\n")));
        assertEquals("{\"text\":\"x\"}", AskUser.result(new Answer(null, "x")));
        assertEquals("{\"timeout\":true}", AskUser.TIMEOUT);
    }

    private static void assertMessage(String fragment, org.junit.jupiter.api.function.Executable call) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, call);
        assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
    }

    private static Json obj(Map<String, Json> fields) {
        return new Json.Obj(fields);
    }
}
