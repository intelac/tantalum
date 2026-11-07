package tantalum.connect;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import tantalum.local.wire.Bye;
import tantalum.local.wire.Call;
import tantalum.local.wire.Cancel;
import tantalum.local.wire.Context;
import tantalum.local.wire.ContextResult;
import tantalum.local.wire.Hello;
import tantalum.local.wire.Message;
import tantalum.local.wire.Welcome;
import tantalum.local.wire.Wire;

/**
 * 连到服务器的那条 WebSocket（local-workspace 设计“tantalum.connect.Link”）。握手带 {@code Authorization: Bearer}，连上先发
 * {@code hello}。断线按 1、2、4、8、16、30、30… 秒重连；令牌无效、到期、撤销、被替换、协议版本不对时说明原因并退出，
 * 退出码 2。断开时打断这条连接上在跑的调用（L8）。
 */
final class Link {

    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
            Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(30));
    /** 服务器每 15 秒 ping 一次；这么久什么都没收到，就当网络断了，重连。 */
    static final Duration SILENCE = Duration.ofSeconds(60);
    /** 服务器拒绝或结束了连接，不再重连。 */
    static final int ENDED_BY_SERVER = 2;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final URI socket;
    private final String token;
    private final Hello hello;
    private final Calls calls;
    private final PrintStream out;
    private final Words words;
    private final List<Duration> backoff;
    private final Duration silence;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private volatile Session current;

    Link(Main.Setup setup, Calls calls, PrintStream out, Words words) {
        this(setup.socket(), setup.token(), setup.hello(), calls, out, words, BACKOFF, SILENCE);
    }

    /** {@code backoff} 是每次重连前等多久，用完之后一直用最后一个。 */
    Link(URI socket, String token, Hello hello, Calls calls, PrintStream out, Words words, List<Duration> backoff, Duration silence) {
        this.socket = socket;
        this.token = token;
        this.hello = hello;
        this.calls = calls;
        this.out = out;
        this.words = words;
        this.backoff = List.copyOf(backoff);
        this.silence = silence;
    }

    /** 连接、断线重连，直到服务器结束这条连接（返回 2）或 {@link #stop()}（返回 0）。 */
    int run() throws InterruptedException {
        int attempt = 0;
        while (!stopped.isDone()) {
            Session session = new Session();
            current = session;
            if (stopped.isDone()) {
                // stop() 和这里交错：它可能没看到这个 session，自己收尾
                return 0;
            }
            try {
                http.newWebSocketBuilder().header("Authorization", "Bearer " + token).buildAsync(socket, session).get();
            } catch (ExecutionException failure) {
                Duration wait = wait(attempt++);
                out.println(words.say(Words.Key.LINK_RETRY, Map.of("cause", describe(failure.getCause()), "seconds", wait.toSeconds())));
                if (pause(wait)) {
                    return 0;
                }
                continue;
            }
            session.send(hello);
            Ended ended = session.ended();
            calls.stopAll();
            if (stopped.isDone()) {
                return 0;
            }
            String fatal = fatal(ended, words);
            if (fatal != null) {
                out.println(fatal);
                return ENDED_BY_SERVER;
            }
            if (session.welcomed) {
                attempt = 0;
            }
            Duration wait = wait(attempt++);
            out.println(words.say(ended.bye == Bye.Reason.SHUTDOWN ? Words.Key.LINK_SHUTDOWN : Words.Key.LINK_LOST,
                    Map.of("seconds", wait.toSeconds())));
            if (pause(wait)) {
                return 0;
            }
        }
        return 0;
    }

    /** 不再重连，断开当前连接，{@link #run()} 返回 0。 */
    void stop() {
        stopped.complete(null);
        Session session = current;
        if (session != null) {
            session.end(new Ended(1000, "stopped", null));
        }
    }

    private Duration wait(int attempt) {
        return backoff.get(Math.min(attempt, backoff.size() - 1));
    }

    /** 等 {@code wait}；期间被 {@link #stop()} 时返回 true。 */
    private boolean pause(Duration wait) throws InterruptedException {
        try {
            stopped.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException waited) {
            return false;
        } catch (ExecutionException impossible) {
            return true;
        }
    }

    /** 不再重连的几种结束，返回给用户看的原因；可以重连时为 null。先看关闭码，没有时看 {@code bye}。 */
    static String fatal(Ended ended, Words words) {
        switch (ended.code) {
            case Wire.CLOSE_BAD_TOKEN -> {
                return words.say(Words.Key.LINK_BAD_TOKEN);
            }
            case Wire.CLOSE_EXPIRED -> {
                return words.say("revoked".equals(ended.reason) || ended.bye == Bye.Reason.REVOKED ? Words.Key.LINK_REVOKED : Words.Key.LINK_EXPIRED);
            }
            case Wire.CLOSE_REPLACED -> {
                return words.say(Words.Key.LINK_REPLACED);
            }
            case Wire.CLOSE_PROTOCOL -> {
                return words.say(Words.Key.LINK_PROTOCOL);
            }
            default -> {
                if (ended.bye == null || ended.bye == Bye.Reason.SHUTDOWN) {
                    return null;
                }
                return fatal(new Ended(ended.bye == Bye.Reason.REPLACED ? Wire.CLOSE_REPLACED : Wire.CLOSE_EXPIRED,
                        ended.bye == Bye.Reason.REVOKED ? "revoked" : "expired", ended.bye), words);
            }
        }
    }

    /** 给用户看的连接失败原因。握手被拒时只说状态码，不带响应内容。 */
    private static String describe(Throwable failure) {
        if (failure instanceof WebSocketHandshakeException handshake) {
            return "HTTP " + handshake.getResponse().statusCode();
        }
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : "：" + message);
    }

    /** 一条连接怎么结束的：关闭码、关闭原因、之前收到的 {@code bye}（没有时为 null）。 */
    record Ended(int code, String reason, Bye.Reason bye) {}

    /** 一次连接：收消息、发消息、看有没有断。 */
    private final class Session implements WebSocket.Listener {

        private final CompletableFuture<Ended> ended = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        /** JDK 的 WebSocket 不允许上一条没发完就发下一条，这里排队。 */
        private final ReentrantLock sending = new ReentrantLock();
        private volatile WebSocket socket;
        private volatile boolean welcomed;
        private volatile Bye.Reason bye;
        private volatile long heard = System.nanoTime();

        /** 等这次连接结束；太久什么都没收到也算结束。 */
        Ended ended() throws InterruptedException {
            while (true) {
                try {
                    return ended.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException waiting) {
                    if (stopped.isDone()) {
                        end(new Ended(1000, "stopped", bye));
                    } else if (System.nanoTime() - heard > silence.toNanos()) {
                        end(new Ended(1006, "silent", bye));
                    }
                } catch (ExecutionException impossible) {
                    return new Ended(1006, "failed", bye);
                }
            }
        }

        void end(Ended how) {
            if (ended.complete(how)) {
                WebSocket open = socket;
                if (open != null) {
                    open.abort();
                }
            }
        }

        /** 连上了：从这里拿到 WebSocket，之后才能发。连上之前就已经结束的（stop()、静默超时），立即断开。 */
        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            webSocket.request(1);
            if (ended.isDone()) {
                webSocket.abort();
            }
        }

        void send(Message message) {
            WebSocket open = socket;
            if (open == null || ended.isDone()) {
                return;
            }
            sending.lock();
            try {
                open.sendText(Wire.encode(message), true).get(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                end(new Ended(1006, "interrupted", bye));
            } catch (ExecutionException | TimeoutException failure) {
                end(new Ended(1006, "send failed", bye));
            } finally {
                sending.unlock();
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            heard = System.nanoTime();
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                Message message;
                try {
                    message = Wire.decode(text);
                } catch (IllegalArgumentException unreadable) {
                    out.println(words.say(Words.Key.LINK_UNREADABLE));
                    webSocket.sendClose(1003, "bad message");
                    end(new Ended(1003, "bad message", bye));
                    webSocket.request(1);
                    return null;
                }
                try {
                    handle(message);
                } catch (RuntimeException failure) {
                    // 处理出错是这边的问题，不是消息不可读：按内部错误结束，重连
                    System.err.println("[tantalum-connect] handling a " + message.getClass().getSimpleName() + " failed: " + failure);
                    end(new Ended(1011, "handler failed", bye));
                }
            }
            webSocket.request(1);
            return null;
        }

        private void handle(Message message) {
            switch (message) {
                case Welcome welcome -> {
                    welcomed = true;
                    calls.welcome(welcome.user());
                    out.println(words.say(Words.Key.LINK_CONNECTED, Map.of("user", welcome.user(),
                            "time", welcome.expiresAt().atZone(ZoneId.systemDefault()).format(CLOCK))));
                }
                case Call call -> calls.call(call, this::send);
                case Cancel cancel -> calls.cancel(cancel.id());
                case Context context -> Thread.ofVirtual().start(() -> {
                    ContextResult result;
                    try {
                        result = calls.context(context);
                    } catch (RuntimeException failure) {
                        System.err.println("[tantalum-connect] reading AGENTS.md failed: " + failure);
                        result = new ContextResult(context.id(), null);
                    }
                    send(result);
                });
                case Bye goodbye -> bye = goodbye.reason();
                default -> { }
            }
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            heard = System.nanoTime();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            end(new Ended(statusCode, reason, bye));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            end(new Ended(1006, error.getClass().getSimpleName(), bye));
        }
    }
}
