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
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.SkillBlock;
import tantalum.value.model.TextBlock;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.Queue;

/** 用户点名的技能在值层（skills-wiki 任务 15、16）：一项要么有内容要么有原因；三种提交默认没有；消息里排在文字和附件之后；找排队的那条时不看它。 */
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
        OwnerRef owner = new OwnerRef.OfSession(new SessionId("s"));
        assertEquals(List.of(), UserPromptFixture.of(SUB, REQ, owner, "hi").skills());
        assertEquals(List.of(), UserPromptFixture.of(SUB, REQ, owner, "hi", List.of(), null, "/work").skills());
        assertEquals(List.of(), new UserPrompt(SUB, REQ, owner, "hi", List.of(), null, null, null).skills());
        assertEquals(List.of(), SteerSubmittedFixture.of(SUB, REQ, "hi").skills());
        assertEquals(List.of(), SteerSubmittedFixture.of(SUB, REQ, "hi", List.of()).skills());
        assertEquals(List.of(), new SteerSubmitted(SUB, REQ, "hi", List.of(), null).skills());
        assertEquals(List.of(), FollowUpSubmittedFixture.of(SUB, REQ, "hi").skills());
        assertEquals(List.of(), FollowUpSubmittedFixture.of(SUB, REQ, "hi", List.of(), null).skills());
        assertEquals(List.of(), new FollowUpSubmitted(SUB, REQ, "hi", List.of(), null, null).skills());

        List<SkillUse> given = new ArrayList<>(List.of(READ, FAILED));
        UserPrompt prompt = new UserPrompt(SUB, REQ, owner, "/pdf-tools", List.of(), null, null, given);
        SteerSubmitted steer = new SteerSubmitted(SUB, REQ, "/pdf-tools", List.of(), given);
        FollowUpSubmitted followUp = new FollowUpSubmitted(SUB, REQ, "/pdf-tools", List.of(), null, given);
        given.clear();
        assertEquals(List.of(READ, FAILED), prompt.skills());
        assertEquals(List.of(READ, FAILED), steer.skills());
        assertEquals(List.of(READ, FAILED), followUp.skills());
    }

    @Test
    void skillBlocksComeAfterTheTextAndTheAttachments() {
        assertEquals(Message.user("hi"), Message.user("hi", List.of(), List.of()));
        assertEquals(Message.user("hi", List.of(FILE)), Message.user("hi", List.of(FILE), List.of()));
        assertEquals(new Message(Role.USER, List.of(new TextBlock("hi"), BLOCK), false), Message.user("hi", List.of(), List.of(BLOCK)));
        assertEquals(new Message(Role.USER, List.of(new TextBlock("hi"), new AttachmentBlock(FILE), BLOCK), false),
                Message.user("hi", List.of(FILE), List.of(BLOCK)));
    }

    /** 改队列的请求只知道文字和附件：带技能内容的那条照样找得到，文字不同的找不到。 */
    @Test
    void aQueuedMessageIsLocatedByItsTextAndAttachmentsOnly() {
        List<Message> queue = List.of(Message.user("a"), Message.user("/pdf-tools b", List.of(FILE), List.of(BLOCK)), Message.user("c"));
        assertEquals(1, Queue.locate(queue, 1, Message.user("/pdf-tools b", List.of(FILE))));
        assertEquals(1, Queue.locate(queue, 2, Message.user("/pdf-tools b", List.of(FILE))), "found when it moved towards the head");
        assertEquals(-1, Queue.locate(queue, 2, Message.user("/pdf-tools b")), "the attachments still have to match");
        assertEquals(-1, Queue.locate(queue, 0, Message.user("/pdf-tools b", List.of(FILE))), "only looks towards the head");
        assertEquals(2, Queue.locate(queue, 2, Message.user("c")));
    }
}
