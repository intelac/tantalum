package tantalum.shell.web;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.boot.Engine;
import tantalum.shell.boot.TantalumProperties;
import tantalum.shell.conversation.ItemJson;
import tantalum.shell.conversation.MessagePage;
import tantalum.shell.conversation.SessionSummary;
import tantalum.value.state.AwaitingAnswer;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.loop.Intake;
import tantalum.shell.route.ChoiceCheck;
import tantalum.shell.route.ModelInfo;
import tantalum.shell.route.Route;
import tantalum.shell.route.RouteSettings;
import tantalum.shell.store.SessionQueries;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.ModelChoice;
import tantalum.value.state.Ended;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tools.jackson.databind.JsonNode;

/** 会话、提交、压缩、取消和配置。只校验和转发，不等待模型结果。回放视图在 {@link RunTraceController}，推送在 {@link EventStreamController}。 */
@RestController
public class ApiController {

    private static final Logger LOG = LoggerFactory.getLogger(ApiController.class);
    /** 提交最多等多久落盘。冷会话载入、磁盘慢时会接近它。 */
    static final Duration SAVE_WAIT = Duration.ofSeconds(10);

    private static final Set<String> MODES = Set.of("prompt", "steer", "follow-up");
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final Engine engine;
    private final TantalumProperties properties;
    private final WebJson json;

    public ApiController(Engine engine, TantalumProperties properties, WebJson json) {
        this.engine = engine;
        this.properties = properties;
        this.json = json;
    }

    /** 当前用户的会话，最近活动的在前。 */
    @GetMapping("/api/sessions")
    ResponseEntity<String> sessions() throws SQLException {
        List<Map<String, Object>> sessions = engine.queries().sessions(WebJson.currentUser()).stream().map(ApiController::describe).toList();
        return json.json(200, Map.of("sessions", sessions));
    }

