package tantalum.shell.local;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.local.tool.LocalResult;
import tantalum.local.wire.Browse;
import tantalum.local.wire.BrowseResult;
import tantalum.local.wire.Bye;
import tantalum.local.wire.Call;
import tantalum.local.wire.Cancel;
import tantalum.local.wire.Context;
import tantalum.local.wire.ContextResult;
import tantalum.local.wire.Hello;
import tantalum.local.wire.Message;
import tantalum.local.wire.McpTools;
import tantalum.local.wire.McpServerState;
import tantalum.local.wire.Result;
import tantalum.local.wire.SkillLoad;
import tantalum.local.wire.SkillLoaded;
import tantalum.local.wire.Welcome;
import tantalum.local.wire.Wire;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

/**
 * 每个用户最多一条连到电脑的连接。新连接顶替旧的；到期、撤销时发 {@link Bye} 并关掉。
 * {@link #call} 把一次工具调用发给电脑并等结果；连接断开时还没回来的调用都记成“结果不确定”，不重发。
 * 连接本身由 {@link LocalEndpoint} 管，这里只通过 {@link Peer} 收发。
 */
public final class LocalLinks implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LocalLinks.class);

    public static final String NOT_CONNECTED = "The local workspace is not connected.";
    public static final String DISCONNECTED = "The computer disconnected. Whether this operation finished is unknown.";
    /** 每条连接同时最多执行的调用数，多的排队，排队也计入超时。 */
    public static final int CONCURRENCY = 4;
    private static final String DISCONNECTED_ID = "disconnected";

    /**
     * 几个时间间隔，做成参数是为了测试跑得快。
     *
     * @param hello 连上后多久内必须收到 {@code hello}
     * @param ping 多久发一次 WebSocket ping
     * @param silence 多久收不到 pong 就关连接
     * @param agents 要 {@code AGENTS.md} 时最多等多久
     */
    public record Timing(Duration hello, Duration ping, Duration silence, Duration agents) {

        public static final Timing DEFAULT = new Timing(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45),
                Duration.ofSeconds(2));
    }

    /** 一条连接的发送端，由 {@link LocalEndpoint} 实现，测试里用假的。实现要能被多个线程同时调用。 */
    public interface Peer {

        void send(String text) throws IOException;

        void ping() throws IOException;

        /** 带关闭码关掉；已经关了就什么都不做。 */
        void close(int code, String reason);
    }

    /** 插件页看的连接状态：电脑在 {@code hello} 里说的、什么时候连上、什么时候到期。 */
    public record Status(Hello hello, Instant connectedAt, Instant expiresAt) {}

    private final LocalTokens tokens;
    private final Clock clock;
    private final Timing timing;
    private final Map<Principal, Link> links = new ConcurrentHashMap<>();
    /** 每个用户最后一次撤销的时刻（{@link System#nanoTime()}），挡住撤销前握手、撤销后才发 {@code hello} 的连接。 */
    private final Map<Principal, Long> revokedAt = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    /** 账号还能不能用（没停用、没删除），默认都能用。 */
    private volatile Predicate<Principal> accounts = principal -> true;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("local-links").factory());

    public LocalLinks(LocalTokens tokens, Clock clock, Timing timing) {
        this.tokens = tokens;
        this.clock = clock;
        this.timing = timing;
        long every = timing.ping().toMillis();
        timers.scheduleWithFixedDelay(this::heartbeat, every, every, TimeUnit.MILLISECONDS);
    }

    public LocalTokens tokens() {
        return tokens;
    }

    public Timing timing() {
        return timing;
    }

    /**
     * 怎么查账号还能不能用。停用账号可能来自服务之外的命令行，所以不等通知：握手时查（{@link #verify}），之后每次心跳再查；
     * 停用或删掉的账号，令牌全部撤销、连接断开。
     */
    public void accounts(Predicate<Principal> usable) {
        this.accounts = usable;
    }

    /** 握手时查令牌：令牌有效但账号已经停用或删掉时，撤销这个用户的全部令牌，按“已撤销”拒绝。 */
    public LocalTokens.Verdict verify(String token) {
        LocalTokens.Verdict verdict = tokens.verify(token);
        if (verdict.valid() && !accounts.test(verdict.principal())) {
            LOG.info("local connect for {}: the account is disabled or gone; revoking its tokens", verdict.principal().value());
            // 走完整的撤销：记下撤销时刻、断开已有的连接，正在握手的也接不上
            revoke(verdict.principal());
            return new LocalTokens.Verdict(LocalTokens.Status.REVOKED, verdict.principal(), verdict.expiresAt());
        }
        return verdict;
    }

    /**
     * 握手通过、收到 {@code hello} 之后由端点调用：这个用户已有连接时让旧的退出，安排到期时刻断开，回 {@code welcome}。
     * 返回的 {@link Link} 接收这条连接上后续的消息。{@code verifiedAt}（{@link System#nanoTime()}）是握手查令牌的时刻：
     * 那之后用户撤销过，就不接这条连接，返回空。
     * <p>先在锁里登记、安排到期，再发 {@code welcome}：登记之后 {@link #revoke} 一定看得到这条连接；
     * 调用在 {@code welcome} 发出之前先等着（{@link #call}），电脑不会在知道自己连的是谁之前收到调用。
     */
    public Optional<Link> attach(Principal principal, Instant expiresAt, long verifiedAt, Hello hello, Peer peer) {
        Link link = new Link(principal, hello, clock.instant(), expiresAt, peer);
        Link old;
        synchronized (this) {
            if (revokedSince(principal, verifiedAt)) {
                return Optional.empty();
            }
            old = links.put(principal, link);
        }
        if (old != null) {
            LOG.info("local link for {} replaced by a new connection", principal.value());
            old.end(Bye.Reason.REPLACED, Wire.CLOSE_REPLACED);
        }
        long delay = Math.max(0, Duration.between(clock.instant(), expiresAt).toMillis());
        link.expiry = timers.schedule(() -> later(() -> {
            LOG.info("local link for {} expired", principal.value());
            link.end(Bye.Reason.EXPIRED, Wire.CLOSE_EXPIRED);
        }), delay, TimeUnit.MILLISECONDS);
        if (link.send(new Welcome(principal.value(), expiresAt))) {
            link.welcomed.countDown();
        }
        // 发不出去时 send 已经按断开处理（摘掉登记、取消到期），返回的 Link 只是让端点关闭时有东西可报
        return Optional.of(link);
    }

    private boolean revokedSince(Principal principal, long verifiedAt) {
        Long revoked = revokedAt.get(principal);
        return revoked != null && revoked - verifiedAt >= 0;
    }

    /** 撤销这个用户的全部令牌，并断开它的连接。正在握手、还没发 {@code hello} 的连接也接不上了。 */
    public void revoke(Principal principal) {
        Link link;
        synchronized (this) {
            tokens.revokeAll(principal);
            revokedAt.put(principal, System.nanoTime());
            link = links.get(principal);
        }
        if (link != null) {
            LOG.info("local link for {} revoked", principal.value());
            link.end(Bye.Reason.REVOKED, Wire.CLOSE_EXPIRED);
        }
    }

    public Optional<Status> status(Principal principal) {
        Link link = links.get(principal);
        return link == null ? Optional.empty() : Optional.of(new Status(link.hello, link.connectedAt, link.expiresAt));
    }

    /**
     * 在这个用户的电脑上执行一次工具调用，最多等 {@code timeout}（等名额的时间也算）。没有连接、断开、超时都返回错误结果；
     * 超时和被打断时给电脑发 {@code cancel}，被打断时照样抛 {@link InterruptedException}。
     */
    public LocalResult call(Principal principal, String session, String tool, Json arguments, Duration timeout)
            throws InterruptedException {
        return call(principal, session, tool, arguments, timeout, null);
    }

    /** 同上，{@code dir} 是会话的工作目录：连接程序是协议 3 起才发，协议 2 的不认识它，照旧用主目录。 */
    public LocalResult call(Principal principal, String session, String tool, Json arguments, Duration timeout, String dir)
            throws InterruptedException {
        return call(principal, session, tool, arguments, timeout, dir, null);
    }

    /** 同上，{@code skills} 是这个用户的技能目录，只有 {@code skill} 工具带（协议 4 起才发）。 */
    public LocalResult call(Principal principal, String session, String tool, Json arguments, Duration timeout, String dir,
                            String skills) throws InterruptedException {
        Link link = links.get(principal);
        if (link == null) {
            return LocalResult.error(NOT_CONNECTED);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        if (!link.permits.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            return LocalResult.error("Tool timed out after " + timeout + " waiting for the computer.");
        }
        String id = "c-" + ids.incrementAndGet();
        try {
            // welcome 还没发出去就等：电脑要先知道连的是谁，否则它按“连的是别的用户”拒绝
            if (!link.welcomed.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) || link.over.get()) {
                return LocalResult.error(NOT_CONNECTED);
            }
            CompletableFuture<Result> reply = new CompletableFuture<>();
            link.calls.put(id, reply);
            long left = deadline - System.nanoTime();
            if (link.over.get() || !link.send(new Call(id, principal.value(), session, tool, arguments, Math.max(1, left / 1_000_000),
                    link.sessionDirs() ? dir : null, link.skillLists() ? skills : null))) {
                return LocalResult.error(DISCONNECTED);
            }
            try {
                return reply.get(Math.max(0, left), TimeUnit.NANOSECONDS).toLocal();
            } catch (TimeoutException late) {
                link.send(new Cancel(id));
                return LocalResult.error("Tool timed out after " + timeout + ". Its outcome is unknown.");
            } catch (InterruptedException stopped) {
                link.send(new Cancel(id));
                throw stopped;
            } catch (ExecutionException impossible) {
                return LocalResult.error(DISCONNECTED);
            } catch (IllegalArgumentException badImage) {
                return LocalResult.error("The computer sent a malformed result: " + badImage.getMessage());
            }
        } finally {
            link.calls.remove(id);
            link.permits.release();
        }
    }

    /** 列子目录的结果：{@code result} 只在 {@code OK} 时有。 */
    public record Listing(Outcome outcome, BrowseResult result) {

        public enum Outcome { OK, NOT_CONNECTED, OLD_PROTOCOL, NO_ANSWER }
    }

    /**
     * 添加工作区时列电脑上 {@code path} 下的子目录（null 列允许的目录本身），最多等 {@link Timing#agents()}。
     * 协议 2 的连接程序不会列，直接回 {@code OLD_PROTOCOL}。
     */
    public Listing browse(Principal principal, String path) throws InterruptedException {
        Link link = links.get(principal);
        if (link == null || link.over.get()) {
            return new Listing(Listing.Outcome.NOT_CONNECTED, null);
        }
        if (!link.sessionDirs()) {
            return new Listing(Listing.Outcome.OLD_PROTOCOL, null);
        }
        String id = "b-" + ids.incrementAndGet();
        CompletableFuture<BrowseResult> reply = new CompletableFuture<>();
        link.browses.put(id, reply);
        try {
            if (!link.send(new Browse(id, path))) {
                return new Listing(Listing.Outcome.NOT_CONNECTED, null);
            }
            return new Listing(Listing.Outcome.OK, reply.get(timing.agents().toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException late) {
            LOG.info("local link for {}: folder listing did not arrive within {}", principal.value(), timing.agents());
            return new Listing(Listing.Outcome.NO_ANSWER, null);
        } catch (ExecutionException disconnected) {
            return new Listing(Listing.Outcome.NOT_CONNECTED, null);
        } finally {
            link.browses.remove(id);
        }
    }

    /** 主目录里的 {@code AGENTS.md}，最多等 {@link Timing#agents()}；没有连接、没有这个文件、没按时回都是空，没按时回记一行日志。 */
    public Optional<String> agents(Principal principal) throws InterruptedException {
        return agents(principal, null);
    }

    /** 同上，从会话的工作目录读（协议 3 起；协议 2 的连接照旧从主目录读）。 */
    public Optional<String> agents(Principal principal, String dir) throws InterruptedException {
        Reply<ContextResult> reply = context(principal, dir, null, null);
        return reply.outcome() == Reply.Outcome.OK ? Optional.ofNullable(reply.result().agents()) : Optional.empty();
    }

    /** 问电脑一件事的结果：{@code result} 只在 {@code OK} 时有。 */
    public record Reply<T>(Outcome outcome, T result) {

        public enum Outcome { OK, NOT_CONNECTED, NO_ANSWER }

        public static <T> Reply<T> notConnected() {
            return new Reply<>(Outcome.NOT_CONNECTED, null);
        }
    }

    /**
     * 一次往返要 {@code AGENTS.md}、技能清单（{@code skills} 是用户填的技能目录）、wiki 页（{@code wiki} 同理），
     * 最多等 {@link Timing#agents()}。两个目录为 null 就不问那一样；协议 3 及以前的连接程序不认识它们，不发。
     */
    public Reply<ContextResult> context(Principal principal, String dir, String skills, String wiki) throws InterruptedException {
        Link link = links.get(principal);
        if (link == null) {
            return Reply.notConnected();
        }
        String id = "x-" + ids.incrementAndGet();
        CompletableFuture<ContextResult> reply = new CompletableFuture<>();
        link.contexts.put(id, reply);
        try {
            boolean lists = link.skillLists();
            if (link.over.get() || !link.send(new Context(id, link.sessionDirs() ? dir : null, lists ? skills : null, lists ? wiki : null))) {
                return Reply.notConnected();
            }
            ContextResult result = reply.get(timing.agents().toMillis(), TimeUnit.MILLISECONDS);
            return link.over.get() && DISCONNECTED_ID.equals(result.id()) ? Reply.notConnected() : new Reply<>(Reply.Outcome.OK, result);
        } catch (TimeoutException late) {
            LOG.info("local link for {}: the context (AGENTS.md, skills, wiki) did not arrive within {}", principal.value(), timing.agents());
            return new Reply<>(Reply.Outcome.NO_ANSWER, null);
        } catch (ExecutionException disconnected) {
            return Reply.notConnected();
        } finally {
            link.contexts.remove(id);
        }
    }

    /**
     * 用户点名的技能：让电脑当场读，最多等 {@link Timing#agents()}。协议 3 及以前的连接程序不认识这条消息，
     * 发了它会断开，所以直接算没连上。
     */
    public Reply<SkillLoaded> skillLoad(Principal principal, String session, String skills, List<String> names) throws InterruptedException {
        Link link = links.get(principal);
        if (link == null || link.over.get() || !link.skillLists()) {
            return Reply.notConnected();
        }
        String id = "k-" + ids.incrementAndGet();
        CompletableFuture<SkillLoaded> reply = new CompletableFuture<>();
        link.loads.put(id, reply);
        try {
            if (!link.send(new SkillLoad(id, session, skills, names))) {
                return Reply.notConnected();
            }
            return new Reply<>(Reply.Outcome.OK, reply.get(timing.agents().toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException late) {
            LOG.info("local link for {}: {} named skill(s) did not arrive within {}", principal.value(), names.size(), timing.agents());
            return new Reply<>(Reply.Outcome.NO_ANSWER, null);
        } catch (ExecutionException disconnected) {
            return Reply.notConnected();
        } finally {
            link.loads.remove(id);
        }
    }

    /** 端点在自己的线程之外安排一件事（如等 {@code hello}）。在虚拟线程上执行，不拖住其他连接的计时。 */
    ScheduledFuture<?> schedule(Duration delay, Runnable action) {
        return timers.schedule(() -> later(action), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void later(Runnable action) {
        Thread.ofVirtual().name("local-links-action").start(action);
    }

    /** 给每条连接发 ping；太久没 pong 的关掉，电脑那边会重连。账号停用或删掉的，撤销令牌并断开。 */
    private void heartbeat() {
        long now = System.nanoTime();
        for (Link link : links.values()) {
            if (now - link.lastPong > timing.silence().toNanos()) {
                LOG.info("local link for {}: no pong for {}; closing", link.principal.value(), timing.silence());
                later(() -> {
                    // 先从名单里拿掉再关：对面一看到关闭，这条连接就已经不算在线
                    link.dropped();
                    link.peer.close(1001, "no pong");
                });
            } else {
                later(() -> {
                    if (accounts.test(link.principal)) {
                        link.ping();
                    } else {
                        LOG.info("local link for {}: the account is disabled or gone", link.principal.value());
                        revoke(link.principal);
                    }
                });
            }
        }
    }

    /** 服务器关机：告诉每台电脑，然后关掉。电脑稍后用仍有效的令牌重连。 */
    @Override
    public void close() {
        timers.shutdownNow();
        for (Link link : List.copyOf(links.values())) {
            link.end(Bye.Reason.SHUTDOWN, 1001);
        }
    }

    /** 一条连接。端点把收到的消息、pong 和关闭交给它。 */
    public final class Link {

        private final Principal principal;
        /** 连上时的 {@code hello}；本机 MCP 服务器的清单随 {@link McpTools} 更新（协议 6 起）。 */
        private volatile Hello hello;
        private final Instant connectedAt;
        private final Instant expiresAt;
        private final Peer peer;
        private final Semaphore permits = new Semaphore(CONCURRENCY, true);
        private final Map<String, CompletableFuture<Result>> calls = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<ContextResult>> contexts = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<BrowseResult>> browses = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<SkillLoaded>> loads = new ConcurrentHashMap<>();
        private final AtomicBoolean over = new AtomicBoolean();
        /** {@code welcome} 发出去了（或者连接已经断了，不会再发）。 */
        private final CountDownLatch welcomed = new CountDownLatch(1);
        private volatile long lastPong = System.nanoTime();
        private volatile ScheduledFuture<?> expiry;

        private Link(Principal principal, Hello hello, Instant connectedAt, Instant expiresAt, Peer peer) {
            this.principal = Objects.requireNonNull(principal, "principal");
            this.hello = Objects.requireNonNull(hello, "hello");
            this.connectedAt = connectedAt;
            this.expiresAt = expiresAt;
            this.peer = peer;
        }

        /** 连接程序认会话目录和列子目录（协议 3 起）。 */
        boolean sessionDirs() {
            return hello.protocol() >= LocalWorkspace.SESSION_DIRS;
        }

        /** 连接程序会列技能和 wiki 页、认 {@code skill} 工具和点名（协议 4 起）。 */
        boolean skillLists() {
            return hello.protocol() >= LocalWorkspace.SKILL_LISTS;
        }

        /** 电脑发来的一条消息。方向不对的消息算协议错误，关掉连接，电脑会重连。 */
        public void receive(Message message) {
            switch (message) {
                case Result result -> {
                    CompletableFuture<Result> waiting = calls.get(result.id());
                    if (waiting != null) {
                        waiting.complete(result);
                    }
                }
                case ContextResult result -> {
                    CompletableFuture<ContextResult> waiting = contexts.get(result.id());
                    if (waiting != null) {
                        waiting.complete(result);
                    }
                }
                case BrowseResult result -> {
                    CompletableFuture<BrowseResult> waiting = browses.get(result.id());
                    if (waiting != null) {
                        waiting.complete(result);
                    }
                }
                case SkillLoaded result -> {
                    CompletableFuture<SkillLoaded> waiting = loads.get(result.id());
                    if (waiting != null) {
                        waiting.complete(result);
                    }
                }
                case McpTools update -> {
                    List<McpServerState> servers = new ArrayList<>();
                    boolean replaced = false;
                    for (McpServerState server : hello.mcp()) {
                        if (server.name().equals(update.server().name())) {
                            servers.add(update.server());
                            replaced = true;
                        } else {
                            servers.add(server);
                        }
                    }
                    if (!replaced) {
                        servers.add(update.server());
                    }
                    hello = hello.withMcp(servers);
                }
                default -> {
                    LOG.info("local link for {}: unexpected {} message; closing", principal.value(), message.getClass().getSimpleName());
                    peer.close(1002, "unexpected message");
                    dropped();
                }
            }
        }

        public void pong() {
            lastPong = System.nanoTime();
        }

        /** 连接断了（电脑断开、网络断了、我们关的）：还在等的调用都按断开处理。可以重复调用。 */
        public void dropped() {
            if (!over.compareAndSet(false, true)) {
                return;
            }
            links.remove(principal, this);
            welcomed.countDown();
            ScheduledFuture<?> timer = expiry;
            if (timer != null) {
                timer.cancel(false);
            }
            for (CompletableFuture<Result> waiting : calls.values()) {
                waiting.complete(new Result("disconnected", DISCONNECTED, true, List.of()));
            }
            for (CompletableFuture<ContextResult> waiting : contexts.values()) {
                waiting.complete(new ContextResult(DISCONNECTED_ID, null));
            }
            for (CompletableFuture<BrowseResult> waiting : browses.values()) {
                waiting.completeExceptionally(new IllegalStateException("disconnected"));
            }
            for (CompletableFuture<SkillLoaded> waiting : loads.values()) {
                waiting.completeExceptionally(new IllegalStateException("disconnected"));
            }
            // 叫醒排队等名额的调用，它们看到连接已断就返回
            permits.release(Integer.MAX_VALUE / 2);
        }

        /** 告诉电脑为什么，然后关掉。 */
        private void end(Bye.Reason reason, int code) {
            if (over.get()) {
                return;
            }
            send(new Bye(reason));
            peer.close(code, reason.name().toLowerCase(Locale.ROOT));
            dropped();
        }

        private void ping() {
            try {
                peer.ping();
            } catch (IOException | RuntimeException failure) {
                peer.close(1001, "ping failed");
                dropped();
            }
        }

        /** 发不出去返回 false，并按断开处理。 */
        private boolean send(Message message) {
            try {
                peer.send(Wire.encode(message));
                return true;
            } catch (IOException | RuntimeException failure) {
                LOG.info("local link for {}: send failed ({}); closing", principal.value(), failure.getClass().getSimpleName());
                peer.close(1011, "send failed");
                dropped();
                return false;
            }
        }
    }
}
