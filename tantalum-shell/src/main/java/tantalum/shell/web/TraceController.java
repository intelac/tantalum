package tantalum.shell.web;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.store.SessionQueries;
import tantalum.shell.store.TracePage;
import tantalum.shell.store.TraceQuery;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/**
 * 整个会话的轨迹：按运行分页的记录，和一次请求的完整输入。只读，只能看自己的会话，别人的和不存在一样返回 404。
 * 记录里不放 prompt 的消息、system 原文和工具 Schema，它们在请求详情里按需取。
 */
@RestController
public class TraceController {

    private static final Logger LOG = LoggerFactory.getLogger(TraceController.class);

    private final Engine engine;
    private final WebJson json;

    public TraceController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    /** 一页记录：不带参数取最新一页；{@code before} 是上一页的 {@code before}；{@code run} 取到包含这个运行为止（旧链接定位）。 */
    @GetMapping("/api/sessions/{session}/trace")
    ResponseEntity<String> page(@PathVariable String session, @RequestParam(required = false) String before,
                                @RequestParam(required = false) String run) {
        Optional<SessionId> id = ApiController.sessionId(session);
        if (id.isEmpty()) {
            return json.error(400, "invalid session id");
        }
        if (before != null && run != null) {
            return json.error(400, "give before or run, not both");
        }
        TraceQuery query = engine.queries().trace();
        Principal user = WebJson.currentUser();
        Optional<TracePage> page;
        if (run != null) {
            RunId target = run.isBlank() ? null : new RunId(run);
            if (target == null || target.seq() < 0) {
                return json.error(400, "invalid run id");
            }
            if (!target.session().map(id.get()::equals).orElse(false)) {
                return json.error(404, "no such run");
            }
            page = query.through(user, id.get(), target);
        } else {
            Long cursor = position(before);
            if (before != null && cursor == null) {
                return json.error(400, "invalid page position");
            }
            page = query.page(user, id.get(), cursor);
        }
        if (page.isEmpty()) {
            return json.error(404, run != null ? "no such run" : "no such session");
        }
        return json.json(200, page.get().toMap(engine.codec().mapper()));
    }

    /** 一次尝试的完整输入。命令要属于路径里的会话。 */
    @GetMapping("/api/sessions/{session}/trace/calls/{command}")
    ResponseEntity<String> call(@PathVariable String session, @PathVariable String command) {
        Optional<SessionId> id = ApiController.sessionId(session);
        if (id.isEmpty()) {
            return json.error(400, "invalid session id");
        }
        return engine.queries().trace().call(WebJson.currentUser(), id.get(), new CommandId(command))
                .map(found -> json.json(200, found))
                .orElseGet(() -> json.error(404, "no such call"));
    }

    @ExceptionHandler(SessionQueries.SessionUnreadable.class)
    ResponseEntity<String> unreadable(SessionQueries.SessionUnreadable failure) {
        LOG.error("session unreadable", failure);
        return json.error(500, "session unreadable");
    }

    /** 不是非负整数时为空。 */
    private static Long position(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 18 || !raw.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.parseLong(raw);
    }
}
