package tantalum.examples.bench;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import tantalum.shell.journal.EventCodec;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.OwnerRef;

/**
 * 写路径对比。四种写法，内容和大小相同，完成后读回核对条数：
 * SQLite（一个写线程，队列里有多少提交一次，同 {@code JournalWriter}），分 {@code PRAGMA fullfsync} 关和开；
 * 每会话一个 JSONL（各自落盘，原型），分 {@code FileChannel.force} 和 {@code FileDescriptor.sync}。
 * 两种负载：满负荷（发一个等落盘再发下一个）和固定速率（每会话每秒一个事件，接近真实运行）。
 * {@code mvn -P bench -pl tantalum-examples -am -DskipTests test-compile -Dbench.main=tantalum.examples.bench.WriteBench -Dbench.sizes=1,10,100}
 */
public final class WriteBench {

    private static final int TOTAL_EVENTS = 3_000;
    private static final int MIN_PER_SESSION = 100;
    private static final int PACED_EVENTS = 30;
    private static final String TEXT = "payload ".repeat(256);

    private WriteBench() {}

    private interface Sink {
        void write(int session, long seq, EventEnvelope envelope) throws Exception;
    }

    private interface Variant {
        Result run(Path dir, int sessions, int perSession, long paceMillis) throws Exception;
    }

