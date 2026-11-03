package tantalum.examples;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.value.json.Json;

/**
 * {@link ProviderBackoffHook} 要的工作：每隔 {@code poll} 查一次提供方的状态地址，返回 2xx 就算恢复。
 * 连不上也接着查：服务端错误时状态页本身也可能暂时不通。不自己设上限，由 {@code tantalum.tool-timeout} 结束，超时的结果是失败。
 */
public final class ProviderStatusWork implements WorkType {

    public static final String TYPE = "providerStatus";

    private final URI statusUrl;
    private final Duration poll;
    private final HttpClient http = HttpClient.newHttpClient();

    public ProviderStatusWork(URI statusUrl, Duration poll) {
        if (poll == null || poll.isNegative() || poll.isZero()) {
            throw new IllegalArgumentException("poll must be positive but was " + poll);
        }
        this.statusUrl = Objects.requireNonNull(statusUrl, "statusUrl");
        this.poll = poll;
    }

    @Override
    public String name() {
        return TYPE;
    }

    @Override
    public String plugin() {
        return "providerBackoff";
    }

    /** 被中断（超时、取消运行）时 {@code send} 和 {@code sleep} 抛 {@link InterruptedException}，循环随之结束。 */
    @Override
    public WorkResult run(ToolContext context, Json input) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(statusUrl).GET().build();
        for (int checks = 1; ; checks++) {
            try {
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status >= 200 && status < 300) {
                    return new WorkResult(healthy(checks), null);
                }
            } catch (IOException unreachable) {
                // 连不上和返回非 2xx 一样，下次再查。
            }
            Thread.sleep(poll);
        }
    }

    private static Json healthy(int checks) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("healthy", new Json.Bool(true));
        fields.put("checks", new Json.Num(BigDecimal.valueOf(checks)));
        return new Json.Obj(fields);
    }
}
