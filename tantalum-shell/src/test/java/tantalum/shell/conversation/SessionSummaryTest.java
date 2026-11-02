package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.shell.conversation.TranscriptFixture.SESSION;
import static tantalum.shell.conversation.TranscriptFixture.T0;
import static tantalum.shell.conversation.TranscriptFixture.text;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import tantalum.value.id.RunId;

class SessionSummaryTest {

    @Test
    void titleIsTheFirstLineOfTheFirstPromptTruncated() {
        assertEquals("hello", SessionSummary.titleOf("  hello\nsecond line"));
        String long80 = "x".repeat(80);
        assertEquals("x".repeat(40) + "…", SessionSummary.titleOf(long80));
        assertEquals("", SessionSummary.titleOf("   "));
    }

    @Test
    void summaryTakesTitleLatestTimeAndRunStatus() {
        TranscriptFixture fx = new TranscriptFixture();
        RunId run = fx.start("where is the interest calculated?\nmore");
        SessionSummary running = SessionSummary.of(SESSION, fx.state().runs().get(run), fx.items());
        assertEquals("where is the interest calculated?", running.title());
        assertEquals("AwaitingModel", running.runStatus());
        assertEquals(T0.plusSeconds(1), running.lastActivity());

        fx.tick(Duration.ofSeconds(30));
        fx.feed(run, fx.reply(text("done")));
        SessionSummary idle = SessionSummary.of(SESSION, null, fx.items());
        assertEquals(SessionSummary.IDLE, idle.runStatus());
        assertEquals(T0.plusSeconds(32), idle.lastActivity());
    }
}
