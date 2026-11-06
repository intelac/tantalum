package tantalum.shell.web;

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
import tantalum.shell.plugin.Question;
import tantalum.shell.question.OpenQuestions;
import tantalum.value.id.SessionId;
import tools.jackson.databind.JsonNode;

/**
 * 插件工作在对话页上问用户的问题（如工具审批）：取这个会话里还没回答的，回答一个。只看得到、只答得了自己的；
 * 别人的和已经不在的一样回 404。问题出现、撤下时 SSE 推一条 {@code question}，页面据此再取。
 */
@RestController
public class QuestionController {

    private final Engine engine;
    private final WebJson json;

    public QuestionController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    @GetMapping("/api/sessions/{session}/questions")
    ResponseEntity<String> open(@PathVariable String session) {
        Optional<SessionId> id = ApiController.sessionId(session);
        if (id.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "bad session id");
        }
        List<Map<String, Object>> questions = engine.questions().open(WebJson.currentUser(), id.get()).stream()
                .map(QuestionController::view).toList();
        return json.json(200, Map.of("questions", questions));
    }

    /** 请求体 {@code {"choice": "选项 id"}}。 */
    @PostMapping("/api/sessions/{session}/questions/{question}")
    ResponseEntity<String> answer(@PathVariable String session, @PathVariable String question, @RequestBody String body) {
        Optional<SessionId> id = ApiController.sessionId(session);
        Optional<JsonNode> parsed = json.read(body);
        String choice = parsed.map(node -> WebJson.text(node, "choice")).orElse(null);
        if (id.isEmpty() || WebJson.blank(choice)) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"choice\": \"...\"}");
        }
        OpenQuestions.Answer answer = engine.questions().answer(WebJson.currentUser(), id.get(), question, choice);
        return switch (answer) {
            case ACCEPTED -> json.json(200, Map.of("question", question, "choice", choice));
            case GONE -> json.error(ErrorCode.QUESTION_GONE, "the question no longer needs an answer");
            case NOT_A_CHOICE -> json.error(ErrorCode.REQUEST_INVALID, "no such choice: " + choice);
        };
    }

    private static Map<String, Object> view(Question question) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", question.id());
        view.put("title", question.title().wire());
        view.put("detail", question.detail().wire());
        view.put("choices", question.choices().stream().map(choice -> Map.of("id", choice.id(), "label", choice.label().wire())).toList());
        return view;
    }
}
