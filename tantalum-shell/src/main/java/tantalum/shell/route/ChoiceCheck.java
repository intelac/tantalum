package tantalum.shell.route;

import java.util.Map;
import java.util.Optional;
import tantalum.value.model.ModelChoice;

/**
 * 检查一次选择能不能用（model-picker M14、M15、M11），写明是路线、模型还是档位的问题。只读内存里的列表，
 * 不调接口。档位不就近换：不是这个模型列出来的就拒绝（M5，照 DSH {@code packages/llm/llm/src/index.ts:888-946}）。
 * 问题是种类加参数，由控制台按看的人的语言说（console-i18n I9）；诊断是英文，给日志和开发者看。
 */
public final class ChoiceCheck {

    /** 一次选择的问题。参数只有路线名、模型名和档位 ID。 */
    public record Problem(Kind kind, String diagnostic, Map<String, Object> params) {
        public enum Kind { ROUTE_UNAVAILABLE, MODEL_NOT_OFFERED, NO_LEVELS, LEVEL_REQUIRED, LEVEL_UNKNOWN, TOOL_CHANGES, NO_IMAGES }
    }

    private final Routes routes;

    public ChoiceCheck(Routes routes) {
        this.routes = routes;
    }

    /**
     * 这次选择的问题；能用时为空。{@code toolChangesInHistory} 是这个会话有没有工具变化（不支持的 Claude 模型不能选），
     * {@code hasImages} 是这次提交带不带图片。
     */
    public Optional<Problem> problem(ModelChoice choice, boolean toolChangesInHistory, boolean hasImages) {
        Optional<Route> route = routes.route(choice.route());
        if (route.isEmpty() || route.get().listing().status() == Listing.Status.UNAVAILABLE) {
            return problem(Problem.Kind.ROUTE_UNAVAILABLE, "model route " + choice.route() + " is not available now",
                    Map.of("route", choice.route()));
        }
        Optional<ModelInfo> found = route.get().listing().find(choice.model());
        if (found.isEmpty()) {
            return problem(Problem.Kind.MODEL_NOT_OFFERED, "model " + choice.model() + " is not offered now", Map.of("model", choice.model()));
        }
        ModelInfo model = found.get();
        Levels levels = model.levels();
        if (levels.isEmpty() && choice.effort() != null) {
            return problem(Problem.Kind.NO_LEVELS, model.name() + " has no thinking levels", Map.of("model", model.name()));
        }
        if (!levels.isEmpty() && choice.effort() == null) {
            return problem(Problem.Kind.LEVEL_REQUIRED, model.name() + " needs a thinking level, one of " + levels.names(),
                    Map.of("model", model.name(), "levels", levels.names()));
        }
        if (!levels.isEmpty() && !levels.names().contains(choice.effort())) {
            return problem(Problem.Kind.LEVEL_UNKNOWN, model.name() + " has no level " + choice.effort() + "; one of " + levels.names(),
                    Map.of("model", model.name(), "level", choice.effort(), "levels", levels.names()));
        }
        if (toolChangesInHistory && !model.toolChanges()) {
            return problem(Problem.Kind.TOOL_CHANGES, "tools changed in this session and " + model.name() + " does not support that",
                    Map.of("model", model.name()));
        }
        if (hasImages && !model.vision()) {
            return noImages();
        }
        return Optional.empty();
    }

    /** 这次会用的模型不收图片。 */
    public static Optional<Problem> noImages() {
        return problem(Problem.Kind.NO_IMAGES, "the chosen model does not accept images", Map.of());
    }

    private static Optional<Problem> problem(Problem.Kind kind, String diagnostic, Map<String, Object> params) {
        return Optional.of(new Problem(kind, diagnostic, params));
    }

    /**
     * 提交里没带选择时用哪个（M7、M14）：会话现在的能用就用它，否则这个用户上次的选择，也不能用就是管理员的默认。
     */
    public ModelChoice fallback(ModelChoice current, Optional<ModelChoice> mine) {
        if (current != null && usable(current)) {
            return current;
        }
        return mine.filter(this::usable).orElseGet(routes::defaultChoice);
    }

    /** 能不能拿来当新会话的默认（“上次的选择”，M7）。 */
    public boolean usable(ModelChoice choice) {
        return problem(choice, false, false).isEmpty();
    }
}
