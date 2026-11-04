package tantalum.examples.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFiles;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.journal.TailSequence;
import tantalum.shell.loop.InputRing;
import tantalum.shell.loop.LaneDispatcher;
import tantalum.shell.loop.LoopFixtures;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.Message;
import tantalum.value.state.OwnerRef;

/**
 * 写路径预算（非功能需求）：走真实的写入分发、用户通道和每会话文件（每批 fsync），
 * 100 个会话分属 10 个用户，每会话每秒 1 个事件，量从进入输入环到命令放行的耗时：事件落盘、交给 cleanroom，
 * 结果行再由同一个通道落盘后才放行（record-results 需求 2.4）。这里的 cleanroom 不调内核，交回一份同等大小的结果。
 * {@code mvn -P bench -pl tantalum-examples -am -DskipTests test-compile -Dbench.main=tantalum.examples.bench.LaneBench}
 */
public final class LaneBench {

    private static final int SESSIONS = 100;
    private static final int USERS = 10;
    private static final int SECONDS = 30;
    private static final String TEXT = "payload ".repeat(256);

    private LaneBench() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createDirectories(Path.of(args[0])).resolve("lane-" + System.nanoTime());
        Files.createDirectories(dir);
        Map<String, Long> sent = new ConcurrentHashMap<>();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        /** 第一秒每个会话都是新文件（建文件并 fsync 目录），单独统计。 */
        List<Long> firstSecond = Collections.synchronizedList(new ArrayList<>());
        SessionPaths paths = new SessionPaths(dir);
        try (SessionFiles files = new SessionFiles(paths, EventCodec.withBuiltins(), 256, (principal, session, seq, offset) -> { })) {
            LaneDispatcher[] holder = new LaneDispatcher[1];
            holder[0] = LoopFixtures.dispatcher(1024, new TailSequence(paths, 1), files::appendLines, envelope -> {
                UserPrompt prompt = (UserPrompt) envelope.event();
                String request = prompt.request().value();
                // 结果行里有这次追加的用户消息，和真实的第一步一样大
                StepRecord record = new StepRecord(envelope.seq(), new SessionChange(envelope.session(), envelope.principal(), false,
                        List.of(Message.user(prompt.text())), null, null, null, null, null, null, null), List.of(), List.of(), null, null,
                        List.of(), List.of());
                holder[0].release(envelope, record, () -> {
                    long now = System.nanoTime();
                    Long start = sent.remove(request);
                    latencies.add(TimeUnit.NANOSECONDS.toMicros(now - start));
                    if (request.startsWith("0-")) {
                        firstSecond.add(TimeUnit.NANOSECONDS.toMicros(now - start));
                    }
                }, holder[0]::released);
                return true;
            });
            InputRing input = LoopFixtures.inputRing(1024, holder[0]);
            input.start();
            if (Boolean.getBoolean("lane.warmup")) {
                // 对照实验：先让另外 100 个会话各写一次（JIT、类加载、用户目录都预热），再开始计时。
                for (int session = 0; session < SESSIONS; session++) {
                    String request = "warm-" + session;
                    SessionId id = new SessionId("warm" + session);
                    sent.put(request, System.nanoTime());
                    input.tryPublish(new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.now(), new Principal("u" + session % USERS), id, null,
                            new UserPrompt(new SubmissionId(request), new RequestId(request), new OwnerRef.OfSession(id), TEXT)));
                    Thread.sleep(10);
                }
                Thread.sleep(2_000);
                latencies.clear();
            }
            long begin = System.nanoTime();
            for (int second = 0; second < SECONDS; second++) {
                for (int session = 0; session < SESSIONS; session++) {
                    // 每个会话在这一秒里的固定时刻发，100 个会话均匀错开。
                    long due = begin + TimeUnit.SECONDS.toNanos(second) + TimeUnit.MILLISECONDS.toNanos(session * 10L);
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        TimeUnit.NANOSECONDS.sleep(wait);
                    }
                    String request = second + "-" + session;
                    SessionId id = new SessionId("s" + session);
                    sent.put(request, System.nanoTime());
                    if (!input.tryPublish(new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.now(), new Principal("u" + session % USERS), id, null,
                            new UserPrompt(new SubmissionId(request), new RequestId(request), new OwnerRef.OfSession(id), TEXT)))) {
                        throw new IllegalStateException("input ring full");
                    }
                }
            }
            for (int i = 0; i < 500 && latencies.size() < SECONDS * SESSIONS; i++) {
                Thread.sleep(10);
            }
            input.shutdown(Duration.ZERO);
        }
        List<Long> sorted = latencies.stream().sorted().toList();
        if (sorted.size() != SECONDS * SESSIONS) {
            throw new IllegalStateException("only " + sorted.size() + " of " + SECONDS * SESSIONS + " events arrived");
        }
        long p50 = sorted.get(sorted.size() / 2);
        long p99 = sorted.get((int) Math.ceil(sorted.size() * 0.99) - 1);
        System.out.printf("machine: %s %s, %d cores, java %s%n", System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(), System.getProperty("java.version"));
        System.out.printf("%d events, %d sessions, %d users: P50 %.1f ms, P99 %.1f ms, max %.1f ms%n", sorted.size(), SESSIONS, USERS,
                p50 / 1000.0, p99 / 1000.0, sorted.getLast() / 1000.0);
        List<Long> first = firstSecond.stream().sorted().toList();
        System.out.printf("first second (new files): P50 %.1f ms, max %.1f ms%n", first.get(first.size() / 2) / 1000.0, first.getLast() / 1000.0);
        List<Long> later = new ArrayList<>(sorted);
        first.forEach(later::remove);
        System.out.printf("after the first second: P50 %.1f ms, P99 %.1f ms, max %.1f ms%n", later.get(later.size() / 2) / 1000.0,
                later.get((int) Math.ceil(later.size() * 0.99) - 1) / 1000.0, later.getLast() / 1000.0);
        Budgets budgets = Budgets.fromSystemProperty();
        budgets.check("write_p99_ms", Math.round(p99 / 1000.0), Budgets.WRITE_P99_MS);
        budgets.exitIfOver();
    }
}
