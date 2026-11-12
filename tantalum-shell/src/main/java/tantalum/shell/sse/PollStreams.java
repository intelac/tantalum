package tantalum.shell.sse;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import tantalum.value.id.Principal;

/**
 * 给读不了长连接的浏览器用的订阅：中间的代理把响应憋到结束才放行时，页面改成一次次短请求来取。
 * 订阅留在服务器这边，两次请求之间到的消息（包括没有 id、补读不回来的分片）都在队列里等着。
 * 一段时间没人来取就关掉。
 */
public final class PollStreams {

    /** 一次登录最多留几条；多了关最久没用的（同一个页面重连、开了几个标签页）。 */
    static final int PER_LOGIN = 4;
    /** 一次最多带回多少条，免得一个响应太大。 */
    static final int BATCH = 500;

    /** 一条留在服务器上的订阅。{@link #take} 一次只让一个请求取。 */
    public final class Held {
        private final String token;
        private final Principal user;
        private final String login;
        private final SseHub.Subscription subscription;
        private volatile long lastUsed;

        private Held(String token, Principal user, String login, SseHub.Subscription subscription, long now) {
            this.token = token;
            this.user = user;
            this.login = login;
            this.subscription = subscription;
            this.lastUsed = now;
        }

        public String token() {
            return token;
        }

        public boolean closed() {
            return subscription.closed();
        }

        /**
         * 等第一条消息最多 {@code waitMillis}；有了以后再凑 {@code lingerMillis} 里跟着到的，一起带回。
         * 没有消息返回空表。订阅已关闭时也返回空表，调用方用 {@link #closed} 分辨。
         */
        public synchronized List<SseMessage> take(long waitMillis, long lingerMillis) throws InterruptedException {
            List<SseMessage> batch = new ArrayList<>();
            SseMessage first = subscription.poll(Math.max(1, waitMillis), TimeUnit.MILLISECONDS);
            if (first != null) {
                batch.add(first);
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lingerMillis);
                while (batch.size() < BATCH && !subscription.closed()) {
                    long left = until - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    SseMessage next = subscription.poll(left, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }
            }
            lastUsed = System.currentTimeMillis();
            return batch;
        }

        private void close() {
            subscription.close();
        }
    }

    private final SseHub hub;
    private final long idleMillis;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Held> held = new ConcurrentHashMap<>();

    public PollStreams(SseHub hub, long idleMillis) {
        this.hub = hub;
        this.idleMillis = idleMillis;
    }

    /** 新开一条。顺手清掉没人取的和这次登录多出来的。 */
    public Held open(Principal user, String login, String lastEventId) {
        long now = System.currentTimeMillis();
        sweep(now);
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Held opened = new Held(token, user, login, hub.subscribe(user, login, lastEventId), now);
        held.put(token, opened);
        List<Held> same = held.values().stream()
                .filter(other -> other.user.equals(user) && java.util.Objects.equals(other.login, login))
                .sorted(Comparator.comparingLong((Held other) -> other.lastUsed).reversed()).toList();
        same.stream().skip(PER_LOGIN).forEach(this::drop);
        return opened;
    }

    /** 只有开它的那个用户、那次登录能接着取；别人拿着编号也当作没有。关掉的不算。 */
    public Optional<Held> find(String token, Principal user, String login) {
        Held found = token == null ? null : held.get(token);
        if (found == null || !found.user.equals(user) || !java.util.Objects.equals(found.login, login)) {
            return Optional.empty();
        }
        if (found.closed()) {
            held.remove(token);
            return Optional.empty();
        }
        return Optional.of(found);
    }

    public void drop(Held gone) {
        held.remove(gone.token);
        gone.close();
    }

    int size() {
        return held.size();
    }

    void sweep(long now) {
        for (Held each : List.copyOf(held.values())) {
            if (each.closed() || now - each.lastUsed > idleMillis) {
                drop(each);
            }
        }
    }
}
