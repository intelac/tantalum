package tantalum.value.model;

import java.util.Objects;

/**
 * 一次提问用哪个模型：路线名、模型名、思考档位。{@code effort} 为 null 表示这个模型没有档位。
 * 只是标识，不含地址和密钥；画面上的显示名由 shell 按路线的模型列表给出。
 */
public record ModelChoice(String route, String model, String effort) {

    public ModelChoice {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(model, "model");
        if (route.isBlank() || model.isBlank()) {
            throw new IllegalArgumentException("a model choice names a route and a model");
        }
    }

    /** 日志和换模型的说明里用：{@code 路线/模型}，写进 journal 时就定下，回放时一字不变。 */
    public String label() {
        return route + "/" + model;
    }

    /** 路线和模型都相同；只换档位不算换模型。 */
    public boolean sameModel(ModelChoice other) {
        return other != null && route.equals(other.route) && model.equals(other.model);
    }
}
