package tantalum.value.model;

import tantalum.value.event.Delivery;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/** memory-plugin 需求 1.2：插件附加的内容块、用户消息里的位置、提交事件的默认值。 */
class AttachedBlockTest {

    @Test
    void anAttachedTextNamesItsPluginAndIsWrappedForTheModel() {
        AttachedBlock block = new AttachedBlock("memory", " - 用户在做 Tantalum \n");
        assertEquals("<attached plugin=\"memory\">\n- 用户在做 Tantalum\n</attached>", block.wrapped());
        assertThrows(IllegalArgumentException.class, () -> new AttachedBlock("", "x"));
        assertThrows(IllegalArgumentException.class, () -> new AttachedBlock("memory", "  "));
    }

    @Test
    void attachedBlocksComeAfterTextAttachmentsAndSkills() {
        AttachedBlock attached = new AttachedBlock("memory", "note");
        SkillBlock skill = new SkillBlock("review", "how to review");
        Message message = Message.user("hi", List.of(), List.of(skill), List.of(attached));
        assertEquals(List.of(new TextBlock("hi"), skill, attached), message.content());
        assertEquals(Message.user("hi"), Message.user("hi", List.of(), List.of(), List.of()));
    }

    @Test
    void submissionsWithoutAttachedTextsHaveAnEmptyList() {
        UserPrompt prompt = new UserPrompt(new SubmissionId("s"), new RequestId("r"), "hi", List.of(), null, List.of(), List.of(), Delivery.QUEUE);
        assertEquals(List.of(), prompt.attached());
        assertEquals(List.of(), new UserPrompt(prompt.submission(), prompt.request(), "hi", null, null, null, null, Delivery.JOIN).attached());
        assertEquals(List.of(new AttachedBlock("memory", "x")),
                new UserPrompt(prompt.submission(), prompt.request(), "hi", null, null, null, List.of(new AttachedBlock("memory", "x")), Delivery.QUEUE)
                        .attached());
    }
}
