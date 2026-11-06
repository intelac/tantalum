package tantalum.shell.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.route.ChoiceCheck;
import tantalum.shell.settings.CompactionPolicy;
import tantalum.value.id.Principal;
import tantalum.value.model.ModelChoice;
import tools.jackson.databind.JsonNode;

/** 每个用户自己的设置（compaction-threshold 需求 2）：只读写当前登录用户的，不接受用户名参数。 */
@RestController
public class SettingsController {

    private final Engine engine;
    private final WebJson json;

    public SettingsController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    /** {@code {compaction: {percent, points: [{percent, tokens}]}}}；窗口未知时 {@code points} 为空。 */
    @GetMapping("/api/settings")
    ResponseEntity<String> get() {
        return json.json(200, view(WebJson.currentUser()));
    }

    /** 请求体 {@code {"percent": 70}}，只能是 50、70、85、100 之一，否则 400。 */
    @PutMapping("/api/settings/compaction")
    ResponseEntity<String> setCompaction(@RequestBody String body) {
        Optional<JsonNode> parsed = json.read(body);
        if (parsed.isEmpty() || !parsed.get().path("percent").isInt()) {
            return json.error(400, "body must be {\"percent\": 50, 70, 85 or 100}");
        }
        Principal user = WebJson.currentUser();
        try {
            engine.userSettings().setCompactionPercent(user, parsed.get().path("percent").asInt());
        } catch (IllegalArgumentException refused) {
            return json.error(400, refused.getMessage());
        }
        return json.json(200, view(user));
    }

    /**
     * 请求体是一个模型选择 {@code {"route", "model", "effort"}}，存成这个用户“上次的选择”（model-picker M7），只改自己的；
     * 现在不可用的回 400，消息写明是路线、模型还是档位的问题。
     */
    @PutMapping("/api/settings/model")
    ResponseEntity<String> setModel(@RequestBody String body) {
        Optional<JsonNode> parsed = json.read(body);
        ModelChoice choice;
        try {
            choice = parsed.isEmpty() ? null : ModelJson.read(parsed.get());
        } catch (IllegalArgumentException invalid) {
            return json.error(400, invalid.getMessage());
        }
        if (choice == null) {
            return json.error(400, "body must be {\"route\": …, \"model\": …, \"effort\": …}");
        }
        Optional<String> problem = new ChoiceCheck(engine.routes()).problem(choice, false, false);
        if (problem.isPresent()) {
            return json.error(400, problem.get());
        }
        Principal user = WebJson.currentUser();
        engine.userSettings().setModelChoice(user, choice);
        return json.json(200, view(user));
    }

    private Map<String, Object> view(Principal user) {
        CompactionPolicy policy = engine.compaction();
        Map<String, Object> compaction = new LinkedHashMap<>();
        compaction.put("percent", engine.userSettings().compactionPercent(user));
        // 管理员默认模型上的压缩点；按会话的模型算的在 /api/models 里（model-picker M10）
        List<CompactionPolicy.Point> points = engine.routes().describe(engine.routes().defaultChoice()).map(policy::points).orElse(List.of());
        compaction.put("points", points.stream().map(SettingsController::point).toList());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("compaction", compaction);
        ChoiceCheck check = new ChoiceCheck(engine.routes());
        view.put("model", ModelJson.view(engine.userSettings().modelChoice(user).filter(check::usable).orElse(null)));
        return view;
    }

    private static Map<String, Object> point(CompactionPolicy.Point point) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("percent", point.percent());
        view.put("tokens", point.tokens());
        return view;
    }
}
