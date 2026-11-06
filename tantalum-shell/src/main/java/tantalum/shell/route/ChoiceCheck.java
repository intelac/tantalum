package tantalum.shell.route;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import tantalum.value.model.ModelChoice;

/**
 * 检查一次选择能不能用（model-picker M14、M15、M11），消息直接给画面，写明是路线、模型还是档位的问题。只读内存里的列表，
 * 不调接口。档位不就近换：不是这个模型列出来的就拒绝（M5，照 DSH {@code packages/llm/llm/src/index.ts:888-946}）。
 */
public final class ChoiceCheck {

    /** 档位的中文名，和控制台 {@code zh.ts} 的一致；不认识的照原样。 */
    private static final Map<String, String> LEVEL_NAMES = Map.of("low", "低", "medium", "中", "high", "高", "xhigh", "很高",
            "max", "最高", "minimal", "极低", "off", "关", "on", "开");

    private final Routes routes;

    public ChoiceCheck(Routes routes) {
        this.routes = routes;
    }

    /**
     * 这次选择的问题；能用时为空。{@code toolChangesInHistory} 是这个会话有没有工具变化（不支持的 Claude 模型不能选），
     * {@code hasImages} 是这次提交带不带图片。
     */
    public Optional<String> problem(ModelChoice choice, boolean toolChangesInHistory, boolean hasImages) {
        Optional<Route> route = routes.route(choice.route());
        if (route.isEmpty() || route.get().listing().status() == Listing.Status.UNAVAILABLE) {
            return Optional.of("模型路线 " + choice.route() + " 现在不可用，请换一个模型");
        }
        Optional<ModelInfo> found = route.get().listing().find(choice.model());
        if (found.isEmpty()) {
            return Optional.of("模型 " + choice.model() + " 现在不提供，请换一个");
        }
        ModelInfo model = found.get();
        Levels levels = model.levels();
        if (levels.isEmpty() && choice.effort() != null) {
            return Optional.of(model.name() + " 没有思考档位");
        }
        if (!levels.isEmpty() && choice.effort() == null) {
            return Optional.of(model.name() + " 要选一个思考档位，可选：" + names(levels));
        }
        if (!levels.isEmpty() && !levels.names().contains(choice.effort())) {
            return Optional.of(model.name() + " 没有“" + name(choice.effort()) + "”这一档，可选：" + names(levels));
        }
        if (toolChangesInHistory && !model.toolChanges()) {
            return Optional.of("这个会话中途改过工具，" + model.name() + " 不支持，请换一个模型");
        }
        if (hasImages && !model.vision()) {
            return Optional.of("当前选的模型不支持图片");
        }
        return Optional.empty();
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

    static String name(String level) {
        return LEVEL_NAMES.getOrDefault(level, level);
    }

    private static String names(Levels levels) {
        return levels.names().stream().map(ChoiceCheck::name).collect(Collectors.joining("、"));
    }
}
