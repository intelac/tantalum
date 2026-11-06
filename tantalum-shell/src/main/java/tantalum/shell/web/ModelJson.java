package tantalum.shell.web;

import java.util.LinkedHashMap;
import java.util.Map;
import tantalum.value.model.ModelChoice;
import tools.jackson.databind.JsonNode;

/** 接口里的模型选择 {@code {"route", "model", "effort"}}（model-picker 设计“数据模型”）：{@code effort} 为 null 表示这个模型没有档位。 */
final class ModelJson {

    private ModelJson() {}

    /** 没有这一项时为 null；有但写得不对时抛 {@link IllegalArgumentException}，消息直接给画面。 */
    static ModelChoice read(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String route = node.path("route").isString() ? node.path("route").asString() : null;
        String model = node.path("model").isString() ? node.path("model").asString() : null;
        JsonNode effort = node.path("effort");
        if (!node.isObject() || route == null || model == null || route.isBlank() || model.isBlank()
                || !(effort.isMissingNode() || effort.isNull() || effort.isString())) {
            throw new IllegalArgumentException("model 要写成 {\"route\": …, \"model\": …, \"effort\": …}");
        }
        return new ModelChoice(route, model, effort.isString() ? effort.asString() : null);
    }

    static Map<String, Object> view(ModelChoice choice) {
        if (choice == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("route", choice.route());
        view.put("model", choice.model());
        view.put("effort", choice.effort());
        return view;
    }
}
