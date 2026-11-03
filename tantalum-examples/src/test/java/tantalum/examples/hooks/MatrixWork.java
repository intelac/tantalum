package tantalum.examples.hooks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.value.json.Json;

/**
 * 测试用工作，按输入的 {@code mode} 行事：ok 成功；fail 抛异常；timeout 一直睡到被工作超时打断；
 * hold 先报“已开始”再一直等到被中断（取消、重启）；continue 成功并带续写文字。按输入里的 {@code cell} 记下执行次数。
 */
final class MatrixWork implements WorkType {

    static final String TYPE = "matrix";
    static final String SOURCE = "matrixSource";
    static final String FAILURE = "matrix work failed";

    private final String name;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> held = new ConcurrentHashMap<>();

    MatrixWork(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public WorkResult run(ToolContext context, Json input) throws Exception {
        Map<String, Json> fields = ((Json.Obj) input).fields();
        String cell = ((Json.Str) fields.get("cell")).value();
        String mode = ((Json.Str) fields.get("mode")).value();
        calls.computeIfAbsent(cell, key -> new AtomicInteger()).incrementAndGet();
        switch (mode) {
            case "fail" -> throw new IllegalStateException(FAILURE);
            case "timeout" -> {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                throw new AssertionError("the work timeout should have interrupted this");
            }
            case "hold" -> {
                latch(cell).countDown();
                Thread.sleep(Long.MAX_VALUE);
                throw new AssertionError("unreachable");
            }
            case "continue" -> {
                return new WorkResult(result(cell, mode), "cell=" + cell + " please continue");
            }
            default -> {
                return new WorkResult(result(cell, mode), null);
            }
        }
    }

    private static Json result(String cell, String mode) {
        Map<String, Json> out = new LinkedHashMap<>();
        out.put("cell", new Json.Str(cell));
        out.put("mode", new Json.Str(mode));
        return new Json.Obj(out);
    }

    private CountDownLatch latch(String cell) {
        return held.computeIfAbsent(cell, key -> new CountDownLatch(1));
    }

    int calls(String cell) {
        AtomicInteger count = calls.get(cell);
        return count == null ? 0 : count.get();
    }

    /** 等 hold 模式的工作开始执行。 */
    void awaitHeld(String cell) throws InterruptedException {
        if (!latch(cell).await(20, TimeUnit.SECONDS)) {
            throw new AssertionError("work of " + cell + " did not start");
        }
    }
}
