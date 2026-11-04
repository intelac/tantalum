package tantalum.shell.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.Kernel;
import tantalum.shell.journal.Line;
import tantalum.shell.journal.SessionCorruptException;
import tantalum.shell.journal.SessionFiles;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.decision.Decision;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * 启动时补写结果行（record-results R5）：崩溃时最后几个事件已经落盘、结果行还没写。结果行落盘之前不执行命令，
 * 所以这些步骤没有产生过副作用；现在交给内核处理一次（命令不执行），结果行追加到各自的文件。这是它们第一次被处理，不是重算。
 * 只看大小和索引不符的文件：没崩溃时两者一致。补写到一半崩溃没关系，下次只补剩下的。
 */
public final class Backfill {

    private static final Logger LOG = LoggerFactory.getLogger(Backfill.class);

    private record Tail(Path file, Principal principal, RecordReplay.Folded folded) {}

    private Backfill() {}

    /** 返回补写了多少个事件。单个文件读不了时跳过，那个会话照现有规则拒绝载入。 */
    public static int run(SessionIndex index, SessionPaths paths, RecordReplay records, SessionFiles files, Kernel kernel)
            throws IOException, SQLException {
        Map<Path, Long> offsets = new HashMap<>();
        for (SessionIndex.Session session : index.sessions()) {
            offsets.put(paths.sessionFile(session.principal(), session.session()), session.fileOffset());
        }
        int handled = 0;
        for (Path dir : paths.users()) {
            List<Tail> tails = new ArrayList<>();
            for (Path file : paths.sessionFiles(dir)) {
                Long offset = offsets.get(file);
                if (offset != null && offset == Files.size(file)) {
                    continue;
                }
                try {
                    RecordReplay.Folded folded = records.fold(file);
                    if (!folded.unanswered().isEmpty()) {
                        tails.add(new Tail(file, folded.unanswered().getFirst().principal(), folded));
                    }
                } catch (SessionCorruptException corrupt) {
                    LOG.warn("cannot backfill a corrupt session file: {}", corrupt.getMessage());
                }
            }
            if (!tails.isEmpty()) {
                handled += answer(index, files, kernel, tails);
            }
        }
        if (handled > 0) {
            LOG.info("handled {} events left without results by the previous process", handled);
        }
        return handled;
    }

    /** 同一用户的尾部按序号合并后处理：钩子看到的累计用量和平时一样，来自索引里已有的用量。 */
    private static int answer(SessionIndex index, SessionFiles files, Kernel kernel, List<Tail> tails) throws IOException, SQLException {
        Principal principal = tails.getFirst().principal();
        KernelState state = KernelState.empty();
        List<EventEnvelope> events = new ArrayList<>();
        for (Tail tail : tails) {
            KernelState folded = tail.folded().state();
            for (SessionState session : folded.sessions().values()) {
                List<RunState> runs = folded.runs().values().stream().filter(run -> run.ref().session().equals(session.id())).toList();
                state = state.withResident(session, runs);
            }
            files.adopt(principal, tail.folded().unanswered().getFirst().session(), tail.folded().memo());
            events.addAll(tail.folded().unanswered());
        }
        events.sort(Comparator.comparingLong(EventEnvelope::seq));
        List<IndexChange.Usage> stored = index.usage(principal);
        Set<Long> storedSeqs = new HashSet<>();
        stored.forEach(row -> storedSeqs.add(row.seq()));
        UsageLedger.Cursor usage = new UsageLedger.Cursor(PrincipalState.start(principal), stored);
        for (EventEnvelope envelope : events) {
            KernelState before = state.with(usage.before(envelope.seq()));
            Decision decision = kernel.step(before, envelope);
            StepRecord record = StepRecord.of(envelope, before, decision);
            files.appendLines(principal, envelope.session(), List.of(new Line.Result(record)));
            if (record.usage() != null && !storedSeqs.contains(envelope.seq())) {
                usage.add(new IndexChange.Usage(envelope.seq(), record.usageDay(), record.usage()));
            }
            state = decision.newState();
        }
        for (Tail tail : tails) {
            files.forget(tail.folded().unanswered().getFirst().session());
        }
        return events.size();
    }
}
