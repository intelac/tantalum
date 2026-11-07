package tantalum.shell.route;

import java.util.concurrent.atomic.AtomicBoolean;
import tantalum.shell.model.ModelClient;

/**
 * 一条路线：配置、发请求的客户端、读列表的 {@link ModelList}，和最近一次读到的 {@link Listing}。列表在后台读，
 * 取的时候只读这个字段，不去调接口。并发上限在 {@code ModelHandler} 里按路线各管各的。
 */
public final class Route {

    private final RouteSettings settings;
    private final ModelClient client;
    private final ModelList models;
    private final AtomicBoolean reading = new AtomicBoolean();
    private volatile Listing listing = Listing.unavailable("还没读到");

    Route(RouteSettings settings, ModelClient client, ModelList models) {
        this.settings = settings;
        this.client = client;
        this.models = models;
    }

    public String name() {
        return settings.name();
    }

    public RouteSettings settings() {
        return settings;
    }

    public ModelClient client() {
        return client;
    }

    public Listing listing() {
        return listing;
    }

    ModelList models() {
        return models;
    }

    void listing(Listing next) {
        listing = next;
    }

    /** 同一时刻只读一次；已经在读时返回 false。 */
    boolean startReading() {
        return reading.compareAndSet(false, true);
    }

    void doneReading() {
        reading.set(false);
    }
}
