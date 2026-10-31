package tantalum.shell.handler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/** 替身工具服务端：同一时刻只能处理一个请求，重叠就记录违规。 */
final class SingleRequestServer implements Tool {

    private final String name;
    private final AtomicInteger active = new AtomicInteger();
    final AtomicInteger violations = new AtomicInteger();
    final List<String> served = new CopyOnWriteArrayList<>();

    SingleRequestServer(String name) {
        this.name = name;
    }

    @Override public String name() { return name; }
    @Override public String description() { return "single request server"; }
    @Override public Json schema() { return new Json.Null(); }
    @Override public String server() { return "single"; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public ToolOutput run(tantalum.shell.plugin.ToolContext context, Json arguments) throws Exception {
        if (active.incrementAndGet() > 1) {
            violations.incrementAndGet();
        }
        try {
            Thread.sleep(2);
            String text = ((Json.Str) arguments).value();
            served.add(text);
            return ToolOutput.ok(text);
        } finally {
            active.decrementAndGet();
        }
    }
}
