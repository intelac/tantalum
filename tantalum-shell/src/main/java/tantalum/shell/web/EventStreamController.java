package tantalum.shell.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tantalum.shell.auth.LoginContextRepository;
import tantalum.shell.boot.Engine;
import tantalum.shell.sse.SseHub;
import tantalum.shell.sse.SseMessage;
import tantalum.value.id.Principal;
import tantalum.value.text.Failures;

/** 每个登录一条 SSE 连接：条目变化、流式分片和事实都从这里推给浏览器。断线后按 {@code Last-Event-ID} 补读。 */
@RestController
public class EventStreamController {

    private static final Logger LOG = LoggerFactory.getLogger(EventStreamController.class);

    private final Engine engine;
    private final LoginContextRepository logins;
    private final Duration heartbeat;

    public EventStreamController(Engine engine, LoginContextRepository logins, @Value("${tantalum.sse-heartbeat:15s}") Duration heartbeat) {
        this.engine = engine;
        this.logins = logins;
        this.heartbeat = heartbeat;
    }

    @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId, HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        Principal user = WebJson.currentUser();
        String login = LoginContextRepository.currentLogin(request);
        SseHub.Subscription subscription = engine.hub().subscribe(user, login, lastEventId);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(failure -> subscription.close());
        Thread.ofVirtual().start(() -> pump(user, login, subscription, emitter));
        return emitter;
    }

    /**
     * 每个心跳周期查一次登录表：登录是否还在、没过期，账号没停用、没改过密码。停用和改密码可能来自另一个进程的命令行，
     * 这边收不到通知。无论怎么退出，订阅都要注销。
     */
    private void pump(Principal user, String login, SseHub.Subscription subscription, SseEmitter emitter) {
        try {
            stream(user, login, subscription, emitter);
        } catch (IOException | SQLException gone) {
            // 客户端走了，或登录表读不了：这条连接到此为止，浏览器会重连
        } catch (IllegalStateException done) {
            LOG.debug("event stream of {} was already completed by the container: {}", user.value(), Failures.describe(done));
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            LOG.error("event stream of {} failed: {}", user.value(), Failures.describe(failure), failure);
            try {
                emitter.completeWithError(failure);
            } catch (RuntimeException alreadyDone) {
                LOG.debug("event stream of {}: cannot report the failure: {}", user.value(), Failures.describe(alreadyDone));
            }
        } finally {
            // 订阅注销、响应结束，无论上面怎么退出（review-fixes 需求 11.3）；已经结束的响应再结束一次会抛，忽略
            subscription.close();
            try {
                emitter.complete();
            } catch (RuntimeException alreadyDone) {
                LOG.debug("event stream of {}: already completed: {}", user.value(), Failures.describe(alreadyDone));
            }
        }
    }

    private void stream(Principal user, String login, SseHub.Subscription subscription, SseEmitter emitter)
            throws IOException, SQLException, InterruptedException {
        long nextCheck = System.currentTimeMillis() + heartbeat.toMillis();
        while (!subscription.closed()) {
            SseMessage message = subscription.poll(Math.max(1, nextCheck - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (subscription.closed()) {
                break;
            }
            long now = System.currentTimeMillis();
            if (now >= nextCheck) {
                if (!logins.stillValid(user.value(), login, now)) {
                    subscription.close();
                    break;
                }
                nextCheck = now + heartbeat.toMillis();
                if (message == null) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                }
            }
            if (message == null) {
                continue;
            }
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(message.event()).data(message.data(), MediaType.TEXT_PLAIN);
            if (message.id() != null) {
                event.id(message.id());
            }
            emitter.send(event);
        }
    }

    /** 在 Web 服务器开始停止之前断开 SSE，否则关闭时要等这些长连接结束。 */
    @EventListener(ContextClosedEvent.class)
    void closeEventStreams() {
        engine.hub().closeEveryone();
    }
}
