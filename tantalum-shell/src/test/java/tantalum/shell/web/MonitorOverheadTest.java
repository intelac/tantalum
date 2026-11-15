package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/**
 * live-monitor 需求 5.2：多个用户同时连续提问，比较没人看和每个用户都开着监视流时的完成时间。各跑两遍，
 * 断言宽松的上限（机器抖动），实测数字打到输出里，记进 spec 的实现说明。
 */
class MonitorOverheadTest {

    private static final int USERS = 8;
    private static final int PROMPTS = 15;

    @TempDir
    Path temp;

    private TestServer server;

    private final ModelClient model = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), model);
        for (int u = 0; u < USERS; u++) {
            server.get("user" + u, "/api/sessions");
        }
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** 每个用户在自己的会话里连续提 {@link #PROMPTS} 个问题，每个等运行结束；返回总毫秒数。 */
    private long round(String tag) throws Exception {
        long start = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        AtomicLong failures = new AtomicLong();
        for (int u = 0; u < USERS; u++) {
            String user = "user" + u;
            String session = tag + "-" + u;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    for (int p = 0; p < PROMPTS; p++) {
                        server.post(user, "/api/sessions/" + session + "/submissions",
                                "{\"requestId\":\"" + session + "-" + p + "\",\"mode\":\"prompt\",\"text\":\"hi " + p + "\"}");
                        String body = "";
                        int completed = 0;
                        for (int i = 0; i < 2000 && completed < p + 1; i++) {
                            body = server.get(user, "/api/sessions/" + session + "/runs").body();
                            completed = body.split("COMPLETED", -1).length - 1;
                            if (completed < p + 1) {
                                Thread.sleep(2);
                            }
                        }
                        if (completed < p + 1) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Exception failure) {
                    failures.incrementAndGet();
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertTrue(failures.get() == 0, failures.get() + " prompts did not finish");
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** 每个用户一条监视流，后台一直读（丢弃内容）。 */
    private List<CompletableFuture<?>> watchAll(AtomicLong received) throws Exception {
        List<CompletableFuture<?>> streams = new ArrayList<>();
        CountDownLatch open = new CountDownLatch(USERS);
        for (int u = 0; u < USERS; u++) {
            streams.add(server.http.sendAsync(server.request("user" + u, "/api/monitor/stream").GET().build(),
                    HttpResponse.BodyHandlers.ofLines()).thenAccept(response -> {
                        try (Stream<String> lines = response.body()) {
                            lines.forEach(line -> {
                                if (line.startsWith(":")) {
                                    open.countDown();
                                } else if (line.startsWith("data:")) {
                                    received.incrementAndGet();
                                }
                            });
                        }
                    }));
        }
        assertTrue(open.await(10, TimeUnit.SECONDS));
        return streams;
    }

    @Test
    void watchingDoesNotSlowTheEngineDownNoticeably() throws Exception {
        round("warm");
        // 先跑没人看的两遍，再开监视流跑两遍（流开着就关不掉，所以不交替；预热放在最前面）
        long quiet = round("quiet0") + round("quiet1");
        AtomicLong received = new AtomicLong();
        watchAll(received);
        long watched = round("watched0") + round("watched1");
        System.out.printf("monitor overhead: %d users × %d prompts, quiet %d ms, watched %d ms (%d monitor messages)%n",
                USERS, PROMPTS, quiet, watched, received.get());
        assertTrue(received.get() > USERS * PROMPTS * 5, "the watchers received " + received.get() + " messages");
        assertTrue(watched < quiet * 1.5 + 500, "watched " + watched + " ms vs quiet " + quiet + " ms");
    }
}
