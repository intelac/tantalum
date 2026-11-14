package tantalum.shell.web;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.loop.Intake;
import tantalum.shell.store.SessionQueries;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.state.SessionState;
import tools.jackson.databind.JsonNode;

/** 会话的名字、归档和置顶。改名进账（{@code SessionRenamed}），归档和置顶不进账。别人的会话和不存在的一样是 404。 */
@RestController
public class SessionController {

    /** 内容搜索最多列几个会话（DSH {@code SESSION_SEARCH_RESULT_LIMIT}）。 */
    static final int SEARCH_LIMIT = 20;
    /** 搜索词最长多少字。 */
    static final int MAX_QUERY = 200;
    /** 会话名最长多少字。 */
    static final int MAX_TITLE = 80;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final Engine engine;
    private final WebJson json;

    public SessionController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    /** body：{@code {"title": "...", "requestId": "..."}}。空名字恢复自动标题；没给 requestId 时每次都是新请求。 */
    @PatchMapping("/api/sessions/{session}")
    ResponseEntity<String> rename(@PathVariable String session, @RequestBody String body) {
        Optional<SessionId> id = ApiController.sessionId(session);
        Optional<JsonNode> input = json.read(body);
        if (id.isEmpty() || input.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid session id or body");
        }
        String title = WebJson.text(input.get(), "title");
        if (title == null) {
            return json.error(ErrorCode.REQUEST_INVALID, "missing title");
        }
        String trimmed = title.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_TITLE) {
            return json.error(ErrorCode.REQUEST_INVALID, "title is longer than " + MAX_TITLE + " characters");
        }
        String requestId = WebJson.text(input.get(), "requestId");
        if (requestId != null && !REQUEST_ID.matcher(requestId).matches()) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid requestId");
        }
        Intake.Submission result = engine.intake().rename(WebJson.currentUser(), id.get(),
                new RequestId(requestId == null ? UUID.randomUUID().toString() : requestId), trimmed);
        if (result.status() == 404) {
            return json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
        }
        if (result.status() != 200) {
            return json.error(result.status(), ErrorCode.SUBMISSION_REJECTED, "rename rejected");
        }
        int saved = ApiController.waitSaved(result, ApiController.SAVE_WAIT);
        if (saved != 200) {
            return json.error(saved, saved == 504 ? ErrorCode.SERVER_BUSY : ErrorCode.SERVER_SAVE_FAILED, "the rename was not saved; send again");
        }
        return json.json(200, Map.of("title", trimmed));
    }

    /** body：{@code {"archived": true}}。运行中、等你回答时不能归档；取消归档随时可以。 */
    @PutMapping("/api/sessions/{session}/archived")
    ResponseEntity<String> archive(@PathVariable String session, @RequestBody String body) throws SQLException {
        Optional<SessionId> id = ApiController.sessionId(session);
        Optional<JsonNode> input = json.read(body);
        if (id.isEmpty() || input.isEmpty() || !input.get().path("archived").isBoolean()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"archived\": true|false}");
        }
        Principal user = WebJson.currentUser();
        Optional<SessionState> state = engine.queries().session(user, id.get()).map(SessionQueries.SessionView::state);
        if (state.isEmpty()) {
            return json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
        }
        boolean archived = input.get().get("archived").asBoolean();
        if (archived && state.get().activeRun() != null) {
            return json.error(ErrorCode.SESSION_ARCHIVE_BUSY, "the session is running or waiting for an answer");
        }
        engine.marks().setArchived(user, id.get(), archived);
        return json.json(200, Map.of("archived", archived));
    }

    /** body：{@code {"pinned": true}}。已归档的会话不能置顶（409），取消置顶随时可以（session-find-pin R1）。 */
    @PutMapping("/api/sessions/{session}/pinned")
    ResponseEntity<String> pin(@PathVariable String session, @RequestBody String body) throws SQLException {
        Optional<SessionId> id = ApiController.sessionId(session);
        Optional<JsonNode> input = json.read(body);
        if (id.isEmpty() || input.isEmpty() || !input.get().path("pinned").isBoolean()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"pinned\": true|false}");
        }
        Principal user = WebJson.currentUser();
        if (engine.queries().session(user, id.get()).isEmpty()) {
            return json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
        }
        boolean pinned = input.get().get("pinned").asBoolean();
        if (pinned && engine.marks().archived(user, id.get())) {
            return json.error(ErrorCode.SESSION_ARCHIVED, "an archived session cannot be pinned");
        }
        engine.marks().setPinned(user, id.get(), pinned);
        return json.json(200, Map.of("pinned", pinned));
    }

    /**
     * 按内容搜当前用户的会话（session-find-pin R3）：{@code {"hits":[{"session","role","snippet"}],"more":bool}}。
     * 搜用户消息和助手回复的文字，不搜推理和工具；最多 {@value #SEARCH_LIMIT} 个，多出时 {@code more} 为 true。
     */
    @GetMapping("/api/sessions/search")
    ResponseEntity<String> search(@RequestParam(defaultValue = "") String q) throws SQLException {
        String query = q.strip();
        if (query.isEmpty() || query.codePointCount(0, query.length()) > MAX_QUERY || query.indexOf('\0') >= 0) {
            return json.error(ErrorCode.REQUEST_INVALID, "q must be 1 to " + MAX_QUERY + " characters");
        }
        List<SessionQueries.ContentHit> found = engine.queries().search(WebJson.currentUser(), query, SEARCH_LIMIT + 1);
        List<Map<String, Object>> hits = found.stream().limit(SEARCH_LIMIT).map(hit -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("session", hit.session().value());
            view.put("role", hit.role());
            view.put("snippet", hit.snippet());
            return view;
        }).toList();
        return json.json(200, Map.of("hits", hits, "more", found.size() > SEARCH_LIMIT));
    }
}
