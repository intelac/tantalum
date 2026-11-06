package tantalum.shell.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.Question;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.Workspace;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.text.LocalText;

/** A4：工具审批在对话页上问用户用的问题板。问题按用户和会话分开，回答只认自己的、还在的问题和它的选项。 */
class OpenQuestionsTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final SessionId S1 = new SessionId("s1");
    private static final Question APPROVE = new Question("approval-c1", LocalText.of("允许调用 bash 吗？"), LocalText.of("{\"command\":\"ls\"}"),
            List.of(new Question.Choice("once", LocalText.of("允许这一次")), new Question.Choice("session", LocalText.of("这个会话里一直允许")),
                    new Question.Choice("deny", LocalText.of("拒绝"))));

    @TempDir
    Path dir;

    private final List<String> changes = new CopyOnWriteArrayList<>();
    private final MovingClock clock = new MovingClock();
    private final OpenQuestions questions = new OpenQuestions(clock, (user, session) -> changes.add(user.value() + "/" + session.value()));

    private ToolContext context(Principal user) throws Exception {
        return new ToolContext(user, S1, new Workspace(dir), questions);
    }

    private CompletableFuture<Optional<String>> asking(Duration wait) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return questions.ask(context(ALICE), APPROVE, wait);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private void awaitOpen(int count) throws InterruptedException {
        for (int i = 0; i < 500 && questions.open(ALICE, S1).size() != count; i++) {
            Thread.sleep(5);
        }
        assertEquals(count, questions.open(ALICE, S1).size());
    }

    @Test
    void anAnswerReachesTheWaitingWork() throws Exception {
        CompletableFuture<Optional<String>> asked = asking(Duration.ofSeconds(10));
        awaitOpen(1);
        assertEquals(List.of(APPROVE), questions.open(ALICE, S1));
        assertEquals(List.of(), questions.open(BOB, S1), "only the asked user sees it");
        assertEquals(OpenQuestions.Answer.GONE, questions.answer(BOB, S1, APPROVE.id(), "once"), "nor can anyone else answer it");
        assertEquals(OpenQuestions.Answer.NOT_A_CHOICE, questions.answer(ALICE, S1, APPROVE.id(), "always"));

        assertEquals(OpenQuestions.Answer.ACCEPTED, questions.answer(ALICE, S1, APPROVE.id(), "session"));
        assertEquals(Optional.of("session"), asked.get(5, TimeUnit.SECONDS));
        assertEquals(List.of(), questions.open(ALICE, S1));
        assertEquals(OpenQuestions.Answer.GONE, questions.answer(ALICE, S1, APPROVE.id(), "once"), "answered once only");
        assertEquals(List.of("alice/s1", "alice/s1"), changes, "shown, then withdrawn");
    }

    /** 等不到回答时问题留着；两次询问之间来的回答，下一次询问马上拿到。 */
    @Test
    void anAnswerBetweenTwoAsksIsKept() throws Exception {
        assertEquals(Optional.empty(), questions.ask(context(ALICE), APPROVE, Duration.ofMillis(20)));
        assertEquals(List.of(APPROVE), questions.open(ALICE, S1));
        assertEquals(OpenQuestions.Answer.ACCEPTED, questions.answer(ALICE, S1, APPROVE.id(), "once"));
        assertEquals(List.of(), questions.open(ALICE, S1), "an answered question is no longer shown");
        assertEquals(Optional.of("once"), questions.ask(context(ALICE), APPROVE, Duration.ofSeconds(5)));
    }

    /** 运行被取消、工作超时：等着的线程被打断，问题撤下。 */
    @Test
    void anInterruptedAskWithdrawsTheQuestion() throws Exception {
        Thread asker = Thread.ofVirtual().start(() -> {
            try {
                questions.ask(context(ALICE), APPROVE, Duration.ofMinutes(1));
            } catch (InterruptedException expected) {
                changes.add("interrupted");
            } catch (Exception other) {
                changes.add("failed " + other);
            }
        });
        awaitOpen(1);
        asker.interrupt();
        asker.join(5000);
        assertEquals(List.of(), questions.open(ALICE, S1));
        assertTrue(changes.contains("interrupted"), changes.toString());
    }

    /** 插件不再问了（放弃了、运行在两次询问之间被取消）：宽限过后撤下，回答不到了。 */
    @Test
    void aQuestionNobodyAsksAgainIsDroppedAfterTheGrace() throws Exception {
        questions.ask(context(ALICE), APPROVE, Duration.ofMillis(10));
        clock.advance(OpenQuestions.GRACE.minusMillis(1));
        assertEquals(1, questions.open(ALICE, S1).size());
        clock.advance(Duration.ofMillis(2));
        assertEquals(List.of(), questions.open(ALICE, S1));
        assertEquals(OpenQuestions.Answer.GONE, questions.answer(ALICE, S1, APPROVE.id(), "once"));
    }

    @Test
    void aQuestionNeedsAChoice() {
        assertThrows(IllegalArgumentException.class, () -> new Question("q", LocalText.of("t"), LocalText.empty(), List.of()));
    }

    /** 测试里可以往前拨的时钟。 */
    private static final class MovingClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-06T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
