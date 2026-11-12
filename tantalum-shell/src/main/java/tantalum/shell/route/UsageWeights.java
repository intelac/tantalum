package tantalum.shell.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tantalum.value.model.TokenUsage;

/**
 * 折算系数（qagent-align 需求 3）：一条路线四个系数——输入、输出、缓存读、缓存写，没写的是 1；路线里某个模型可以另写一套，
 * 没写的项用路线的。贵的模型多算、便宜的少算，折算后的量算进今日用量和额度。只在模型回复进会话文件之前用一次，
 * 结果和用量一起记下，之后改系数不影响已经记下的数。
 */
public final class UsageWeights {

    /** 四个系数。 */
    public record Four(double input, double output, double cacheRead, double cacheWrite) {

        public static final Four ONE = new Four(1, 1, 1, 1);

        public Four {
            if (!(input >= 0 && output >= 0 && cacheRead >= 0 && cacheWrite >= 0)
                    || Double.isInfinite(input + output + cacheRead + cacheWrite)) {
                throw new IllegalArgumentException("weights must be finite and not negative");
            }
        }

        public boolean plain() {
            return equals(ONE);
        }
    }

    /** 给运维页看的一行；{@code model} 为 null 是整条路线的。 */
    public record Row(String route, String model, Four weights) {}

    public static final UsageWeights NONE = new UsageWeights(Map.of(), Map.of());

    private final Map<String, Four> routes;
    private final Map<String, Map<String, Four>> models;

    /** {@code models} 里的每一套已经把没写的项补成了路线的。 */
    public UsageWeights(Map<String, Four> routes, Map<String, Map<String, Four>> models) {
        this.routes = new TreeMap<>(routes);
        Map<String, Map<String, Four>> copy = new TreeMap<>();
        models.forEach((route, each) -> copy.put(route, new TreeMap<>(each)));
        this.models = copy;
    }

    /** 先按 {@code models} 的顺序找单独写了的模型（实际用的在前、请求的在后），找不到用路线的，再没有就是 1。 */
    public Four of(String route, String... candidates) {
        if (route == null) {
            // 只有一个客户端、没有路线名（测试）：不折算
            return Four.ONE;
        }
        Map<String, Four> byModel = models.getOrDefault(route, Map.of());
        for (String model : candidates) {
            if (model != null && byModel.containsKey(model)) {
                return byModel.get(model);
            }
        }
        return routes.getOrDefault(route, Four.ONE);
    }

    /** 折算一次调用的用量；系数全是 1 时原样返回。 */
    public TokenUsage apply(String route, TokenUsage usage, String... candidates) {
        Four weights = of(route, candidates);
        return weights.plain() ? usage : usage.weighed(weights.input(), weights.output(), weights.cacheRead(), weights.cacheWrite());
    }

    /** 有没有任何不是 1 的系数：没有的话界面上不出现“折算”。 */
    public boolean any() {
        return !rows().isEmpty();
    }

    /** 不全是 1 的各行：路线的在前，它下面的模型跟着，按名字。 */
    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        java.util.Set<String> names = new java.util.TreeSet<>(routes.keySet());
        names.addAll(models.keySet());
        for (String route : names) {
            Four whole = routes.getOrDefault(route, Four.ONE);
            if (!whole.plain()) {
                rows.add(new Row(route, null, whole));
            }
            models.getOrDefault(route, Map.of()).forEach((model, weights) -> {
                if (!weights.equals(whole)) {
                    rows.add(new Row(route, model, weights));
                }
            });
        }
        return List.copyOf(rows);
    }
}
