package tantalum.shell.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.loop.Intake;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.question.Answer;
import tantalum.value.question.Question;
import tantalum.value.state.AwaitingAnswer;
import tools.jackson.databind.JsonNode;

/**
 * 运行停下来问用户的问题（waiting-state 需求 6）：取这个会话正在等回答的问题（0 或 1 个），回答一个。
 * 只看得到、只答得了自己的；别人的和不存在的一样 404。回答和提交一样经 Intake 落盘后才回 200。
 */
@RestController
public class QuestionController {

    /** 等落盘的时间，同提交。 */
    private static final Duration SAVE_WAIT = Duration.ofSeconds(10);

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
        List<Map<String, Object>> questions = engine.queries().openQuestion(WebJson.currentUser(), id.get())
                .map(waiting -> List.of(view(waiting))).orElse(List.of());
        return json.json(200, Map.of("questions", questions));
    }

    /** 请求体 {@code {"choice": "选项 id", "text": "补充的文字"}}，至少一个。 */
    @PostMapping("/api/sessions/{session}/questions/{question}")
    ResponseEntity<String> answer(@PathVariable String session, @PathVariable String question, @RequestBody String body) {
        Optional<SessionId> id = ApiController.sessionId(session);
        Optional<JsonNode> parsed = json.read(body);
        if (id.isEmpty() || parsed.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"choice\": \"...\"} and/or {\"text\": \"...\"}");
        }
        String choice = WebJson.text(parsed.get(), "choice");
        String text = WebJson.text(parsed.get(), "text");
        if (WebJson.blank(choice) && WebJson.blank(text)) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must have a choice or text");
        }
        if (text != null && text.length() > Question.MAX_TEXT) {
            return json.error(ErrorCode.REQUEST_INVALID, "text must be at most " + Question.MAX_TEXT + " characters");
        }
        Optional<AwaitingAnswer> waiting = engine.queries().openQuestion(WebJson.currentUser(), id.get());
        if (waiting.isEmpty() || !waiting.get().question().id().equals(question)) {
            return json.error(ErrorCode.QUESTION_GONE, "the question no longer needs an answer");
        }
        Answer answer = new Answer(WebJson.blank(choice) ? null : choice, text);
        if (!waiting.get().question().accepts(answer)) {
            return json.error(ErrorCode.REQUEST_INVALID, WebJson.blank(choice) || waiting.get().question().offers(choice)
                    ? "this question does not take free text" : "no such choice: " + choice);
        }
        Intake.Submission result = engine.intake().answer(WebJson.currentUser(), id.get(), new RequestId(UUID.randomUUID().toString()),
                question, answer);
        return switch (result.status()) {
            case 200 -> accepted(result, question, answer);
            case 404 -> json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
            case 409 -> json.error(ErrorCode.QUESTION_GONE, "the question no longer needs an answer");
            case 503 -> json.error(ErrorCode.SESSION_UNAVAILABLE, "the server has stopped processing events");
            default -> json.error(ErrorCode.SERVER_BUSY, "the server is busy; send again");
        };
    }

    private ResponseEntity<String> accepted(Intake.Submission result, String question, Answer answer) {
        int saved = ApiController.waitSaved(result, SAVE_WAIT);
        if (saved == 504) {
            return json.error(ErrorCode.SERVER_BUSY, "the server is busy; send again");
        }
        if (saved == 500) {
            return json.error(ErrorCode.SERVER_SAVE_FAILED, "the answer was not saved; send again");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", question);
        response.put("choice", answer.choice());
        response.put("text", answer.text());
        return json.json(200, response);
    }

    static Map<String, Object> view(AwaitingAnswer waiting) {
        Question question = waiting.question();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", question.id());
        view.put("title", question.title().wire());
        view.put("detail", question.detail().wire());
        view.put("choices", question.choices().stream().map(choice -> Map.of("id", choice.id(), "label", choice.label().wire())).toList());
        view.put("freeText", question.freeText());
        view.put("deadline", question.deadline() == null ? null : question.deadline().toString());
        view.put("askedBy", waiting.fromTool() ? "ask_user" : "plugin");
        return view;
    }
}
