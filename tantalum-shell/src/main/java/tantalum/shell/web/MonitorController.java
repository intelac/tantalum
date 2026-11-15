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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tantalum.shell.auth.LoginContextRepository;
import tantalum.shell.auth.ViewAs;
import tantalum.shell.boot.Engine;
import tantalum.shell.monitor.Monitor;
import tantalum.shell.monitor.MonitorMessage;
import tantalum.value.id.Principal;
import tantalum.value.text.Failures;

/**
 * 实时监视流（live-monitor 需求 2）：只推当前用户自己会话的阶段消息，管理员只读查看时推被查看的人的。只看当下，断线不补读；
 * 心跳时照事件流的规矩查登录还在不在。
 */
@RestController
public class MonitorController {

    private static final Logger LOG = LoggerFactory.getLogger(MonitorController.class);

    private final Engine engine;
    private final WebJson json;
    private final LoginContextRepository logins;
    private final ViewAs viewAs;
    private final Duration heartbeat;

    public MonitorController(Engine engine, WebJson json, LoginContextRepository logins, ViewAs viewAs,
                             @Value("${tantalum.sse-heartbeat:15s}") Duration heartbeat) {
        this.engine = engine;
        this.json = json;
        this.logins = logins;
        this.viewAs = viewAs;
        this.heartbeat = heartbeat;
    }

    @GetMapping(path = "/api/monitor/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        Principal user = WebJson.currentUser();
        Principal viewer = WebJson.viewer();
        String login = LoginContextRepository.currentLogin(request);
        Monitor.Subscription subscription = engine.monitor().subscribe(user);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(failure -> subscription.close());
        Thread.ofVirtual().start(() -> pump(user, viewer, login, subscription, emitter));
        return emitter;
    }

    private void pump(Principal user, Principal viewer, String login, Monitor.Subscription subscription, SseEmitter emitter) {
        try {
            long nextCheck = System.currentTimeMillis() + heartbeat.toMillis();
            emitter.send(SseEmitter.event().comment("open"));
            while (!subscription.closed()) {
                MonitorMessage message = subscription.poll(Math.max(1, nextCheck - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                long now = System.currentTimeMillis();
                if (now >= nextCheck) {
                    if (!logins.stillValid(viewer.value(), login, now) || (!viewer.equals(user) && !viewAs.stillAllowed(viewer.value()))) {
                        break;
                    }
                    nextCheck = now + heartbeat.toMillis();
                    if (message == null) {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    }
                }
                if (message != null) {
                    emitter.send(SseEmitter.event().name("monitor").data(json.write(message), MediaType.TEXT_PLAIN));
                }
            }
        } catch (IOException | SQLException gone) {
            // 浏览器走了，或登录表读不了：这条连接到此为止
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            LOG.debug("monitor stream of {} ended: {}", user.value(), Failures.describe(failure));
        } finally {
            subscription.close();
            try {
                emitter.complete();
            } catch (RuntimeException alreadyDone) {
                LOG.debug("monitor stream of {}: already completed", user.value());
            }
        }
    }

    @EventListener(ContextClosedEvent.class)
    void closeMonitorStreams() {
        engine.monitor().closeAll();
    }
}
