package tantalum.shell.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.loop.SessionSlice;
import tantalum.value.decision.Decision;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.RunState;

/**
 * 比较用内核重放和用结果行折叠得到的结果（record-results 第 1 步、检查工具）。不一致说明内核或钩子的行为变了：
 * 记错误日志（会话、序号、哪个字段），并记下这个会话，会话列表上标出。
 */
public final class ReplayCompare {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayCompare.class);

    /** 第一处不同：哪一步（事件序号）、哪个部分。 */
    public record Difference(long seq, String field) {}

    /** 一步里属于这个会话的部分：内核的状态里还有别的会话，折叠的没有。 */
    private record Step(long seq, Object commands, Object facts, Object session, Object runs) {}

    private final Set<SessionId> mismatched = ConcurrentHashMap.newKeySet();

    public boolean mismatched(SessionId session) {
        return mismatched.contains(session);
    }

    /** 冷载入时调用：两边的切片不同就逐步找出第一处差异。返回是否一致。 */
    public boolean check(Principal principal, SessionId session, Path file, long beforeSeq, Optional<SessionSlice> kernel,
                         Optional<SessionSlice> folded, SessionReplay replay, RecordReplay records) {
        if (same(kernel, folded)) {
            return true;
        }
        String where;
        try {
            where = firstDifference(principal, session, file, beforeSeq, replay, records)
                    .map(difference -> "seq " + difference.seq() + " " + difference.field()).orElse("the loaded slice");
        } catch (Exception failure) {
            where = "unknown (" + failure.getMessage() + ")";
        }
        mismatched.add(session);
        LOG.error("session {} of {}: replaying the kernel and folding the results differ at {}", session.value(), principal.value(), where);
        return false;
    }

    /** 结果行读不出来也算不一致。 */
    public void unreadable(Principal principal, SessionId session, Exception failure) {
        mismatched.add(session);
        LOG.error("session {} of {}: the result lines cannot be folded: {}", session.value(), principal.value(), failure.toString());
    }

    private static boolean same(Optional<SessionSlice> kernel, Optional<SessionSlice> folded) {
        if (kernel.isEmpty() || folded.isEmpty()) {
            return kernel.isEmpty() && folded.isEmpty();
        }
        SessionSlice a = kernel.get();
        SessionSlice b = folded.get();
        return a.seq() == b.seq() && Objects.equals(a.session(), b.session()) && sorted(a.runs()).equals(sorted(b.runs()))
                && Objects.equals(a.transcript(), b.transcript()) && Objects.equals(a.trace(), b.trace());
    }

    /** 逐步比较序号小于 {@code beforeSeq} 的步骤，返回第一处不同；全部相同时为空。 */
    public static Optional<Difference> firstDifference(Principal principal, SessionId session, Path file, long beforeSeq,
                                                       SessionReplay replay, RecordReplay records) throws Exception {
        List<Step> kernel = new ArrayList<>();
        replay.until(principal, file, beforeSeq, (envelope, before, decision) -> kernel.add(step(envelope.seq(), session, decision)));
        List<Step> folded = new ArrayList<>();
        records.until(principal, file, beforeSeq, (envelope, before, decision) -> folded.add(step(envelope.seq(), session, decision)));
        for (int i = 0; i < Math.max(kernel.size(), folded.size()); i++) {
            if (i >= kernel.size() || i >= folded.size()) {
                return Optional.of(new Difference((i < kernel.size() ? kernel : folded).get(i).seq(), "missing step"));
            }
            Step a = kernel.get(i);
            Step b = folded.get(i);
            String field = !a.commands().equals(b.commands()) ? "commands"
                    : !a.facts().equals(b.facts()) ? "facts"
                    : !Objects.equals(a.session(), b.session()) ? "session"
                    : !a.runs().equals(b.runs()) ? "runs" : null;
            if (field != null) {
                return Optional.of(new Difference(a.seq(), field));
            }
        }
        return Optional.empty();
    }

    private static Step step(long seq, SessionId session, Decision decision) {
        List<RunState> runs = decision.newState().runs().values().stream().filter(run -> run.ref().session().equals(session)).toList();
        return new Step(seq, decision.commands(), decision.facts(), decision.newState().sessions().get(session), sorted(runs));
    }

    private static List<RunState> sorted(List<RunState> runs) {
        return runs.stream().sorted(Comparator.comparing(run -> run.ref().run().value())).toList();
    }
}
