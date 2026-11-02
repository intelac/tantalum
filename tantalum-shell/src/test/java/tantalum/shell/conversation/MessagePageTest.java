package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

class MessagePageTest {

    private static final SessionId S = new SessionId("s");
    private static final Instant AT = Instant.EPOCH;

    private final List<TranscriptItem> items = new ArrayList<>();
    private int next;

    /** 一个运行：提问、状态、{@code replies} 条回复。 */
    private RunId run(int replies) {
        RunId run = RunId.of(S, next);
        items.add(new TranscriptItem.UserMessage((next++) + ".0", S, run, AT, "q", false));
        items.add(TranscriptItem.RunStatus.started(S, run, AT, "AwaitingModel"));
        for (int i = 0; i < replies; i++) {
            items.add(new TranscriptItem.Text((next++) + ".0", S, run, AT, "a", false, false));
        }
        return run;
    }

    private static int messages(List<TranscriptItem> page) {
        return (int) page.stream().filter(item -> item instanceof TranscriptItem.UserMessage || item instanceof TranscriptItem.Text).count();
    }

    private static Set<RunId> runs(List<TranscriptItem> page) {
        Set<RunId> runs = new HashSet<>();
        page.forEach(item -> {
            if (item.run() != null) {
                runs.add(item.run());
            }
        });
        return runs;
    }

    /** 每个运行的条目要么全在页里，要么全不在。 */
    private void assertRunsWhole(MessagePage page) {
        for (RunId run : runs(page.items())) {
            long inPage = page.items().stream().filter(item -> run.equals(item.run())).count();
            long total = items.stream().filter(item -> run.equals(item.run())).count();
            assertEquals(total, inPage, "run " + run.value() + " was split");
        }
    }

    @Test
    void aShortConversationIsOnePage() {
        run(1);
        run(1);
        MessagePage page = MessagePage.of(items, null);
        assertEquals(0, page.start());
        assertEquals(items, page.items());
        assertFalse(page.hasMore());
    }

    @Test
    void theLatestPageStopsAtFiftyMessagesAcrossTwoRunsAndKeepsRunsWhole() {
        for (int i = 0; i < 40; i++) {
            run(2);
        }
        MessagePage page = MessagePage.of(items, null);
        assertTrue(page.hasMore());
        assertTrue(messages(page.items()) >= MessagePage.MIN_MESSAGES);
        assertTrue(messages(page.items()) < MessagePage.MIN_MESSAGES + 3, "stops at the first run boundary after fifty");
        assertTrue(runs(page.items()).size() >= MessagePage.MIN_RUNS);
        assertEquals(items.subList(page.start(), items.size()), page.items());
        assertRunsWhole(page);
    }

    @Test
    void oneHugeRunAndAShortOneStillSpanTwoRuns() {
        run(100);
        run(1);
        MessagePage page = MessagePage.of(items, null);
        assertEquals(0, page.start(), "fifty messages are reached in the last run, but two runs are required");
        assertFalse(page.hasMore());
    }

    @Test
    void aRunLongerThanTheLimitIsSplitAndPagingStillReachesTheStart() {
        run(1);
        run(MessagePage.MAX_ITEMS + 100);
        MessagePage last = MessagePage.of(items, null);
        assertEquals(MessagePage.MAX_ITEMS, last.items().size());
        assertTrue(last.hasMore());
        MessagePage earlier = MessagePage.of(items, last.start());
        assertTrue(earlier.items().size() <= MessagePage.MAX_ITEMS);
        assertEquals(last.start(), earlier.start() + earlier.items().size());
    }

    @Test
    void neverMoreThanTheLimitEvenWhenTheMinimumIsNotReached() {
        run(1);
        run(300);
        run(300);
        MessagePage page = MessagePage.of(items, null);
        assertTrue(page.items().size() <= MessagePage.MAX_ITEMS);
        assertEquals(1, runs(page.items()).size(), "adding the second big run would exceed the limit");
        assertRunsWhole(page);
    }

    /** 排队的提问在上一个运行进行中加入，夹在它的条目中间；切页不能把那个运行劈开。 */
    @Test
    void aQueuedPromptInsideARunDoesNotSplitIt() {
        for (int i = 0; i < 30; i++) {
            run(1);
        }
        RunId running = RunId.of(S, next);
        items.add(new TranscriptItem.UserMessage((next++) + ".0", S, running, AT, "q", false));
        RunId queued = RunId.of(S, next);
        items.add(new TranscriptItem.UserMessage((next++) + ".0", S, queued, AT, "queued", false));
        for (int i = 0; i < 60; i++) {
            items.add(new TranscriptItem.Text((next++) + ".0", S, running, AT, "a", false, false));
        }
        items.add(new TranscriptItem.Text((next++) + ".0", S, queued, AT, "a", false, false));
        MessagePage page = MessagePage.of(items, null);
        assertRunsWhole(page);
        assertTrue(page.items().stream().anyMatch(item -> running.equals(item.run())));
    }

    @Test
    void pagesConcatenateToTheWholeHistory() {
        for (int i = 0; i < 200; i++) {
            run(i % 7);
        }
        run(MessagePage.MAX_ITEMS + 3);
        for (int i = 0; i < 30; i++) {
            run(2);
        }
        List<TranscriptItem> collected = new ArrayList<>();
        Integer before = null;
        int pages = 0;
        while (true) {
            MessagePage page = MessagePage.of(items, before);
            assertTrue(page.items().size() <= MessagePage.MAX_ITEMS);
            assertFalse(page.items().isEmpty());
            collected.addAll(0, page.items());
            pages++;
            if (!page.hasMore()) {
                break;
            }
            before = page.start();
        }
        assertEquals(items, collected);
        assertTrue(pages > 3);
    }

    @Test
    void aPositionPastTheEndMeansTheEnd() {
        run(1);
        assertEquals(MessagePage.of(items, null), MessagePage.of(items, 999));
        assertTrue(MessagePage.of(items, 0).items().isEmpty());
    }
}
