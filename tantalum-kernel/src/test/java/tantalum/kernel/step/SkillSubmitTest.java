package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.compaction.Compactor;
import tantalum.kernel.compaction.TokenEstimate;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.FileRef;
import tantalum.value.command.CallModel;
import tantalum.value.decision.Decision;
import tantalum.value.decision.QueueEdited;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.QueueChanged;
import tantalum.value.event.QueueChanged.Action;
import tantalum.value.event.SkillUse;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.SkillBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.state.OwnerRef;
import tantalum.value.event.ContextAssembledFixture;

/**
 * 用户点名的技能在内核里（skills-wiki 任务 16；需求 5.2、5.3、5.6、5.8、7.6）：读到的内容接在文字和附件之后进用户消息，
 * 没读到的不进；排队、插话同样带着；带技能内容的排队消息不能改文字，能移除、能转成插话。
 */
class SkillSubmitTest {

    private static final FileRef FILE = new FileRef("sha256:" + "b".repeat(64), "notes.txt", "text/plain", 5, null);
    private static final SkillUse PDF = new SkillUse("pdf-tools", "<skill_content name=\"pdf-tools\">…</skill_content>", null);
    private static final SkillUse DEPLOY = new SkillUse("deploy", "<skill_content name=\"deploy\">…</skill_content>", null);
    private static final SkillUse LATE = new SkillUse("slow", null, "timeout");
    private static final SkillBlock PDF_BLOCK = new SkillBlock("pdf-tools", PDF.content());
    private static final SkillBlock DEPLOY_BLOCK = new SkillBlock("deploy", DEPLOY.content());

    private static Message withPdf(String text) {
        return new Message(Role.USER, List.of(new TextBlock(text), PDF_BLOCK), false);
    }

    private static void prompt(LoopFixture loop, String text, List<AttachmentRef> attachments, List<SkillUse> skills) {
        loop.feed(null, new UserPrompt(new SubmissionId("p"), new RequestId("p"), new OwnerRef.OfSession(LoopFixture.SESSION), text,
                attachments, null, null, skills));
    }

    private static FollowUpSubmitted followUp(String text, List<SkillUse> skills) {
        return new FollowUpSubmitted(new SubmissionId("f" + text), new RequestId("f" + text), text, List.of(), null, skills);
    }

    private static QueueChanged change(Action action, int index, String expected, String text) {
        return new QueueChanged(new SubmissionId("q" + action), new RequestId("q" + action), action, index, expected, List.of(), text);
    }

