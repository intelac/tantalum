package tantalum.shell.loop;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.value.id.CommandId;

/** 在途 Handler 的记录，负责中断。 */
public final class InFlightCommands {

    private static final class Entry {
        final Thread thread;
        volatile boolean aborted;
        volatile boolean shutdown;

        Entry(Thread thread) {
            this.thread = thread;
        }
    }

    private final Map<CommandId, Entry> entries = new ConcurrentHashMap<>();

    public void register(CommandId id, Thread thread) {
        entries.put(id, new Entry(thread));
    }

    public void complete(CommandId id) {
        entries.remove(id);
    }

    public void abort(Collection<CommandId> ids) {
        for (CommandId id : ids) {
            Entry entry = entries.get(id);
            if (entry != null) {
                entry.aborted = true;
                entry.thread.interrupt();
            }
        }
    }

    public boolean isAborted(CommandId id) {
        Entry entry = entries.get(id);
        return entry != null && entry.aborted;
    }

    public int size() {
        return entries.size();
    }

    /** 等在途的全部结束，超时返回 false。 */
    public boolean awaitEmpty(Duration wait) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (!entries.isEmpty()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    /** 关闭时中断全部在途的 Handler。它们的结果不再写回：被打断不是命令本身的结果，重启时按 H9 收尾。 */
    public void abortForShutdown() {
        for (Entry entry : entries.values()) {
            entry.shutdown = true;
        }
        abort(List.copyOf(entries.keySet()));
    }

    public boolean isShutdownAbort(CommandId id) {
        Entry entry = entries.get(id);
        return entry != null && entry.shutdown;
    }
}