    private record Result(double perSecond, double p50, double p99) {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).resolve("write");
        int samples = Integer.parseInt(args[2]);
        Map<String, Variant> variants = new LinkedHashMap<>();
        variants.put("sqlite-fsync    ", (dir, sessions, each, pace) -> sqlite(dir, false, sessions, each, pace));
        variants.put("sqlite-fullfsync", (dir, sessions, each, pace) -> sqlite(dir, true, sessions, each, pace));
        variants.put("jsonl-force     ", (dir, sessions, each, pace) -> jsonl(dir, true, sessions, each, pace));
        variants.put("jsonl-fdsync    ", (dir, sessions, each, pace) -> jsonl(dir, false, sessions, each, pace));
        for (String size : args[1].split(",")) {
            int sessions = Integer.parseInt(size.trim());
            int perSession = Math.max(MIN_PER_SESSION, TOTAL_EVENTS / sessions);
            System.out.printf("%n== saturated: %d sessions, %d events each%n", sessions, perSession);
            runAll(variants, root, "full-" + sessions, samples, sessions, perSession, 0);
        }
        System.out.printf("%n== paced: 100 sessions, 1 event/s each, %d events each%n", PACED_EVENTS);
        runAll(variants, root, "paced", samples, 100, PACED_EVENTS, 1000);
    }

    private static void runAll(Map<String, Variant> variants, Path root, String tag, int samples, int sessions, int perSession, long pace)
            throws Exception {
        for (var variant : variants.entrySet()) {
            for (int i = 0; i < samples; i++) {
                Path dir = root.resolve(tag + "-" + variant.getKey().trim() + "-" + i);
                Result result = variant.getValue().run(dir, sessions, perSession, pace);
                System.out.printf("%s %8.0f events/s   p50 %7.2f ms   p99 %7.2f ms%n", variant.getKey(), result.perSecond, result.p50, result.p99);
            }
        }
    }

    private static Result sqlite(Path dir, boolean fullFsync, int sessions, int perSession, long pace) throws Exception {
        record Item(String json, long seq, CompletableFuture<Void> done) {}
        Files.createDirectories(dir);
        EventCodec codec = EventCodec.withBuiltins();
        LinkedBlockingQueue<Item> queue = new LinkedBlockingQueue<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("journal.db"))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA fullfsync=" + (fullFsync ? "ON" : "OFF"));
                statement.execute("CREATE TABLE events (seq INTEGER PRIMARY KEY, payload TEXT NOT NULL)");
            }
            connection.setAutoCommit(false);
            PreparedStatement insert = connection.prepareStatement("INSERT INTO events VALUES (?, ?)");
            Thread writer = new Thread(() -> {
                List<Item> batch = new ArrayList<>();
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        batch.add(queue.take());
                        queue.drainTo(batch);
                        for (Item item : batch) {
                            insert.setLong(1, item.seq);
                            insert.setString(2, item.json);
                            insert.executeUpdate();
                        }
                        connection.commit();
                        batch.forEach(item -> item.done.complete(null));
                        batch.clear();
                    }
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            writer.start();
            Result result = drive(sessions, perSession, pace, (session, seq, envelope) -> {
                CompletableFuture<Void> done = new CompletableFuture<>();
                queue.add(new Item(codec.encode(envelope.event()), seq, done));
                done.get();
            });
            writer.interrupt();
            writer.join();
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT count(*) FROM events")) {
                rows.next();
                check(rows.getLong(1), (long) sessions * perSession);
            }
            return result;
        }
    }

    private static Result jsonl(Path dir, boolean force, int sessions, int perSession, long pace) throws Exception {
        Files.createDirectories(dir);
        EventCodec codec = EventCodec.withBuiltins();
        FileOutputStream[] streams = new FileOutputStream[sessions];
        Path[] files = new Path[sessions];
        for (int i = 0; i < sessions; i++) {
            files[i] = dir.resolve("s" + i + ".jsonl");
            streams[i] = new FileOutputStream(files[i].toFile(), true);
        }
        Result result = drive(sessions, perSession, pace, (session, seq, envelope) -> {
            String line = "{\"seq\":" + seq + ",\"at\":\"" + envelope.at() + "\",\"type\":\"" + envelope.event().type()
                    + "\",\"version\":1,\"payload\":" + codec.encode(envelope.event()) + "}\n";
            streams[session].write(line.getBytes(StandardCharsets.UTF_8));
            if (force) {
                streams[session].getChannel().force(true);
            } else {
                streams[session].getFD().sync();
            }
        });
        long read = 0;
        for (int i = 0; i < sessions; i++) {
            streams[i].close();
            try (var lines = Files.lines(files[i])) {
                read += lines.count();
            }
        }
        check(read, (long) sessions * perSession);
        return result;
    }

    private static Result drive(int sessions, int perSession, long pace, Sink sink) throws Exception {
        long[][] latencies = new long[sessions][perSession];
        AtomicLong seq = new AtomicLong();
        long start = System.nanoTime();
        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> running = new ArrayList<>();
            for (int s = 0; s < sessions; s++) {
                int session = s;
                running.add(CompletableFuture.runAsync(() -> {
                    SessionId id = new SessionId("s" + session);
                    Principal principal = new Principal("u" + session);
                    for (int i = 0; i < perSession; i++) {
                        if (pace > 0) {
                            pause(session * pace / sessions, i == 0, pace);
                        }
                        EventEnvelope envelope = new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH, principal, id, null,
                                new UserPrompt(new SubmissionId("sub"), new RequestId("req"), new OwnerRef.OfSession(id), TEXT));
                        long begin = System.nanoTime();
                        try {
                            sink.write(session, seq.getAndIncrement(), envelope);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                        latencies[session][i] = System.nanoTime() - begin;
                    }
                }, clients));
            }
            CompletableFuture.allOf(running.toArray(CompletableFuture[]::new)).get();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        long[] all = Arrays.stream(latencies).flatMapToLong(Arrays::stream).sorted().toArray();
        return new Result(all.length / seconds, all[all.length / 2] / 1e6, all[(int) (all.length * 0.99)] / 1e6);
    }

    /** 第一个事件前错开，之后每隔 {@code pace} 毫秒发一个，避免所有会话同时发。 */
    private static void pause(long offset, boolean first, long pace) {
        try {
            Thread.sleep(first ? offset : pace);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(stop);
        }
    }

    private static void check(long read, long expected) throws IOException {
        if (read != expected) {
            throw new IOException("read back " + read + " events but wrote " + expected);
        }
    }
}
