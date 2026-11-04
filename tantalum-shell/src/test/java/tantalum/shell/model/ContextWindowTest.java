package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tantalum.shell.model.ContextWindow.Reading;
import tantalum.shell.model.ContextWindow.Source;

/** compaction-threshold T2、需求 1：启动、后台刷新、失败保留、太小不要、超长后再读。 */
class ContextWindowTest {

    private static final Duration NEVER = Duration.ofHours(1);

    /** 按设定回答的窗口来源；{@code answer} 为 null 时抛 IOException。 */
    private static final class Scripted implements WindowSource {
        final AtomicReference<Long> answer = new AtomicReference<>();
        final AtomicInteger reads = new AtomicInteger();
        volatile CountDownLatch gate = new CountDownLatch(0);

        @Override
        public OptionalLong read() throws IOException, InterruptedException {
            reads.incrementAndGet();
            gate.await();
            Long value = answer.get();
            if (value == null) {
                throw new IOException("down");
            }
            return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
        }
    }

    private static void eventually(Runnable check) throws InterruptedException {
        AssertionError last = null;
        for (int i = 0; i < 200; i++) {
            try {
                check.run();
                return;
            } catch (AssertionError notYet) {
                last = notYet;
                Thread.sleep(10);
            }
        }
        throw last;
    }

    @Test
    void theModelsAnswerReplacesTheConfiguredValue() {
        Scripted source = new Scripted();
        source.answer.set(1_000_000L);
        try (ContextWindow window = new ContextWindow(source, 200_000, 64_000, NEVER).start(Duration.ofSeconds(5))) {
            assertEquals(new Reading(1_000_000, Source.MODEL), window.current());
        }
    }

    @Test
    void failuresAndMissingAnswersKeepTheConfiguredValue() {
        Scripted down = new Scripted();
        try (ContextWindow window = new ContextWindow(down, 200_000, 64_000, NEVER).start(Duration.ofSeconds(5))) {
            assertEquals(new Reading(200_000, Source.CONFIG), window.current());
        }
        Scripted none = new Scripted();
        none.answer.set(-1L);
        try (ContextWindow window = new ContextWindow(none, 200_000, 64_000, NEVER).start(Duration.ofSeconds(5))) {
            assertEquals(new Reading(200_000, Source.CONFIG), window.current());
        }
    }

    @Test
    void aWindowNotAboveTheReserveIsRefused() {
        Scripted source = new Scripted();
        source.answer.set(64_000L);
        try (ContextWindow window = new ContextWindow(source, 200_000, 64_000, NEVER).start(Duration.ofSeconds(5))) {
            assertEquals(new Reading(200_000, Source.CONFIG), window.current());
        }
    }

    @Test
    void aSlowModelDoesNotHoldUpStartAndIsUsedOnceItAnswers() throws InterruptedException {
        Scripted source = new Scripted();
        source.answer.set(131_072L);
        source.gate = new CountDownLatch(1);
        try (ContextWindow window = new ContextWindow(source, 32_000, 16_384, NEVER).start(Duration.ofMillis(50))) {
            assertEquals(new Reading(32_000, Source.CONFIG), window.current());
            source.gate.countDown();
            eventually(() -> assertEquals(new Reading(131_072, Source.MODEL), window.current()));
        }
    }

    @Test
    void theBackgroundRefreshPicksUpAChangeAndKeepsTheLastValueOnFailure() throws InterruptedException {
        Scripted source = new Scripted();
        source.answer.set(131_072L);
        try (ContextWindow window = new ContextWindow(source, 32_000, 16_384, Duration.ofMillis(20)).start(Duration.ofSeconds(5))) {
            source.answer.set(65_536L);
            eventually(() -> assertEquals(new Reading(65_536, Source.MODEL), window.current()));
            source.answer.set(null);
            int before = source.reads.get();
            eventually(() -> assertTrue(source.reads.get() > before + 2));
            assertEquals(new Reading(65_536, Source.MODEL), window.current(), "a failed read keeps the last good value");
        }
    }

    @Test
    void refreshSoonReadsAgainAndMergesRequestsMadeMeanwhile() throws InterruptedException {
        Scripted source = new Scripted();
        source.answer.set(131_072L);
        try (ContextWindow window = new ContextWindow(source, 32_000, 16_384, NEVER).start(Duration.ofSeconds(5))) {
            assertEquals(1, source.reads.get());
            source.answer.set(65_536L);
            source.gate = new CountDownLatch(1);
            window.refreshSoon();
            eventually(() -> assertEquals(2, source.reads.get()));
            window.refreshSoon();
            window.refreshSoon();
            window.refreshSoon();
            source.gate.countDown();
            eventually(() -> assertEquals(3, source.reads.get()));
            Thread.sleep(50);
            assertEquals(3, source.reads.get(), "three requests during a read become one read after it");
            assertEquals(new Reading(65_536, Source.MODEL), window.current());
        }
    }

    @Test
    void noSourceMeansTheConfiguredValueAndNoThreadWork() {
        try (ContextWindow window = new ContextWindow(WindowSource.NONE, 0, 16_384, NEVER).start(Duration.ofSeconds(5))) {
            window.refreshSoon();
            assertEquals(new Reading(0, Source.CONFIG), window.current());
        }
    }
}
