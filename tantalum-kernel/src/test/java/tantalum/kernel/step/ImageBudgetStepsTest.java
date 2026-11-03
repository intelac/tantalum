package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.ImageRef;
import tantalum.value.command.CallModel;
import tantalum.value.decision.ImagesOmitted;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;

/** 任务 14：组 prompt 时按图片预算省略；会话记住省略过的图片；回放得到同样的省略；摘要请求同样经过省略。 */
class ImageBudgetStepsTest {

    private static final long FIVE_MIB_BASE64 = 3L * 1024 * 1024 * 5 / 4;
    private static final CompactionSettings LARGE = new CompactionSettings(100_000, 200, 70, 60, 30, 500);

    private static ImageRef image(char tag) {
        return new ImageRef("sha256:" + String.valueOf(tag).repeat(64), tag + ".png", "image/png", FIVE_MIB_BASE64, 10, 10);
    }

    private static void prompt(LoopFixture loop, String text, List<AttachmentRef> images) {
        loop.feed(null, new UserPrompt(new SubmissionId(text), new RequestId(text), new OwnerRef.OfSession(LoopFixture.SESSION), text, images));
    }

    private static CallModel assemble(LoopFixture loop) {
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), LARGE, List.of()));
        return assertInstanceOf(CallModel.class, loop.onlyCommand());
    }

    private static void reply(LoopFixture loop) {
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("ok"), StopReason.END_TURN, "m", null, null, null, null));
    }

    /** 第一轮四张正好用完预算；第二轮再来一张，最早的那张省略，报一次事实，以后一直省略。 */
    private static LoopFixture twoRuns() {
        LoopFixture loop = new LoopFixture();
        prompt(loop, "q1", List.of(image('a'), image('b'), image('c'), image('d')));
        assemble(loop);
        reply(loop);
        prompt(loop, "q2", List.of(image('e')));
        return loop;
    }

    @Test
    void theOldestImageIsOmittedOnceTheBudgetIsExceeded() {
        LoopFixture loop = twoRuns();
        CallModel call = assemble(loop);
        assertEquals(List.of(image('a').id()), call.omittedImages());
        assertEquals(new TextBlock(AttachmentText.omitted(image('a'))), call.prompt().messages().getFirst().content().get(1));
        assertEquals(new AttachmentBlock(image('b')), call.prompt().messages().getFirst().content().get(2));
        assertEquals(List.of(image('a').id()), loop.session().omittedImages());
        assertEquals(List.of(new ImagesOmitted(loop.run().ref(), List.of(image('a').id()))),
                loop.last.facts().stream().filter(ImagesOmitted.class::isInstance).toList());
        assertEquals(new AttachmentBlock(image('a')), loop.session().messages().getFirst().content().get(1), "history keeps the image");
    }

    @Test
    void laterRequestsKeepOmittingWithoutReportingAgain() {
        LoopFixture loop = twoRuns();
        assemble(loop);
        reply(loop);
        loop.prompt("q3");
        CallModel call = assemble(loop);
        assertEquals(List.of(image('a').id()), call.omittedImages());
        assertEquals(List.of(), loop.last.facts().stream().filter(ImagesOmitted.class::isInstance).toList());
    }

    @Test
    void replayGivesTheSameOmissionAndTheSamePrompt() {
        LoopFixture live = twoRuns();
        CallModel liveCall = assemble(live);
        LoopFixture replayed = new LoopFixture();
        KernelState state = KernelState.empty();
        CallModel replayedCall = null;
        for (EventEnvelope envelope : live.journal) {
            var decision = replayed.kernel.step(state, envelope);
            state = decision.newState();
            for (var command : decision.commands()) {
                if (command instanceof CallModel call) {
                    replayedCall = call;
                }
            }
        }
        assertEquals(liveCall, replayedCall);
        assertEquals(live.state, state);
    }

    /** 一张单独就超出预算的图片：摘要请求里同样换成省略说明，不会把原图发出去。 */
    @Test
    void theSummaryRequestIsBudgetedToo() {
        ImageRef huge = new ImageRef("sha256:" + "f".repeat(64), "huge.png", "image/png", FIVE_MIB_BASE64 * 5, 10, 10);
        LoopFixture loop = new LoopFixture();
        prompt(loop, "q1", List.of(huge));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), CompactStepsTest.SETTINGS, List.of()));
        loop.feed(loop.runId(), CompactStepsTest.endTurn(loop, CompactStepsTest.block("a1"), 100));
        loop.prompt(CompactStepsTest.block("q2"));
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), CompactStepsTest.SETTINGS, List.of()));
        loop.feed(loop.runId(), CompactStepsTest.endTurn(loop, CompactStepsTest.block("a2"), 200));
        loop.feed(null, CompactStepsTest.compactRequest(null));
        assertEquals("AwaitingSummary", loop.phase());
        CallModel summary = assertInstanceOf(CallModel.class, loop.onlyCommand());
        assertEquals(List.of(huge.id()), summary.omittedImages());
        assertEquals(new TextBlock(AttachmentText.omitted(huge)), summary.prompt().messages().getFirst().content().get(1));
    }
}
