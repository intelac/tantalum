package tantalum.shell.store;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.trace.RunRow;
import tantalum.shell.trace.TraceRecord;
import tantalum.shell.trace.TraceRecord.RunHead;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/**
 * 把轨迹记录攒成运行表的行（run-tree 需求 3）：运行开始时给一行（还没结束），结束时给最终的一行，中间不给，免得每个事件都写索引。
 * 只留未结束运行的记录，结束就丢。
 *
 * <p>重启后接着进行的运行，开头的记录在重启前，这里没见过：不给半截的汇总，结束时交给 {@link Rebuild} 从会话文件重算。
 * 一条记录是不是这一步新建的看它的 {@code pos}（“序号.下标”）是不是这一步的序号。
 * 出错只丢那一个运行（记日志），不影响运行本身和别的运行。
 */
public final class RunCollector {

    /** 从会话文件重算一个运行的最终一行。 */
    @FunctionalInterface
    public interface Rebuild {
        void rebuild(Principal owner, SessionId session, RunId run);
    }

    private static final Logger LOG = LoggerFactory.getLogger(RunCollector.class);

    private static final class Open {
        final Principal owner;
        final Map<String, TraceRecord> records = new LinkedHashMap<>();
        RunHead head;
        /** 这一批里刚开始，还没给过开始行。 */
        boolean fresh;

        Open(Principal owner) {
            this.owner = owner;
        }
    }

    private final Consumer<RunRow> emit;
    private final Rebuild rebuild;
    private final Map<RunId, Open> open = new LinkedHashMap<>();
    /** 开头没见过的运行：等它结束时重算。 */
    private final Set<RunId> partial = new HashSet<>();
    /** 这一批里开头没见过的运行的运行头有没有结束。 */
    private final Map<RunId, Boolean> ended = new HashMap<>();

    public RunCollector(Consumer<RunRow> emit, Rebuild rebuild) {
        this.emit = emit;
        this.rebuild = rebuild;
    }

    /** 一个事件带来的轨迹记录（这一步的最终版本），{@code seq} 是这个事件的序号。整批处理完才给行，同一事件里的记录不会漏。 */
    public synchronized void onRecords(long seq, Principal owner, List<TraceRecord> records) {
        Set<RunId> touched = new LinkedHashSet<>();
        // 这一步新开始的运行：运行头是这一步建的（同一批里它不一定排在最前）
        Set<RunId> starting = new HashSet<>();
        for (TraceRecord record : records) {
            if (record instanceof RunHead && createdAt(record, seq)) {
                starting.add(record.head().run());
            }
        }
        for (TraceRecord record : records) {
            RunId run = record.head().run();
            try {
                accept(starting, owner, record, run);
                touched.add(run);
            } catch (RuntimeException failure) {
                drop(run, seq, failure);
            }
        }
        for (RunId run : touched) {
            try {
                settle(seq, owner, run);
            } catch (RuntimeException failure) {
                drop(run, seq, failure);
            }
        }
    }

    /** 还在攒的运行数（测试和日志用）。 */
    public synchronized int openRuns() {
        return open.size() + partial.size();
    }

    private void drop(RunId run, long seq, RuntimeException failure) {
        LOG.error("run collector dropped run {} at seq {}", run.value(), seq, failure);
        open.remove(run);
        partial.remove(run);
    }

    private void accept(Set<RunId> starting, Principal owner, TraceRecord record, RunId run) {
        if (partial.contains(run)) {
            if (record instanceof RunHead head) {
                ended.put(run, head.endReason() != null);
            }
            return;
        }
        Open current = open.get(run);
        if (current == null) {
            if (!starting.contains(run)) {
                // 开头在这里没见过（重启前开始的运行）：只记下它，结束时重算
                partial.add(run);
                if (record instanceof RunHead head) {
                    ended.put(run, head.endReason() != null);
                }
                return;
            }
            current = new Open(owner);
            current.fresh = true;
            open.put(run, current);
        }
        current.records.put(record.id(), record);
        if (record instanceof RunHead head) {
            current.head = head;
        }
    }

    /** 这一批处理完后：新开始的运行给开始行，结束的给最终行并丢掉；开头没见过的结束时重算。 */
    private void settle(long seq, Principal owner, RunId run) {
        Boolean partialEnded = ended.remove(run);
        if (partial.contains(run)) {
            if (Boolean.TRUE.equals(partialEnded)) {
                partial.remove(run);
                rebuild.rebuild(owner, session(run), run);
            }
            return;
        }
        Open current = open.get(run);
        if (current == null || current.head == null) {
            return;
        }
        boolean finished = current.head.endReason() != null;
        if (finished) {
            open.remove(run);
        }
        if (finished || current.fresh) {
            current.fresh = false;
            emit.accept(RunRow.of(current.owner, session(run), current.head, current.records.values()));
        }
    }

    private static boolean createdAt(TraceRecord record, long seq) {
        String pos = record.head().pos();
        int dot = pos.indexOf('.');
        return (dot < 0 ? pos : pos.substring(0, dot)).equals(Long.toString(seq));
    }

    private static SessionId session(RunId run) {
        return run.session().orElseThrow(() -> new IllegalArgumentException("run without a session: " + run.value()));
    }
}
