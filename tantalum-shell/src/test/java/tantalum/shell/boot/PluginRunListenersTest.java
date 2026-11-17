package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tantalum.shell.plugin.PromptAttacher;
import tantalum.shell.plugin.RunFinishedListener;
import tantalum.shell.trace.RunRow;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.AttachedBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.state.EndReason;

/** memory-plugin 需求 1.1、1.2：运行结束通知只给开着的插件、出错互不影响；提交时附加内容有时限、带以前附过的。 */
class PluginRunListenersTest {

    private static final Principal YI = new Principal("yi");
    private static final SessionId SESSION = new SessionId("s1");

    private static RunRow row(TraceRecord.RunHead.Trigger trigger) {
        Instant start = Instant.parse("2026-11-15T10:00:00Z");
        return new RunRow(YI, SESSION, RunId.of(SESSION, 3), 2, trigger, start, start.plusSeconds(5), EndReason.COMPLETED, "hi", null);
    }

    private static RunFinishedListener listener(String plugin, List<RunFinishedListener.FinishedRun> seen, boolean fails) {
        return new RunFinishedListener() {
            @Override
            public String plugin() {
                return plugin;
            }

            @Override
            public void runFinished(FinishedRun run) {
                seen.add(run);
                if (fails) {
                    throw new IllegalStateException("boom");
                }
            }
        };
    }

    @Test
    void onlyEnabledPluginsHearAndOneFailureDoesNotStopTheOthers() {
        List<RunFinishedListener.FinishedRun> broken = new CopyOnWriteArrayList<>();
        List<RunFinishedListener.FinishedRun> memory = new CopyOnWriteArrayList<>();
        List<RunFinishedListener.FinishedRun> off = new CopyOnWriteArrayList<>();
        try (PluginRunListeners listeners = PluginRunListenersFixture.of(
                List.of(listener("broken", broken, true), listener("memory", memory, false), listener("off", off, false)),
                (plugin, user) -> !plugin.equals("off"), (user, session, run) -> List.of(), session -> "标题")) {
            listeners.deliver(row(TraceRecord.RunHead.Trigger.PROMPT));
        }
        assertEquals(1, broken.size());
        assertEquals(0, off.size());
        RunFinishedListener.FinishedRun run = memory.getFirst();
        assertEquals("COMPLETED", run.endReason());
        assertEquals("标题", run.title());
        assertEquals(2, run.turn());
        assertTrue(!run.manual());
    }

    @Test
    void finishedRunsAreDeliveredInTheBackground() throws InterruptedException {
        CountDownLatch heard = new CountDownLatch(1);
        RunFinishedListener latch = new RunFinishedListener() {
            @Override
            public String plugin() {
                return "memory";
            }

            @Override
            public void runFinished(FinishedRun run) {
                heard.countDown();
            }
        };
        try (PluginRunListeners listeners = PluginRunListenersFixture.of(List.of(latch), (plugin, user) -> true,
                (user, session, run) -> List.of(), session -> "")) {
            listeners.offer(row(TraceRecord.RunHead.Trigger.MANUAL_COMPACTION));
            assertTrue(heard.await(5, TimeUnit.SECONDS));
        }
    }

    private static PromptAttacher attacher(String plugin, java.util.function.Function<PromptAttacher.Submission, java.util.Optional<String>> body) {
        return new PromptAttacher() {
            @Override
            public String plugin() {
                return plugin;
            }

            @Override
            public java.util.Optional<String> attach(Submission submission) {
                return body.apply(submission);
            }
        };
    }

    @Test
    void attachersRunWithinABudgetAndSeeWhatTheyAttachedBefore() {
        List<List<String>> before = new CopyOnWriteArrayList<>();
        PromptAttacher memory = attacher("memory", submission -> {
            before.add(submission.attachedBefore());
            return java.util.Optional.of("记得：用户在做 Tantalum");
        });
        PromptAttacher slow = attacher("slow", submission -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return java.util.Optional.of("too late");
        });
        PromptAttacher broken = attacher("broken", submission -> {
            throw new IllegalStateException("boom");
        });
        PromptAttacher off = attacher("off", submission -> java.util.Optional.of("never"));
        PromptAttacher empty = attacher("empty", submission -> java.util.Optional.of("  "));
        PromptAttachers attachers = new PromptAttachers(List.of(slow, memory, broken, off, empty), (plugin, user) -> !plugin.equals("off"),
                Duration.ofMillis(200));
        Message earlier = new Message(Role.USER, List.of(new TextBlock("昨天"), new AttachedBlock("memory", "以前附的"),
                new AttachedBlock("other", "别人的")), false);
        long started = System.nanoTime();
        List<AttachedBlock> attached = attachers.collect(YI, SESSION, "今天做什么", List.of(earlier));
        long ms = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertEquals(List.of(new AttachedBlock("memory", "记得：用户在做 Tantalum")), attached);
        assertEquals(List.of(List.of("以前附的")), before);
        assertTrue(ms < 1_000, "waited " + ms + " ms");
    }
}
