package tantalum.shell.web;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.loop.Intake;
import tantalum.shell.store.SessionQueries;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.state.SessionState;
import tools.jackson.databind.JsonNode;

/** 会话的名字和归档。改名进账（{@code SessionRenamed}），归档不进账。别人的会话和不存在的一样是 404。 */
@RestController
public class SessionController {

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
        engine.archive().set(user, id.get(), archived);
        return json.json(200, Map.of("archived", archived));
    }
}
