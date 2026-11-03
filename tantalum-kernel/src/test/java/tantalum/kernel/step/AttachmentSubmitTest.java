package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.command.CallModel;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.state.OwnerRef;

/** 任务 9：提问、排队、插话带的附件都进用户消息，顺序是先文字、再按上传顺序的附件（设计第 1 节）。 */
class AttachmentSubmitTest {

    private static final ImageRef IMAGE = new ImageRef("sha256:" + "a".repeat(64), "cat.png", "image/png", 10, 4, 3);
    private static final FileRef FILE = new FileRef("sha256:" + "b".repeat(64), "notes.txt", "text/plain", 5, null);
    private static final List<AttachmentRef> BOTH = List.of(IMAGE, FILE);

    private static Message expected(String text) {
        return new Message(Role.USER, List.of(new TextBlock(text), new AttachmentBlock(IMAGE), new AttachmentBlock(FILE)), false);
    }

    private static void prompt(LoopFixture loop, String text, List<AttachmentRef> attachments) {
        loop.feed(null, new UserPrompt(new SubmissionId("p" + text), new RequestId("p" + text),
                new OwnerRef.OfSession(LoopFixture.SESSION), text, attachments));
    }

    private static CallModel toModel(LoopFixture loop) {
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        return assertInstanceOf(CallModel.class, loop.onlyCommand());
    }

    private static void reply(LoopFixture loop) {
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("ok"), StopReason.END_TURN, "m", null, null, null, null));
    }

    @Test
    void promptAttachmentsFollowTheTextAndReachTheModel() {
        LoopFixture loop = new LoopFixture();
        prompt(loop, "what is this?", BOTH);
        assertEquals(List.of(expected("what is this?")), loop.session().messages());
        assertEquals(List.of(expected("what is this?")), toModel(loop).prompt().messages());
    }

    @Test
    void attachmentsWithoutTextKeepAnEmptyTextBlock() {
        LoopFixture loop = new LoopFixture();
        prompt(loop, "", BOTH);
        assertEquals(expected(""), loop.session().messages().getFirst());
    }

    /** 排队的提交在运行结束、开下一轮时带着附件进历史。 */
    @Test
    void queuedFollowUpStartsTheNextRunWithItsAttachments() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        var pending = loop.onlyCommandId();
        loop.feed(null, new FollowUpSubmitted(new SubmissionId("f"), new RequestId("f"), "and this", BOTH));
        assertEquals(List.of(expected("and this")), loop.session().followUps());
        loop.feed(loop.runId(), new ContextAssembled(pending, "s", List.of(), null, List.of()));
        reply(loop);
        assertEquals(List.of(), loop.session().followUps());
        assertEquals(expected("and this"), loop.session().messages().getLast());
        assertEquals(expected("and this"), toModel(loop).prompt().messages().getLast());
    }

    @Test
    void steerCarriesItsAttachments() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        toModel(loop);
        loop.feed(null, new SteerSubmitted(new SubmissionId("s"), new RequestId("s"), "look", BOTH));
        assertEquals(List.of(expected("look")), loop.run().pendingSteers());
        loop.feed(loop.runId(), new Cancel(new RequestId("c")));
        assertEquals(expected("look"), loop.session().messages().getLast());
    }

    @Test
    void steerAndFollowUpOnAnIdleSessionStartARunWithTheirAttachments() {
        LoopFixture idle = new LoopFixture();
        idle.feed(null, new SteerSubmitted(new SubmissionId("s"), new RequestId("s"), "x", BOTH));
        assertEquals(List.of(expected("x")), idle.session().messages());
        LoopFixture queued = new LoopFixture();
        queued.feed(null, new FollowUpSubmitted(new SubmissionId("f"), new RequestId("f"), "y", BOTH));
        assertEquals(List.of(expected("y")), queued.session().messages());
    }
}
