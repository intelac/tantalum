package tantalum.memory.capture;

/**
 * 自动记笔记的配置（{@code tantalum.memory-capture.*}，memory-capture 需求 2、4.2）。{@code route}、{@code model} 为空时：
 * 有本机路线 {@code local} 就用它，否则用默认路线。服务器默认开（{@code application.properties}）；没有配置时（嵌入使用、测试）是关的，
 * 免得后台记笔记的模型调用混进别的测试。
 */
public record CaptureSettings(Boolean enabled, String route, String model, Integer maxInputChars, Integer maxOutputTokens, Integer workers,
                              Integer queue) {

    public CaptureSettings {
        enabled = enabled != null && enabled;
        route = route == null || route.isBlank() ? null : route.strip();
        model = model == null || model.isBlank() ? null : model.strip();
        maxInputChars = positive("max-input-chars", maxInputChars, 20_000);
        maxOutputTokens = positive("max-output-tokens", maxOutputTokens, 1_500);
        workers = positive("workers", workers, 2);
        queue = positive("queue", queue, 200);
    }

    public static CaptureSettings defaults() {
        return new CaptureSettings(null, null, null, null, null, null, null);
    }

    private static int positive(String name, Integer value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("tantalum.memory-capture." + name + " must be positive but was " + value);
        }
        return value;
    }
}
