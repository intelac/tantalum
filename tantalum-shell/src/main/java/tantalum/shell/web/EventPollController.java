package tantalum.shell.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.auth.LoginContextRepository;
import tantalum.shell.boot.Engine;
import tantalum.shell.sse.PollStreams;
import tantalum.shell.sse.SseMessage;
import tantalum.value.id.Principal;

/**
 * {@code /api/events} 的后备：有的代理和隧道把长连接的内容憋到响应结束才放行，页面发现后改成反复请求这里。
 * 每次请求等到有消息（或等满）就带着这一批结束，格式和 SSE 一样，页面用同一个解析。第一次不带 {@code stream}，
 * 响应头 {@code X-Event-Stream} 给出编号，之后带着它来；编号失效回 410，页面重新开一条并按 {@code Last-Event-ID} 补读。
 */
@RestController
public class EventPollController {

    static final String STREAM = "X-Event-Stream";
    /** 第一条消息到了以后再等这么久，把紧跟着的一起带回。 */
    private static final long LINGER_MILLIS = 80;

    private final PollStreams streams;
    private final LoginContextRepository logins;
    private final Duration hold;

    public EventPollController(Engine engine, LoginContextRepository logins,
                               @Value("${tantalum.sse-poll-hold:15s}") Duration hold,
                               @Value("${tantalum.sse-poll-idle:60s}") Duration idle) {
        this.streams = new PollStreams(engine.hub(), idle.toMillis());
        this.logins = logins;
        this.hold = hold;
    }

    @GetMapping("/api/events/poll")
    ResponseEntity<byte[]> poll(@RequestParam(name = "stream", required = false) String stream,
                                @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
                                HttpServletRequest request) throws InterruptedException, SQLException {
        Principal user = WebJson.currentUser();
        String login = LoginContextRepository.currentLogin(request);
        PollStreams.Held held;
        boolean opened = stream == null || stream.isEmpty();
        if (opened) {
            held = streams.open(user, login, lastEventId);
        } else {
            Optional<PollStreams.Held> found = streams.find(stream, user, login);
            if (found.isEmpty()) {
                return ResponseEntity.status(410).build();
            }
            held = found.get();
        }
        // 新开的不久等：页面要尽快拿到编号，补读的内容也在这一次带回
        List<SseMessage> batch = held.take(opened ? LINGER_MILLIS : hold.toMillis(), LINGER_MILLIS);
        // 只读查看别人时，登录是管理员自己的
        if (held.closed() && batch.isEmpty() || !logins.stillValid(WebJson.viewer().value(), login, System.currentTimeMillis())) {
            streams.drop(held);
            return ResponseEntity.status(410).build();
        }
        return ResponseEntity.ok().header(STREAM, held.token()).cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8)).body(text(batch).getBytes(StandardCharsets.UTF_8));
    }

    /** 和 SSE 一样的文本：{@code id}、{@code event}、{@code data}（多行就多个 data），空行结束。 */
    static String text(List<SseMessage> batch) {
        StringBuilder out = new StringBuilder();
        for (SseMessage message : batch) {
            if (message.id() != null) {
                out.append("id:").append(message.id()).append('\n');
            }
            out.append("event:").append(message.event()).append('\n');
            for (String line : message.data().split("\n", -1)) {
                out.append("data:").append(line).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }
}