    /**
     * 一页条目和排队数，默认最近一页；{@code before} 是上一页的 {@code start}。排队中的提交也是条目，{@code queued} 为 true。
     * {@code seq} 是整个会话截至的事件序号，客户端据此和 SSE 推送合并。
     */
    @GetMapping("/api/sessions/{session}/messages")
    ResponseEntity<String> messages(@PathVariable String session, @RequestParam(required = false) Integer before) {
        Optional<SessionId> id = sessionId(session);
        if (id.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid session id");
        }
        if (before != null && before < 0) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid page position");
        }
        Optional<SessionQueries.SessionView> found = engine.queries().session(WebJson.currentUser(), id.get());
        if (found.isEmpty()) {
            return json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
        }
        Transcript.View view = found.get().transcript();
        MessagePage page = MessagePage.of(view.items(), before);
        List<Map<String, Object>> items = page.items().stream()
                .map(item -> ItemJson.toMap(engine.codec().mapper(), item)).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("seq", view.seq());
        response.put("items", items);
        response.put("start", page.start());
        response.put("hasMore", page.hasMore());
        response.put("followUps", found.get().state().followUps().size());
        // 这个会话现在的选择（本 spec 之前的会话为 null）和有没有工具变化（不支持的 Claude 模型灰掉，M11）
        SessionState state = found.get().state();
        response.put("model", state == null ? null : ModelJson.view(state.model()));
        response.put("toolChangesInHistory", state != null && state.toolChangesInHistory());
        return json.json(200, response);
    }

    @ExceptionHandler(SessionQueries.SessionUnreadable.class)
    ResponseEntity<String> unreadable(SessionQueries.SessionUnreadable failure) {
        LOG.error("session unreadable", failure);
        return json.error(ErrorCode.SESSION_UNREADABLE, "session unreadable");
    }

    /**
     * 供应商、模型名、是否云端、上下文窗口和它从哪来（模型报告或配置；不知道时省略，前端就不显示占比）、是否收图片和附件的各项上限（前端先查，服务器再查）。
     * Key 只在环境变量里，这里碰不到。
     */
    @GetMapping("/api/config")
    ResponseEntity<String> config() {
        Map<String, Object> response = new LinkedHashMap<>();
        // 管理员的默认选择，兼容旧画面（model-picker 之前只有一个模型）；控制台按会话的选择显示模型，不再用这几项
        ModelChoice choice = engine.routes().defaultChoice();
        Route route = engine.routes().route(choice.route()).orElseThrow();
        Optional<ModelInfo> model = engine.routes().describe(choice);
        response.put("provider", route.settings().kind() == RouteSettings.Kind.CLAUDE ? "claude" : "openai");
        response.put("model", choice.model());
        response.put("cloud", route.settings().cloud());
        if (model.isPresent() && model.get().window() > 0) {
            response.put("contextWindow", model.get().window());
            response.put("windowSource", model.get().windowSource() == ModelInfo.Source.MODEL ? "model" : "config");
        }
        response.put("vision", model.map(ModelInfo::vision).orElse(false));
        AttachmentLimits limits = properties.attachmentLimits();
        Map<String, Object> attachments = new LinkedHashMap<>();
        attachments.put("maxImagesPerMessage", limits.maxImagesPerMessage());
        attachments.put("maxImageBytesPerMessage", limits.maxImageBytesPerMessage());
        attachments.put("maxImageBytes", limits.maxImageBytes());
        attachments.put("maxFilesPerMessage", limits.maxFilesPerMessage());
        attachments.put("maxFileBytes", limits.maxFileBytes());
        response.put("attachments", attachments);
        return json.json(200, response);
    }

    @PostMapping("/api/sessions/{session}/submissions")
    ResponseEntity<String> submit(@PathVariable String session, @RequestBody String body) {
        Optional<SessionId> id = sessionId(session);
        if (id.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid session id");
        }
        Optional<JsonNode> request = json.read(body);
        if (request.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body is not valid JSON");
        }
        String requestId = WebJson.text(request.get(), "requestId");
        String mode = WebJson.text(request.get(), "mode");
        String content = WebJson.text(request.get(), "text");
        if (WebJson.blank(requestId)) {
            return json.error(ErrorCode.REQUEST_INVALID, "missing requestId");
        }
        if (mode == null || !MODES.contains(mode)) {
            return json.error(ErrorCode.REQUEST_INVALID, "mode must be one of " + MODES);
        }
        Principal user = WebJson.currentUser();
        List<AttachmentRef> attachments;
        try {
            attachments = attachments(user, request.get().get("attachments"));
        } catch (MissingAttachment missing) {
            return json.error(400, ErrorCode.ATTACHMENT_NOT_FOUND, missing.getMessage());
        } catch (IllegalArgumentException invalid) {
            return json.error(ErrorCode.REQUEST_INVALID, invalid.getMessage());
        } catch (IOException failure) {
            LOG.warn("attachment lookup failed: {}", failure.toString());
            return json.error(ErrorCode.ATTACHMENT_READ_FAILED, "the attachment could not be read");
        }
        if (WebJson.blank(content) && attachments.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "missing text");
        }
        // 插话并进当前运行，不换模型（M8）：带了也不用
        ModelChoice model;
        try {
            model = mode.equals("steer") ? null : ModelJson.read(request.get().get("model"));
        } catch (IllegalArgumentException invalid) {
            return json.error(ErrorCode.REQUEST_INVALID, invalid.getMessage());
        }
        Optional<AttachmentLimits.Violation> violation = properties.attachmentLimits().violation(attachments);
        if (violation.isPresent()) {
            return json.error(ErrorCode.of(violation.get().kind()), violation.get().diagnostic(), violation.get().params());
        }
        boolean images = attachments.stream().anyMatch(ImageRef.class::isInstance);
        Optional<ChoiceCheck.Problem> problem = problem(user, id.get(), model, images);
        if (problem.isPresent()) {
            return json.error(ErrorCode.of(problem.get().kind()), problem.get().diagnostic(), problem.get().params());
        }
        Intake.Submission result = engine.intake().submit(user, id.get(), new RequestId(requestId), mode,
                content == null ? "" : content, attachments, model);
        return result.status() == 200 ? accepted(result, SAVE_WAIT) : json.error(result.status(), ErrorCode.SUBMISSION_REJECTED, "submission rejected");
    }

    /**
     * 提交里的选择能不能用（model-picker M14、M15、M11），不合格的不进 journal。没带选择时只查图片：按这次会用的模型
     * （会话现在的 → 上次的选择 → 默认），和组装上下文时的取法一样。
     */
    private Optional<ChoiceCheck.Problem> problem(Principal user, SessionId session, ModelChoice model, boolean images) {
        ChoiceCheck check = new ChoiceCheck(engine.routes());
        Optional<SessionState> state = engine.queries().session(user, session).map(SessionQueries.SessionView::state);
        if (model != null) {
            return check.problem(model, state.map(SessionState::toolChangesInHistory).orElse(false), images);
        }
        if (!images) {
            return Optional.empty();
        }
        ModelChoice used = check.fallback(state.map(SessionState::model).orElse(null), engine.userSettings().modelChoice(user));
        return engine.routes().describe(used).map(ModelInfo::vision).orElse(false) ? Optional.empty() : ChoiceCheck.noImages();
    }

    /**
     * 请求体里的 {@code attachments: [{id, name}]}。每个都在本人目录里查元数据组成引用，客户端报的尺寸和大小一概不用（设计第 2 节）。
     * 查不到的和别人的一样：400，代码 {@code attachment/not-found}。
     */
    private List<AttachmentRef> attachments(Principal user, JsonNode field) throws IOException {
        if (field == null || field.isNull()) {
            return List.of();
        }
        if (!field.isArray()) {
            throw new IllegalArgumentException("attachments must be an array");
        }
        List<AttachmentRef> refs = new ArrayList<>();
        for (JsonNode item : field) {
            String attachment = item.isObject() ? WebJson.text(item, "id") : null;
            if (attachment == null) {
                throw new IllegalArgumentException("each attachment needs an id");
            }
            refs.add(engine.attachments().ref(user, attachment, WebJson.text(item, "name"))
                    .orElseThrow(() -> new MissingAttachment(attachment)));
        }
        return refs;
    }

    /** 附件查不到，或者不是这个用户的。 */
    private static final class MissingAttachment extends IllegalArgumentException {
        MissingAttachment(String id) {
            super("no such attachment: " + id);
        }
    }

    /** 手动压缩。body 可为空或 {@code {"requestId": "...", "instructions": "..."}}；没给 requestId 时每次都是新请求。 */
    @PostMapping("/api/sessions/{session}/compact")
    ResponseEntity<String> compact(@PathVariable String session, @RequestBody(required = false) String body) {
        Optional<SessionId> id = sessionId(session);
        if (id.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid session id");
        }
        JsonNode request = null;
        if (!WebJson.blank(body)) {
            Optional<JsonNode> parsed = json.read(body);
            if (parsed.isEmpty()) {
                return json.error(ErrorCode.REQUEST_INVALID, "body is not valid JSON");
            }
            request = parsed.get();
        }
        String requestId = request == null ? null : WebJson.text(request, "requestId");
        String instructions = request == null ? null : WebJson.text(request, "instructions");
        Intake.Submission result = engine.intake().compact(WebJson.currentUser(), id.get(),
                new RequestId(WebJson.blank(requestId) ? UUID.randomUUID().toString() : requestId),
                WebJson.blank(instructions) ? null : instructions);
        return switch (result.status()) {
            case 200 -> accepted(result, SAVE_WAIT);
            case 404 -> json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
            case 409 -> json.error(ErrorCode.SESSION_BUSY, "session is running or has nothing to compact");
            default -> json.error(result.status(), ErrorCode.SESSION_BUSY, "compaction rejected");
        };
    }

    @PostMapping("/api/runs/{run}/cancel")
    ResponseEntity<String> cancel(@PathVariable String run) {
        Principal user = WebJson.currentUser();
        Optional<RunState> found = engine.queries().run(user, new RunId(run));
        if (found.isEmpty()) {
            return json.error(ErrorCode.RUN_NOT_FOUND, "no such run");
        }
        int status = engine.intake().cancel(user, found.get().ref());
        return status == 200 ? json.json(200, Map.of("cancelled", true)) : json.error(status, ErrorCode.RUN_CANCEL_REJECTED, "cancel rejected");
    }

    @GetMapping("/api/runs/{run}")
    ResponseEntity<String> run(@PathVariable String run) {
        return engine.queries().run(WebJson.currentUser(), new RunId(run))
                .map(found -> json.json(200, describe(found)))
                .orElseGet(() -> json.error(ErrorCode.RUN_NOT_FOUND, "no such run"));
    }

    @GetMapping("/api/sessions/{session}/runs")
    ResponseEntity<String> runs(@PathVariable String session) {
        Optional<SessionId> id = sessionId(session);
        if (id.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid session id");
        }
        Optional<SessionQueries.SessionView> found = engine.queries().session(WebJson.currentUser(), id.get());
        if (found.isEmpty()) {
            return json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
        }
        List<Map<String, Object>> runs = found.get().runs().stream()
                .sorted(Comparator.comparingLong(run -> run.ref().run().seq()))
                .map(ApiController::describe).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runs", runs);
        response.put("followUps", found.get().state().followUps().size());
        return json.json(200, response);
    }

    /** 会话 ID 只允许字母、数字、下划线和连字符：它会成为文件名的一部分。 */
    static Optional<SessionId> sessionId(String raw) {
        return SESSION_ID.matcher(raw).matches() ? Optional.of(new SessionId(raw)) : Optional.empty();
    }

    /**
     * 等提交那一步落盘再回“已接受”（one-line-journal J4）：写不进去回 500，{@code wait} 内没写完回 504。
     * 不用 503：控制台把 503 当作“服务已停止处理事件”。
     * 超时之后如果还是写进去了，用同一个 requestId 重发会按重复提交处理，不会处理两次。
     */
    private ResponseEntity<String> accepted(Intake.Submission result, Duration wait) {
        int saved = waitSaved(result, wait);
        if (saved == 504) {
            return json.error(ErrorCode.SERVER_BUSY, "the server is busy; send again");
        }
        if (saved == 500) {
            return json.error(ErrorCode.SERVER_SAVE_FAILED, "the submission was not saved; send again");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("submissionId", result.submission().value());
        response.put("duplicate", result.duplicate());
        return json.json(200, response);
    }

    /** 200：已落盘；500：没写进去；504：{@code wait} 内没写完。 */
    static int waitSaved(Intake.Submission result, Duration wait) {
        try {
            result.saved().get(wait.toMillis(), TimeUnit.MILLISECONDS);
            return 200;
        } catch (TimeoutException slow) {
            return 504;
        } catch (ExecutionException notSaved) {
            LOG.warn("submission {} was not saved: {}", result.submission().value(), notSaved.getCause().toString());
            return 500;
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            return 504;
        }
    }

    private static Map<String, Object> describe(SessionSummary summary) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", summary.id().value());
        view.put("title", summary.title());
        view.put("lastActivity", summary.lastActivity().toString());
        view.put("runStatus", summary.runStatus());
        // 侧栏的“待回答”标记（waiting-state 需求 7.3）：从索引里的阶段名来，不载入会话
        view.put("awaitingAnswer", AwaitingAnswer.NAME.equals(summary.runStatus()));
        return view;
    }

    private static Map<String, Object> describe(RunState run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("run", run.ref().run().value());
        view.put("session", run.ref().session().value());
        view.put("phase", run.phase().name());
        if (run.phase() instanceof Ended ended) {
            view.put("endReason", ended.reason().name());
            view.put("detail", ended.detail());
        }
        return view;
    }
}
