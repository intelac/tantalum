package tantalum.shell.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.route.ChoiceCheck;
import tantalum.shell.route.Listing;
import tantalum.shell.route.ModelInfo;
import tantalum.shell.route.Route;
import tantalum.shell.route.RouteSettings;
import tantalum.shell.settings.CompactionPolicy;
import tantalum.value.id.Principal;

/**
 * 能选的模型：按路线列出，每个模型带窗口和来源、输出上限、图片、档位，以及这个用户的压缩点在这个模型上
 * 是多少 token。只读内存里的列表，不调模型接口。不返回路线地址和存密钥的环境变量名。
 */
@RestController
public class ModelController {

    private final Engine engine;
    private final WebJson json;

    public ModelController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    /** {@code mine} 是这个用户上次的选择，现在不可用时为 null（画面上用 {@code default}）。 */
    @GetMapping("/api/models")
    ResponseEntity<String> models() {
        Principal user = WebJson.currentUser();
        CompactionPolicy policy = engine.compaction();
        int percent = engine.userSettings().compactionPercent(user);
        List<Map<String, Object>> routes = engine.routes().all().stream().map(route -> route(route, policy)).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("routes", routes);
        response.put("default", ModelJson.view(engine.routes().defaultChoice()));
        ChoiceCheck check = new ChoiceCheck(engine.routes());
        response.put("mine", ModelJson.view(engine.userSettings().modelChoice(user).filter(check::usable).orElse(null)));
        response.put("percent", percent);
        return json.json(200, response);
    }

    private static Map<String, Object> route(Route route, CompactionPolicy policy) {
        Listing listing = route.listing();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", route.name());
        view.put("displayName", route.settings().displayName());
        view.put("kind", route.settings().kind() == RouteSettings.Kind.CLAUDE ? "claude" : "openai");
        view.put("cloud", route.settings().cloud());
        view.put("status", listing.status().name().toLowerCase());
        view.put("error", listing.error());
        view.put("models", listing.models().stream().map(model -> model(model, policy)).toList());
        return view;
    }

    private static Map<String, Object> model(ModelInfo model, CompactionPolicy policy) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", model.id());
        view.put("name", model.name());
        view.put("window", model.window());
        view.put("windowSource", model.windowSource() == ModelInfo.Source.MODEL ? "model" : "config");
        view.put("maxTokens", model.maxTokens());
        view.put("vision", model.vision());
        view.put("levels", model.levels().names());
        view.put("defaultLevel", model.levels().defaultLevel());
        view.put("toolChanges", model.toolChanges());
        view.put("points", policy.points(model).stream().map(ModelController::point).toList());
        return view;
    }

    private static Map<String, Object> point(CompactionPolicy.Point point) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("percent", point.percent());
        view.put("tokens", point.tokens());
        return view;
    }
}