    private static CallModel toModel(LoopFixture loop) {
        loop.feed(loop.runId(), ContextAssembledFixture.of(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        return assertInstanceOf(CallModel.class, loop.onlyCommand());
    }

    @Test
    void namedSkillsFollowTheTextAndAttachmentsInTheOrderTheyWereNamed() {
        LoopFixture loop = new LoopFixture();
        prompt(loop, "/pdf-tools then /deploy this", List.of(FILE), List.of(PDF, LATE, DEPLOY));
        Message expected = new Message(Role.USER, List.of(new TextBlock("/pdf-tools then /deploy this"), new AttachmentBlock(FILE),
                PDF_BLOCK, DEPLOY_BLOCK), false);
        assertEquals(List.of(expected), loop.session().messages(), "the user's text is untouched; a skill that was not read adds nothing");
        assertEquals(List.of(expected), toModel(loop).prompt().messages());
    }

    /** 一个都没读到：消息和没点名时一模一样。 */
    @Test
    void whenNothingWasReadTheMessageIsPlain() {
        LoopFixture loop = new LoopFixture();
        prompt(loop, "/slow go", List.of(), List.of(LATE));
        assertEquals(List.of(Message.user("/slow go")), loop.session().messages());
    }

    /** 排队的提问带着排队那一刻读到的内容，轮到它时原样进历史（需求 5.6）。 */
    @Test
    void aQueuedMessageKeepsWhatWasReadWhenItWasQueued() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        var pending = loop.onlyCommandId();
        loop.feed(null, followUp("/pdf-tools later", List.of(PDF)));
        assertEquals(List.of(withPdf("/pdf-tools later")), loop.session().followUps());
        loop.feed(loop.runId(), ContextAssembledFixture.of(pending, "s", List.of(), null, List.of()));
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("ok"), StopReason.END_TURN, "m", null, null, null, null));
        assertEquals(withPdf("/pdf-tools later"), loop.session().messages().getLast());
        assertEquals(withPdf("/pdf-tools later"), toModel(loop).prompt().messages().getLast());
    }

    @Test
    void aSteerCarriesItsSkills() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        toModel(loop);
        loop.feed(null, new SteerSubmitted(new SubmissionId("s"), new RequestId("s"), "/pdf-tools now", List.of(), List.of(PDF)));
        assertEquals(List.of(withPdf("/pdf-tools now")), loop.run().pendingSteers());
        loop.feed(loop.runId(), new Cancel(new RequestId("c")));
        assertEquals(withPdf("/pdf-tools now"), loop.session().messages().getLast());
    }

    /** 改队列的请求只带文字和附件：照样找得到带技能内容的那条；改文字不行，移除可以（需求 5.6）。 */
    @Test
    void aQueuedMessageWithASkillCannotBeEditedButCanBeRemoved() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        loop.feed(null, followUp("a", List.of()));
        loop.feed(null, followUp("/pdf-tools b", List.of(PDF)));

        Decision refused = loop.feed(null, change(Action.EDIT, 1, "/pdf-tools b", "b without the skill"));
        assertEquals(List.of(Message.user("a"), withPdf("/pdf-tools b")), loop.session().followUps(), "nothing changed");
        assertEquals(List.of(new QueueEdited(loop.run().ref(), Action.EDIT, -1)), facts(refused));

        Decision plain = loop.feed(null, change(Action.EDIT, 0, "a", "a2"));
        assertEquals(List.of(new QueueEdited(loop.run().ref(), Action.EDIT, 0)), facts(plain), "a message without a skill still edits");

        Decision removed = loop.feed(null, change(Action.REMOVE, 1, "/pdf-tools b", null));
        assertEquals(List.of(Message.user("a2")), loop.session().followUps());
        assertEquals(List.of(new QueueEdited(loop.run().ref(), Action.REMOVE, 1)), facts(removed));
    }

    /** 转成插话：整条消息原样移过去，技能内容跟着走。 */
    @Test
    void turningAQueuedMessageIntoASteerKeepsItsSkill() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        loop.feed(null, followUp("/pdf-tools b", List.of(PDF)));
        Decision moved = loop.feed(null, change(Action.STEER, 0, "/pdf-tools b", null));
        assertEquals(List.of(), loop.session().followUps());
        assertEquals(List.of(withPdf("/pdf-tools b")), loop.run().pendingSteers());
        assertEquals(List.of(new QueueEdited(loop.run().ref(), Action.STEER, 0)), facts(moved));
    }

    /** 技能内容按文字估算；带技能的提问仍算用户亲笔的消息，压缩时作为“最近一次提问”整条留下。 */
    @Test
    void theSkillTextCountsTowardsTheEstimateAndTheMessageIsStillTheUsersRequest() {
        Message plain = Message.user("/pdf-tools go");
        Message named = withPdf("/pdf-tools go");
        assertEquals(TokenEstimate.LEGACY.ofMessage(plain) + TokenEstimate.LEGACY.ofText(PDF.content()), TokenEstimate.LEGACY.ofMessage(named));
        assertTrue(Compactor.isUserText(named));
    }

    private static List<QueueEdited> facts(Decision decision) {
        return decision.facts().stream().filter(QueueEdited.class::isInstance).map(QueueEdited.class::cast).toList();
    }
}
