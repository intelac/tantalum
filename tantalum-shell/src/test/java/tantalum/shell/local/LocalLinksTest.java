package tantalum.shell.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.tool.LocalResult;
import tantalum.local.wire.Bye;
import tantalum.local.wire.Call;
import tantalum.local.wire.Cancel;
import tantalum.local.wire.Context;
import tantalum.local.wire.ContextResult;
import tantalum.local.wire.Hello;
import tantalum.local.wire.Message;
import tantalum.local.wire.Result;
import tantalum.local.wire.Welcome;
import tantalum.local.wire.Wire;
import tantalum.shell.local.LocalLinks.Link;
import tantalum.shell.local.LocalLinks.Timing;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

/** local-workspace L3、L8、L10、L11：用假的连接测替换、到期、撤销、名额、超时、断开。真的 WebSocket 见 {@link LocalEndpointTest}。 */
class LocalLinksTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Instant START = Instant.parse("2026-11-06T02:00:00Z");
    private static final Instant HOUR_LATER = START.plus(Duration.ofHours(1));
    private static final Hello HELLO = new Hello(Wire.PROTOCOL, "test", new Hello.Device("mac", "macos", "26.2", "arm64"),
            List.of(new Hello.Root("/Users/alice/work")), true, new Hello.Shell(false, "--allow-shell was not given"), true, true);
    /** 心跳间隔足够长，不干扰其他用例。 */
    private static final Timing QUIET = new Timing(Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofHours(1), Duration.ofMillis(300));
    private static final Json ARGS = new Json.Obj(Map.of("path", new Json.Str("a.txt")));

    @TempDir
    Path data;

    private final LocalTokensTest.Ticking clock = new LocalTokensTest.Ticking(START);
    private LocalTokens tokens;
    private LocalLinks links;

    @BeforeEach
    void open() throws Exception {
        tokens = LocalTokens.open(data, clock);
        links = new LocalLinks(tokens, clock, QUIET);
    }

    @AfterEach
    void close() throws Exception {
        links.close();
        tokens.close();
    }

    private Link attach(FakePeer peer, Instant expiresAt) {
        return links.attach(ALICE, expiresAt, System.nanoTime(), HELLO, peer).orElseThrow();
    }

    @Test
    void attachingWelcomesTheComputerAndShowsTheStatus() throws Exception {
        FakePeer peer = new FakePeer();
        attach(peer, HOUR_LATER);
        assertEquals(new Welcome("alice", HOUR_LATER), peer.next());
        LocalLinks.Status status = links.status(ALICE).orElseThrow();
        assertEquals(HELLO, status.hello());
        assertEquals(START, status.connectedAt());
        assertEquals(HOUR_LATER, status.expiresAt());
        assertEquals(Optional.empty(), links.status(new Principal("bob")));
    }

    /** L10：第二条连接顶替第一条，旧的收到 {@code bye replaced} 和 4409，旧连接上还在等的调用按断开处理。 */
    @Test
    void aSecondLinkReplacesTheFirst() throws Exception {
        FakePeer first = new FakePeer();
        Link original = attach(first, HOUR_LATER);
        first.next();
        CompletableFuture<LocalResult> pending = callAsync("s-1", Duration.ofSeconds(10));
        assertInstanceOf(Call.class, first.next());

        FakePeer second = new FakePeer();
        Link replacing = attach(second, HOUR_LATER);
        assertEquals(new Bye(Bye.Reason.REPLACED), first.next());
        assertEquals(Wire.CLOSE_REPLACED, first.closed.get(2, TimeUnit.SECONDS));
        assertEquals(LocalResult.error(LocalLinks.DISCONNECTED), pending.get(2, TimeUnit.SECONDS));
        assertInstanceOf(Welcome.class, second.next());
        assertFalse(second.closed.isDone());

        // 旧连接后来报告关闭，不会把新连接也摘掉
        original.dropped();
        CompletableFuture<LocalResult> onSecond = callAsync("s-1", Duration.ofSeconds(10));
        Call call = (Call) second.next();
        replacing.receive(new Result(call.id(), "fine", false, List.of()));
        assertEquals(LocalResult.ok("fine"), onSecond.get(2, TimeUnit.SECONDS));
        assertTrue(links.status(ALICE).isPresent());
    }

    /** L3：到期那一刻发 {@code bye expired}，用 4403 关掉。 */
    @Test
    void theLinkEndsWhenTheTokenExpires() throws Exception {
        FakePeer peer = new FakePeer();
        attach(peer, START.plusMillis(400));
        peer.next();
        assertNull(peer.sent.poll(200, TimeUnit.MILLISECONDS), "not before the expiry");
        assertEquals(new Bye(Bye.Reason.EXPIRED), peer.next());
        assertEquals(Wire.CLOSE_EXPIRED, peer.closed.get(2, TimeUnit.SECONDS));
        assertEquals(Optional.empty(), links.status(ALICE));
        assertEquals(LocalResult.error(LocalLinks.NOT_CONNECTED), links.call(ALICE, "s-1", "read_file", ARGS, Duration.ofSeconds(1)));
    }

    /** L3：撤销让令牌失效、连接断开，在跑的调用按断开处理。 */
    @Test
    void revokingEndsTheLinkAndTheTokens() throws Exception {
        String token = tokens.issue(ALICE, Duration.ofHours(1)).token();
        FakePeer peer = new FakePeer();
        attach(peer, HOUR_LATER);
        peer.next();
        CompletableFuture<LocalResult> pending = callAsync("s-1", Duration.ofSeconds(10));
        peer.next();

        links.revoke(ALICE);
        assertEquals(new Bye(Bye.Reason.REVOKED), peer.next());
        assertEquals(Wire.CLOSE_EXPIRED, peer.closed.get(2, TimeUnit.SECONDS));
        assertEquals(LocalResult.error(LocalLinks.DISCONNECTED), pending.get(2, TimeUnit.SECONDS));
        assertEquals(LocalTokens.Status.REVOKED, tokens.verify(token).status());
    }

    /** 撤销前握手、撤销后才发 {@code hello} 的连接接不上；撤销后新握手的可以。 */
    @Test
    void aHandshakeFromBeforeTheRevokeCannotAttach() {
        long before = System.nanoTime();
        links.revoke(ALICE);
        assertEquals(Optional.empty(), links.attach(ALICE, HOUR_LATER, before, HELLO, new FakePeer()));
        assertTrue(links.attach(ALICE, HOUR_LATER, System.nanoTime(), HELLO, new FakePeer()).isPresent());
    }

    @Test
    void aCallCarriesTheUserAndSessionAndReturnsTheResult() throws Exception {
        FakePeer peer = new FakePeer();
        Link link = attach(peer, HOUR_LATER);
        peer.next();
        CompletableFuture<LocalResult> pending = callAsync("s-42", Duration.ofSeconds(10));
        Call call = (Call) peer.next();
        assertEquals("alice", call.user());
        assertEquals("s-42", call.session());
        assertEquals("read_file", call.tool());
        assertEquals(ARGS, call.arguments());
        assertTrue(call.timeoutMs() > 9_000 && call.timeoutMs() <= 10_000, String.valueOf(call.timeoutMs()));

        link.receive(new Result("someone-else", "wrong id", false, List.of()));
        assertFalse(pending.isDone(), "a result for another id does not complete this call");
        link.receive(new Result(call.id(), "path not found in the allowed directories: x", true, List.of()));
        assertEquals(LocalResult.error("path not found in the allowed directories: x"), pending.get(2, TimeUnit.SECONDS));
    }

    @Test
    void withoutALinkTheCallFailsAtOnce() throws Exception {
        assertEquals(LocalResult.error("The local workspace is not connected."),
                links.call(ALICE, "s-1", "read_file", ARGS, Duration.ofSeconds(10)));
    }

    /** L11：一条连接同时最多 4 个调用，第 5 个排队，前面有一个回来它才发出去。 */
    @Test
    void atMostFourCallsRunAtOnce() throws Exception {
        FakePeer peer = new FakePeer();
        Link link = attach(peer, HOUR_LATER);
        peer.next();
        List<CompletableFuture<LocalResult>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(callAsync("s-" + i, Duration.ofSeconds(10)));
        }
        List<Call> sent = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            sent.add((Call) peer.next());
        }
        assertNull(peer.sent.poll(300, TimeUnit.MILLISECONDS), "the fifth call waits");
        link.receive(new Result(sent.getFirst().id(), "one", false, List.of()));
        sent.add((Call) peer.next());
        for (Call call : sent.subList(1, 5)) {
            link.receive(new Result(call.id(), "done", false, List.of()));
        }
        for (CompletableFuture<LocalResult> call : calls) {
            assertFalse(call.get(2, TimeUnit.SECONDS).error());
        }
    }

    /** 排队计入超时：名额一直不空出来，到时返回错误，调用根本没发出去。 */
    @Test
    void waitingForASlotCountsAgainstTheTimeout() throws Exception {
        FakePeer peer = new FakePeer();
        attach(peer, HOUR_LATER);
        peer.next();
        for (int i = 0; i < 4; i++) {
            callAsync("s-" + i, Duration.ofSeconds(10));
            peer.next();
        }
        long started = System.nanoTime();
        LocalResult late = links.call(ALICE, "s-5", "read_file", ARGS, Duration.ofMillis(300));
        assertTrue(late.error());
        assertTrue(late.text().contains("waiting for the computer"), late.text());
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(300));
        assertNull(peer.sent.poll(100, TimeUnit.MILLISECONDS));
    }

    /** 超时：给电脑发 {@code cancel}，返回现有措辞“结果不确定”。 */
    @Test
    void aTimeoutCancelsOnTheComputer() throws Exception {
        FakePeer peer = new FakePeer();
        attach(peer, HOUR_LATER);
        peer.next();
        LocalResult result = links.call(ALICE, "s-1", "read_file", ARGS, Duration.ofMillis(200));
        Call call = (Call) peer.next();
        assertEquals(new Cancel(call.id()), peer.next());
        assertTrue(result.error());
        assertEquals("Tool timed out after PT0.2S. Its outcome is unknown.", result.text());
    }

    /** 被打断（用户取消运行、关机）：发 {@code cancel}，照样抛 {@link InterruptedException}，名额还回去。 */
    @Test
    void anInterruptCancelsOnTheComputer() throws Exception {
        FakePeer peer = new FakePeer();
        Link link = attach(peer, HOUR_LATER);
        peer.next();
        CompletableFuture<Throwable> thrown = new CompletableFuture<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                links.call(ALICE, "s-1", "read_file", ARGS, Duration.ofSeconds(10));
                thrown.complete(null);
            } catch (Throwable failure) {
                thrown.complete(failure);
            }
        });
        Call call = (Call) peer.next();
        caller.interrupt();
        assertInstanceOf(InterruptedException.class, thrown.get(2, TimeUnit.SECONDS));
        assertEquals(new Cancel(call.id()), peer.next());
        // 名额都在：再来 4 个都能立即发出去
        for (int i = 0; i < 4; i++) {
            callAsync("s-" + i, Duration.ofSeconds(10));
            assertInstanceOf(Call.class, peer.next());
        }
        link.dropped();
    }

    /** L8：连接断了，在跑的调用和排队的调用都结束，不重发。 */
    @Test
    void aDisconnectEndsEveryPendingCall() throws Exception {
        FakePeer peer = new FakePeer();
        Link link = attach(peer, HOUR_LATER);
        peer.next();
        List<CompletableFuture<LocalResult>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(callAsync("s-" + i, Duration.ofSeconds(10)));
        }
        for (int i = 0; i < 4; i++) {
            peer.next();
        }
        link.dropped();
        int disconnected = 0;
        for (CompletableFuture<LocalResult> call : calls) {
            LocalResult result = call.get(2, TimeUnit.SECONDS);
            assertTrue(result.error());
            if (result.text().equals("The computer disconnected. Whether this operation finished is unknown.")) {
                disconnected++;
            } else {
                assertEquals(LocalLinks.NOT_CONNECTED, result.text(), "queued calls were never sent");
            }
        }
        assertEquals(4, disconnected);
        assertNull(peer.sent.poll(100, TimeUnit.MILLISECONDS), "nothing is sent again");
        assertEquals(Optional.empty(), links.status(ALICE));
    }

    /** {@code AGENTS.md}：回了就用，没回最多等 {@link Timing#agents()}，没连接就没有。 */
    @Test
    void agentsAsksTheComputerAndWaitsBriefly() throws Exception {
        assertEquals(Optional.empty(), links.agents(ALICE));
        FakePeer peer = new FakePeer();
        Link link = attach(peer, HOUR_LATER);
        peer.next();
        CompletableFuture<Optional<String>> answered = CompletableFuture.supplyAsync(this::agents);
        Context asked = (Context) peer.next();
        link.receive(new ContextResult(asked.id(), "Use tabs."));
        assertEquals(Optional.of("Use tabs."), answered.get(2, TimeUnit.SECONDS));

        CompletableFuture<Optional<String>> none = CompletableFuture.supplyAsync(this::agents);
        link.receive(new ContextResult(((Context) peer.next()).id(), null));
        assertEquals(Optional.empty(), none.get(2, TimeUnit.SECONDS));

        long started = System.nanoTime();
        assertEquals(Optional.empty(), links.agents(ALICE), "silent computer");
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
    }

    /** 方向不对的消息（电脑发来 {@code call}）关掉连接。 */
    @Test
    void aMessageFromTheWrongSideClosesTheLink() throws Exception {
        FakePeer peer = new FakePeer();
        Link link = attach(peer, HOUR_LATER);
        peer.next();
        link.receive(new Call("c-1", "alice", "s-1", "write_file", ARGS, 1000));
        assertEquals(1002, peer.closed.get(2, TimeUnit.SECONDS));
        assertEquals(Optional.empty(), links.status(ALICE));
    }

    /** 心跳：定时 ping；有 pong 就一直连着，太久没 pong 就关掉。 */
    @Test
    void silentLinksAreClosed() throws Exception {
        try (LocalLinks fast = new LocalLinks(tokens, clock, new Timing(Duration.ofSeconds(5), Duration.ofMillis(50),
                Duration.ofMillis(400), Duration.ofMillis(300)))) {
            FakePeer answering = new FakePeer();
            Link alive = fast.attach(ALICE, HOUR_LATER, System.nanoTime(), HELLO, answering).orElseThrow();
            answering.onPing = alive::pong;
            FakePeer silent = new FakePeer();
            Principal bob = new Principal("bob");
            fast.attach(bob, HOUR_LATER, System.nanoTime(), HELLO, silent).orElseThrow();

            assertEquals(1001, silent.closed.get(3, TimeUnit.SECONDS));
            assertTrue(silent.pings.get() > 0);
            assertEquals(Optional.empty(), fast.status(bob));
            Thread.sleep(500);
            assertFalse(answering.closed.isDone(), "a link that answers pings stays");
            assertTrue(answering.pings.get() >= 5, String.valueOf(answering.pings.get()));
        }
    }

    /** 关机：每台电脑收到 {@code bye shutdown}。 */
    @Test
    void shutdownSaysGoodbye() throws Exception {
        FakePeer peer = new FakePeer();
        attach(peer, HOUR_LATER);
        peer.next();
        links.close();
        assertEquals(new Bye(Bye.Reason.SHUTDOWN), peer.next());
        assertEquals(1001, peer.closed.get(2, TimeUnit.SECONDS));
    }

    private Optional<String> agents() {
        try {
            return links.agents(ALICE);
        } catch (InterruptedException stopped) {
            throw new IllegalStateException(stopped);
        }
    }

    private CompletableFuture<LocalResult> callAsync(String session, Duration timeout) {
        CompletableFuture<LocalResult> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(links.call(ALICE, session, "read_file", ARGS, timeout));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** 记下发出的消息、ping 和关闭码。 */
    /** 停用或删掉的账号（服务外的命令行停用）：握手时按“已撤销”拒绝，令牌全部作废，账号恢复后也不能再用。 */
    @Test
    void aDisabledAccountCannotConnectAndLosesItsTokens() {
        String token = tokens.issue(ALICE, Duration.ofHours(1)).token();
        java.util.concurrent.atomic.AtomicBoolean usable = new java.util.concurrent.atomic.AtomicBoolean(false);
        links.accounts(principal -> usable.get());
        assertEquals(LocalTokens.Status.REVOKED, links.verify(token).status());
        usable.set(true);
        assertEquals(LocalTokens.Status.REVOKED, links.verify(token).status(), "the token stays revoked");
        String fresh = tokens.issue(ALICE, Duration.ofHours(1)).token();
        assertEquals(LocalTokens.Status.VALID, links.verify(fresh).status());
    }

    /** 连着的时候账号被停用：下一次心跳撤销令牌、发 {@code bye revoked} 断开。 */
    @Test
    void aLinkEndsWhenItsAccountIsDisabled() throws Exception {
        try (LocalLinks beating = new LocalLinks(tokens, clock, new Timing(Duration.ofSeconds(5), Duration.ofMillis(100),
                Duration.ofHours(1), Duration.ofMillis(300)))) {
            String token = tokens.issue(ALICE, Duration.ofHours(1)).token();
            java.util.concurrent.atomic.AtomicBoolean usable = new java.util.concurrent.atomic.AtomicBoolean(true);
            beating.accounts(principal -> usable.get());
            FakePeer peer = new FakePeer();
            beating.attach(ALICE, HOUR_LATER, System.nanoTime(), HELLO, peer).orElseThrow();
            assertInstanceOf(Welcome.class, peer.next());
            usable.set(false);
            assertEquals(new Bye(Bye.Reason.REVOKED), peer.next());
            assertEquals(Wire.CLOSE_EXPIRED, peer.closed.get(2, TimeUnit.SECONDS));
            assertEquals(Optional.empty(), beating.status(ALICE));
            assertEquals(LocalTokens.Status.REVOKED, tokens.verify(token).status());
        }
    }

    static final class FakePeer implements LocalLinks.Peer {

        final BlockingQueue<Message> sent = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final AtomicInteger pings = new AtomicInteger();
        volatile Runnable onPing = () -> { };

        @Override
        public void send(String text) {
            sent.add(Wire.decode(text));
        }

        @Override
        public void ping() {
            pings.incrementAndGet();
            onPing.run();
        }

        @Override
        public void close(int code, String reason) {
            closed.complete(code);
        }

        Message next() throws InterruptedException {
            Message message = sent.poll(2, TimeUnit.SECONDS);
            if (message == null) {
                throw new AssertionError("nothing was sent");
            }
            return message;
        }
    }
}
