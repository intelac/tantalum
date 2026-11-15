package tantalum.shell.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.plugin.RunFinishedListener.FinishedRun;
import tantalum.shell.plugin.RunFinishedListener;
import tantalum.shell.trace.RunRow;
import tantalum.shell.trace.RunText;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * “运行结束后”插件点的平台一侧（memory-plugin 需求 1.1）：运行表的最终一行进一个有界队列（满了丢最旧的），几个后台线程逐个处理——
 * 给这个用户开着的每个听众一份这一轮的摘录。听众慢不影响对话，抛出只记日志、不影响别的听众。
 */
final class PluginRunListeners implements AutoCloseable {

    /** 这一轮的轨迹记录。 */
    @FunctionalInterface
    interface Records {
        List<TraceRecord> of(Principal user, SessionId session, tantalum.value.id.RunId run);
    }

    static final int QUEUE = 200;
    static final int WORKERS = 2;
    static final int EXCERPT_CHARS = 20_000;
    private static final Logger LOG = LoggerFactory.getLogger(PluginRunListeners.class);

    private final List<RunFinishedListener> listeners;
    private final BiPredicate<String, Principal> enabled;
    private final Records records;
    private final Function<SessionId, String> titles;
    private final ArrayBlockingQueue<RunRow> queue = new ArrayBlockingQueue<>(QUEUE);
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean closed;

    PluginRunListeners(List<RunFinishedListener> listeners, BiPredicate<String, Principal> enabled, Records records,
                       Function<SessionId, String> titles) {
        this.listeners = List.copyOf(listeners);
        this.enabled = enabled;
        this.records = records;
        this.titles = titles;
        if (!this.listeners.isEmpty()) {
            for (int i = 0; i < WORKERS; i++) {
                workers.add(Thread.ofPlatform().daemon().name("plugin-run-listeners-" + i).start(this::work));
            }
        }
    }

    /** 一次运行结束（运行表的最终一行）。不阻塞。 */
    void offer(RunRow row) {
        if (closed || listeners.isEmpty() || row.end() == null) {
            return;
        }
        while (!queue.offer(row)) {
            RunRow dropped = queue.poll();
            if (dropped != null) {
                LOG.warn("run listeners are behind; dropped run {}", dropped.run().value());
            }
        }
    }

    private void work() {
        while (!closed) {
            RunRow row;
            try {
                row = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException stopping) {
                return;
            }
            if (row != null) {
                deliver(row);
            }
        }
    }

    /** 测试直接调。 */
    void deliver(RunRow row) {
        List<RunFinishedListener> interested = listeners.stream().filter(listener -> enabled.test(listener.plugin(), row.principal())).toList();
        if (interested.isEmpty()) {
            return;
        }
        FinishedRun run;
        try {
            String excerpt = RunText.of(records.of(row.principal(), row.session(), row.run()), EXCERPT_CHARS);
            run = new FinishedRun(row.principal(), row.session(), row.run(), row.turn(),
                    row.endReason() == null ? null : row.endReason().name(),
                    row.trigger() == TraceRecord.RunHead.Trigger.MANUAL_COMPACTION, row.start(), row.end(), titles.apply(row.session()), excerpt);
        } catch (RuntimeException failure) {
            LOG.warn("cannot build the finished run {} for plugins: {}", row.run().value(), failure.toString());
            return;
        }
        for (RunFinishedListener listener : interested) {
            try {
                listener.runFinished(run);
            } catch (RuntimeException failure) {
                LOG.warn("plugin {} failed on finished run {}: {}", listener.plugin(), row.run().value(), failure.toString());
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        workers.forEach(Thread::interrupt);
    }
}
