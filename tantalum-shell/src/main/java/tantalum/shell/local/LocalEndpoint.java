package tantalum.shell.local;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.local.wire.Hello;
import tantalum.local.wire.Message;
import tantalum.local.wire.Wire;
import tantalum.shell.local.LocalTokens.Verdict;

/**
 * 连接程序连进来的 WebSocket 端点 {@value #PATH}（local-workspace 设计“LocalEndpoint”）。握手时读 {@code Authorization: Bearer}
 * 查令牌，令牌本身不留下；不通过就在连上后立即带关闭码关掉（4401 无效、4403 到期或撤销）。第一条消息必须是 {@code hello}，
 * 协议版本不对用 4400 关；之后的消息交给 {@link LocalLinks}。每条连接一个实例。
 */
public final class LocalEndpoint extends Endpoint {

    private static final Logger LOG = LoggerFactory.getLogger(LocalEndpoint.class);

    public static final String PATH = "/api/local/connect";
    /** 单帧上限，够放一张图片（设计“大小”）。 */
    public static final int MAX_FRAME = 16 * 1024 * 1024;

    private static final String VERDICT = "tantalum.local.verdict";
    private static final String VERIFIED_AT = "tantalum.local.verifiedAt";

    private final LocalLinks links;
    private Verdict verdict;
    private long verifiedAt;
    private SessionPeer peer;
    private ScheduledFuture<?> helloTimer;
    private volatile LocalLinks.Link link;

    private LocalEndpoint(LocalLinks links) {
        this.links = links;
    }

    /** 注册用的配置。{@code links} 在第一次握手时才取，所以端点可以在 {@code LocalLinks} 建好之前注册。 */
    public static ServerEndpointConfig config(Supplier<LocalLinks> links) {
        return ServerEndpointConfig.Builder.create(LocalEndpoint.class, PATH).configurator(new Handshake(links)).build();
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        session.setMaxTextMessageBufferSize(MAX_FRAME);
        peer = new SessionPeer(session);
        verdict = (Verdict) config.getUserProperties().get(VERDICT);
        verifiedAt = (Long) config.getUserProperties().getOrDefault(VERIFIED_AT, 0L);
        if (verdict == null || !verdict.valid()) {
            refuse(verdict);
            return;
        }
        helloTimer = links.schedule(links.timing().hello(), () -> {
            if (link == null) {
                LOG.info("local connect for {}: no hello within {}", verdict.principal().value(), links.timing().hello());
                peer.close(1008, "no hello");
            }
        });
        session.addMessageHandler(String.class, this::onText);
        session.addMessageHandler(PongMessage.class, pong -> {
            LocalLinks.Link current = link;
            if (current != null) {
                current.pong();
            }
        });
    }

    private void refuse(Verdict refused) {
        LocalTokens.Status status = refused == null ? LocalTokens.Status.INVALID : refused.status();
        String user = refused == null || refused.principal() == null ? "unknown user" : refused.principal().value();
        LOG.info("local connect refused for {}: {}", user, status);
        switch (status) {
            case EXPIRED -> peer.close(Wire.CLOSE_EXPIRED, "expired");
            case REVOKED -> peer.close(Wire.CLOSE_EXPIRED, "revoked");
            default -> peer.close(Wire.CLOSE_BAD_TOKEN, "invalid token");
        }
    }

    /** 同一条连接的消息由容器依次交来，不会并发。 */
    private void onText(String text) {
        Message message;
        try {
            message = Wire.decode(text);
        } catch (IllegalArgumentException bad) {
            LOG.info("local link for {}: unreadable message; closing", verdict.principal().value());
            peer.close(1003, "bad message");
            dropLink();
            return;
        }
        LocalLinks.Link current = link;
        if (current != null) {
            current.receive(message);
            return;
        }
        if (!(message instanceof Hello hello)) {
            peer.close(1008, "hello first");
            return;
        }
        if (hello.protocol() != Wire.PROTOCOL) {
            LOG.info("local connect for {}: protocol {} is not {}", verdict.principal().value(), hello.protocol(), Wire.PROTOCOL);
            peer.close(Wire.CLOSE_PROTOCOL, "protocol " + Wire.PROTOCOL + " required");
            return;
        }
        helloTimer.cancel(false);
        Optional<LocalLinks.Link> attached = links.attach(verdict.principal(), verdict.expiresAt(), verifiedAt, hello, peer);
        if (attached.isEmpty()) {
            LOG.info("local connect for {}: revoked during the handshake", verdict.principal().value());
            peer.close(Wire.CLOSE_EXPIRED, "revoked");
            return;
        }
        link = attached.get();
        LOG.info("local link for {} connected: {} {}, {} allowed directories, write {}, shell {}", verdict.principal().value(),
                hello.device().os(), hello.device().arch(), hello.roots().size(), hello.write(), hello.shell().available());
    }

    @Override
    public void onClose(Session session, CloseReason reason) {
        if (helloTimer != null) {
            helloTimer.cancel(false);
        }
        dropLink();
    }

    @Override
    public void onError(Session session, Throwable failure) {
        LOG.debug("local link error: {}", failure.getClass().getSimpleName());
    }

    private void dropLink() {
        LocalLinks.Link current = link;
        if (current != null) {
            current.dropped();
        }
    }

    /** 握手时查令牌，把结果（不是令牌）放进这条连接自己的属性里。 */
    private static final class Handshake extends ServerEndpointConfig.Configurator {

        private final Supplier<LocalLinks> links;

        Handshake(Supplier<LocalLinks> links) {
            this.links = links;
        }

        @Override
        public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
            config.getUserProperties().put(VERDICT, links.get().verify(bearer(request.getHeaders())));
            config.getUserProperties().put(VERIFIED_AT, System.nanoTime());
        }

        @Override
        public <T> T getEndpointInstance(Class<T> type) {
            return type.cast(new LocalEndpoint(links.get()));
        }

        private static String bearer(Map<String, List<String>> headers) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (header.getKey().equalsIgnoreCase("Authorization") && !header.getValue().isEmpty()) {
                    String value = header.getValue().getFirst().strip();
                    return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).strip() : null;
                }
            }
            return null;
        }
    }

    /** Jakarta 的同步发送不能并发调用，这里加锁。 */
    private static final class SessionPeer implements LocalLinks.Peer {

        private final Session session;

        SessionPeer(Session session) {
            this.session = session;
        }

        @Override
        public synchronized void send(String text) throws IOException {
            session.getBasicRemote().sendText(text);
        }

        @Override
        public synchronized void ping() throws IOException {
            session.getBasicRemote().sendPing(ByteBuffer.allocate(0));
        }

        @Override
        public void close(int code, String reason) {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), reason));
            } catch (IOException | IllegalStateException alreadyClosing) {
                // 已经在关了
            }
        }
    }
}
