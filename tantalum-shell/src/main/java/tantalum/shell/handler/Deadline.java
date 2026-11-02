package tantalum.shell.handler;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 在自己的虚拟线程上跑插件代码：到时或调用方被中断时中断它，不等它真的停下。插件代码抛的异常包在 {@link ExecutionException} 里。 */
final class Deadline {

    private Deadline() {}

    static <T> T call(Callable<T> body, Duration limit) throws TimeoutException, ExecutionException, InterruptedException {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                result.complete(body.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(Math.max(0, limit.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | InterruptedException stopped) {
            worker.interrupt();
            throw stopped;
        }
    }
}
