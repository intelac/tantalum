package tantalum.value.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.OwnerRef;

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
        UserPrompt prompt = new UserPrompt(new SubmissionId("s"), new RequestId("r"), new OwnerRef.OfSession(new SessionId("s1")), "hi",
                List.of(), null, null, List.of());
        assertEquals(List.of(), prompt.attached());
        assertEquals(List.of(), new UserPrompt(prompt.submission(), prompt.request(), prompt.owner(), "hi", null, null, null, null, null)
                .attached());
        FollowUpSubmitted followUp = new FollowUpSubmitted(new SubmissionId("s"), new RequestId("r"), "hi", List.of(), null, List.of());
        assertEquals(List.of(), followUp.attached());
        assertEquals(List.of(new AttachedBlock("memory", "x")),
                new FollowUpSubmitted(followUp.submission(), followUp.request(), "hi", null, null, null, List.of(new AttachedBlock("memory", "x")))
                        .attached());
    }
}
