package tantalum.shell.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tantalum.kernel.compaction.Compactor;
import tantalum.shell.boot.Engine;
import tantalum.shell.store.SessionQueries;
import tantalum.value.id.SessionId;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;

/** 会话的上下文现在占了多少：只回数字，不回任何正文。状态栏的百分比和点开的明细用它。 */
@RestController
public class ContextController {

    private final Engine engine;
    private final WebJson json;

    ContextController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    /**
     * {@code {used, window?, limit?, parts: {system, tools, messages}}}。{@code used} 是压缩判断用的那个数（最近一次模型调用实测的，
     * 加之后新增消息的估算）；三项是估算按比例摊到 {@code used} 上的，加起来正好等于它。{@code window}、{@code limit}（压缩点）
     * 是这个会话上次组装上下文时定的，不知道窗口时没有。还没调过模型，或者模型没报用量：空对象 {@code {}}，页面不显示。
     */
    @GetMapping("/api/sessions/{session}/context")
    ResponseEntity<String> context(@PathVariable String session) {
        Optional<SessionId> id = ApiController.sessionId(session);
        if (id.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid session id");
        }
        Optional<SessionState> found = engine.queries().session(WebJson.currentUser(), id.get()).map(SessionQueries.SessionView::state);
        if (found.isEmpty()) {
            return json.error(ErrorCode.SESSION_NOT_FOUND, "no such session");
        }
        SessionState state = found.get();
        SessionContext context = state.context();
        Map<String, Object> view = new LinkedHashMap<>();
        Optional<Compactor.Occupancy> occupancy = context == null ? Optional.empty()
                : Compactor.occupancy(state, context.system(), context.tools());
        if (occupancy.isEmpty()) {
            return json.json(200, view);
        }
        view.put("used", occupancy.get().used());
        CompactionSettings settings = context.compaction();
        if (settings != null) {
            view.put("window", settings.window());
            view.put("limit", settings.threshold());
        }
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("system", occupancy.get().system());
        parts.put("tools", occupancy.get().tools());
        parts.put("messages", occupancy.get().messages());
        view.put("parts", parts);
        return json.json(200, view);
    }
}
