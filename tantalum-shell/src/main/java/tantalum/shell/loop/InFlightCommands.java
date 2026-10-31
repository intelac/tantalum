package tantalum.shell.loop;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.value.id.CommandId;

/** 在途 Handler 的记录，负责中断。 */
public final class InFlightCommands {

    private static final class Entry {
        final Thread thread;
        volatile boolean aborted;

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
}
