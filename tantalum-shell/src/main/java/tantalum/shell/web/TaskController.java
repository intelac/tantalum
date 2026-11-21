package tantalum.shell.web;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.conversation.SessionSummary;
import tantalum.shell.store.SessionQueries;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.id.SessionIds;
import tantalum.value.assignment.OpenAssignment;
import tools.jackson.databind.JsonNode;

/**
 * 一个会话放出的任务（subagent 需求 6、10）：列出来，停一个或全部停。任务自己的会话按会话号用现有的接口看（对话、轨迹、问题），
 * 只是不列进会话清单。别人的会话和不存在的一样是 404。
 */
@RestController
public class TaskController {

    private final Engine engine;
    private final WebJson json;

    TaskController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    /**
     * {@code tasks}：这个会话放出过的任务，按放出的先后。{@code open} 为真是还没回来；{@code session} 是任务自己的会话，
     * {@code runStatus} 是它现在的状态（{@code idle} 是那一轮已经结束）。交代的第一行和 agent 只有还没回来的带着。
     */
    @GetMapping("/api/sessions/{session}/tasks")
    ResponseEntity<String> tasks(@PathVariable String session) throws SQLException {
        Principal user = WebJson.currentUser();
        Optional<SessionId> id = ApiController.sessionId(session);
        Optional<SessionQueries.SessionView> found = id.flatMap(value -> engine.queries().session(user, value));
        if (found.isEmpty()) {
            return json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
        }
        // 按交代号对应（dispatch 审查第 13 条）：一个派活会话会先后收几次交代，主会话也能交给你自己开的会话
        List<OpenAssignment> open = new ArrayList<>(found.get().state().assignments().open());
        List<Map<String, Object>> tasks = new ArrayList<>();
        String prefix = id.get().value() + "-";
        for (SessionSummary child : engine.queries().tasksOf(user, id.get())) {
            List<OpenAssignment> here = open.stream().filter(task -> task.session().equals(child.id())).toList();
            open.removeAll(here);
            if (!here.isEmpty()) {
                here.forEach(task -> tasks.add(view(task.id(), child.id(), child.runStatus(), task)));
            } else {
                tasks.add(view(taskOf(child.id(), prefix), child.id(), child.runStatus(), null));
            }
        }
        // 刚放出、对方会话还没写过事件的，和交给你自己开的会话的
        open.forEach(task -> tasks.add(view(task.id(), task.session(), SessionSummary.IDLE, task)));
        return json.json(200, Map.of("tasks", tasks));
    }

    /** 已经回来的那件事的交代号：帮手是编号的后半；派活会话是开它的那一次（{@code work-用户-3-0} 是 {@code t3-0}）。 */
    private static String taskOf(SessionId child, String prefix) {
        if (SessionIds.workShaped(child)) {
            String[] parts = child.value().split("-");
            return "t" + parts[parts.length - 2] + "-" + parts[parts.length - 1];
        }
        return child.value().startsWith(prefix) ? child.value().substring(prefix.length()) : child.value();
    }


    private static Map<String, Object> view(String id, SessionId session, String runStatus, OpenAssignment open) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("session", session.value());
        view.put("runStatus", runStatus);
        view.put("open", open != null);
        if (open != null) {
            view.put("brief", open.brief());
            view.put("started", open.started().toString());
            if (open.agent() != null) {
                view.put("agent", open.agent());
            }
        }
        return view;
    }

    /** 请求体 {@code {"task": "t5-0"}} 停一个，{@code {}} 全部停。任务的结果照常回来（已停止）。 */
    @PostMapping("/api/sessions/{session}/tasks/stop")
    ResponseEntity<String> stop(@PathVariable String session, @RequestBody(required = false) String body) {
        Principal user = WebJson.currentUser();
        Optional<SessionId> id = ApiController.sessionId(session);
        if (id.isEmpty()) {
            return json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
        }
        String task = null;
        if (body != null && !body.isBlank()) {
            JsonNode parsed = json.read(body).orElse(null);
            if (parsed == null || !parsed.isObject() || parsed.has("task") && !parsed.get("task").isString()) {
                return json.error(ErrorCode.REQUEST_INVALID, "body must be {} or {\"task\": string}");
            }
            task = parsed.has("task") ? parsed.get("task").asString() : null;
        }
        int status = engine.intake().stopAssignments(user, id.get(), task);
        return switch (status) {
            case 200 -> json.json(200, Map.of("stopping", true));
            case 404 -> json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
            default -> json.error(status, ErrorCode.RUN_CANCEL_REJECTED, "stop rejected");
        };
    }
}
