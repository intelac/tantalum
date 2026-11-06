package tantalum.shell.route;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 一条路线最近一次读到的模型列表（model-picker M2）。{@code OK} 是最近一次读到了；{@code STALE} 是这次没读到、还用上次的；
 * {@code UNAVAILABLE} 是一次也没读到过，没有可选的模型。{@code error} 是给日志和画面的一句话，只写类别（“连不上”“401”“超时”），
 * 不写地址和响应体；{@code OK} 时为 null。
 */
public record Listing(Status status, List<ModelInfo> models, Instant loadedAt, String error) {

    public enum Status {
        OK, STALE, UNAVAILABLE
    }

    public Listing {
        models = List.copyOf(models);
    }

    /** 还没读完或从没读到。 */
    public static Listing unavailable(String error) {
        return new Listing(Status.UNAVAILABLE, List.of(), null, error);
    }

    public static Listing ok(List<ModelInfo> models, Instant at) {
        return new Listing(Status.OK, models, at, null);
    }

    /** 这次没读到：读到过就留着上次的模型（{@code STALE}），否则仍是 {@code UNAVAILABLE}。 */
    public Listing failed(String reason) {
        return status == Status.UNAVAILABLE ? unavailable(reason) : new Listing(Status.STALE, models, loadedAt, reason);
    }

    public Optional<ModelInfo> find(String model) {
        return models.stream().filter(info -> info.id().equals(model)).findFirst();
    }
}
