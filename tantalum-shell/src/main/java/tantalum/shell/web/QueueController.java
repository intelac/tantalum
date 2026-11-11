package tantalum.shell.web;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.conversation.TranscriptItem.UserMessage;
import tantalum.shell.loop.Intake;
import tantalum.shell.store.SessionQueries;
import tantalum.value.event.QueueChanged;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;
import tantalum.value.state.Queue;
import tools.jackson.databind.JsonNode;

/**
 * 改排队的提问：换文字、移除、转成插话。页面用对话条目的 ID 指那一条；内核的队列没有编号，这里把它换成“位置加原文”
 * （第几条排队的、当时的文字和附件），由内核再确认一次还在不在。别人的会话和不存在的一样是 404。
 */
@RestController
public class QueueController {

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Map<String, QueueChanged.Action> ACTIONS = Map.of("edit", QueueChanged.Action.EDIT,
            "remove", QueueChanged.Action.REMOVE, "steer", QueueChanged.Action.STEER);

    private final Engine engine;
    private final WebJson json;

    QueueController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    /** body：{@code {"requestId": "...", "action": "edit|remove|steer", "text": "..."}}，{@code text} 只在 edit 时要。 */
    @PostMapping("/api/sessions/{session}/queue/{item}")
    ResponseEntity<String> change(@PathVariable String session, @PathVariable String item, @RequestBody String body) {
        Optional<SessionId> id = ApiController.sessionId(session);
        Optional<JsonNode> input = json.read(body);
        if (id.isEmpty() || input.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid session id or body");
        }
        String requestId = WebJson.text(input.get(), "requestId");
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            return json.error(ErrorCode.REQUEST_INVALID, "missing or invalid requestId");
        }
        QueueChanged.Action action = ACTIONS.get(String.valueOf(WebJson.text(input.get(), "action")));
        if (action == null) {
            return json.error(ErrorCode.REQUEST_INVALID, "action must be edit, remove or steer");
        }
        String text = action == QueueChanged.Action.EDIT ? WebJson.text(input.get(), "text") : null;
        if (action == QueueChanged.Action.EDIT && WebJson.blank(text)) {
            return json.error(ErrorCode.REQUEST_INVALID, "missing text");
        }
        Principal user = WebJson.currentUser();
        Optional<SessionQueries.SessionView> view = engine.queries().session(user, id.get());
        if (view.isEmpty()) {
            return json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
        }
        // 排队的条目和内核的队列顺序相同：它是第几条排队的，就在队列的第几个
        List<UserMessage> queued = view.get().transcript().items().stream()
                .filter(UserMessage.class::isInstance).map(UserMessage.class::cast).filter(UserMessage::queued).toList();
        int index = -1;
        for (int i = 0; i < queued.size(); i++) {
            if (queued.get(i).id().equals(item)) {
                index = i;
            }
        }
        if (index < 0) {
            return json.error(ErrorCode.QUEUE_GONE, "the message is no longer queued");
        }
        UserMessage message = queued.get(index);
        if (Queue.locate(view.get().state().followUps(), index, Message.user(message.text(), message.attachments())) < 0) {
            return json.error(ErrorCode.QUEUE_GONE, "the message is no longer queued");
        }
        if (action == QueueChanged.Action.EDIT && !message.attachments().isEmpty()) {
            return json.error(ErrorCode.QUEUE_ATTACHMENTS, "a queued message with attachments cannot be edited");
        }
        if (action == QueueChanged.Action.EDIT && !message.skills().isEmpty()) {
            return json.error(ErrorCode.QUEUE_SKILLS, "a queued message that names a skill cannot be edited");
        }
        Intake.Submission result = engine.intake().changeQueue(user, id.get(), new RequestId(requestId), action, index,
                message.text(), message.attachments(), text);
        if (result.status() == 404) {
            return json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
        }
        if (result.status() != 200) {
            return json.error(result.status(), ErrorCode.SUBMISSION_REJECTED, "queue change rejected");
        }
        int saved = ApiController.waitSaved(result, ApiController.SAVE_WAIT);
        if (saved != 200) {
            return json.error(saved, saved == 504 ? ErrorCode.SERVER_BUSY : ErrorCode.SERVER_SAVE_FAILED,
                    "the change was not saved; send again");
        }
        return json.json(200, Map.of("submissionId", result.submission().value()));
    }
}
