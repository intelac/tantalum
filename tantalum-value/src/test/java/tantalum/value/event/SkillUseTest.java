package tantalum.value.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.attachment.FileRef;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.SkillBlock;
import tantalum.value.model.TextBlock;

/** 用户点名的技能在值层（skills-wiki 任务 15、16）：一项要么有内容要么有原因；提交默认没有；消息里排在文字和附件之后。 */
class SkillUseTest {

    private static final SubmissionId SUB = new SubmissionId("s");
    private static final RequestId REQ = new RequestId("r");
    private static final SkillUse READ = new SkillUse("pdf-tools", "<skill_content name=\"pdf-tools\">x</skill_content>", null);
    private static final SkillUse FAILED = new SkillUse("slow", null, "timeout");
    private static final SkillBlock BLOCK = new SkillBlock("pdf-tools", READ.content());
    private static final FileRef FILE = new FileRef("sha256:" + "b".repeat(64), "notes.txt", "text/plain", 5, null);

    @Test
    void aNamedSkillHasEitherItsContentOrTheReasonItWasNotRead() {
        assertTrue(READ.loaded());
        assertFalse(FAILED.loaded());
        assertThrows(IllegalArgumentException.class, () -> new SkillUse("x", null, null));
        assertThrows(IllegalArgumentException.class, () -> new SkillUse("x", "content", "timeout"));
        assertThrows(NullPointerException.class, () -> new SkillUse(null, "content", null));
        assertThrows(NullPointerException.class, () -> new SkillBlock(null, "text"));
        assertThrows(NullPointerException.class, () -> new SkillBlock("x", null));
    }

    @Test
    void submissionsHaveNoSkillsUnlessGivenAndKeepTheirOwnCopy() {
        assertEquals(List.of(), UserPromptFixture.of(SUB, REQ, "hi").skills());
        assertEquals(List.of(), UserPromptFixture.of(SUB, REQ, "hi", List.of(), null).skills());
        assertEquals(List.of(), new UserPrompt(SUB, REQ, "hi", List.of(), null, null, List.of(), Delivery.QUEUE).skills());
        assertEquals(List.of(), UserPromptFixture.join(SUB, REQ, "hi").skills());
        assertEquals(List.of(), UserPromptFixture.join(SUB, REQ, "hi", List.of()).skills());

        List<SkillUse> given = new ArrayList<>(List.of(READ, FAILED));
        UserPrompt queued = new UserPrompt(SUB, REQ, "/pdf-tools", List.of(), null, given, List.of(), Delivery.QUEUE);
        UserPrompt joined = new UserPrompt(SUB, REQ, "/pdf-tools", List.of(), null, given, List.of(), Delivery.JOIN);
        given.clear();
        assertEquals(List.of(READ, FAILED), queued.skills());
        assertEquals(List.of(READ, FAILED), joined.skills());
        assertThrows(NullPointerException.class, () -> new UserPrompt(SUB, REQ, "hi", List.of(), null, List.of(), List.of(), null),
                "how to deliver it while the session is busy is always said");
    }

    @Test
    void skillBlocksComeAfterTheTextAndTheAttachments() {
        assertEquals(Message.user("hi"), Message.user("hi", List.of(), List.of()));
        assertEquals(Message.user("hi", List.of(FILE)), Message.user("hi", List.of(FILE), List.of()));
        assertEquals(new Message(Role.USER, List.of(new TextBlock("hi"), BLOCK), false), Message.user("hi", List.of(), List.of(BLOCK)));
        assertEquals(new Message(Role.USER, List.of(new TextBlock("hi"), new AttachmentBlock(FILE), BLOCK), false),
                Message.user("hi", List.of(FILE), List.of(BLOCK)));
    }
}
