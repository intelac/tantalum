package tantalum.shell.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tantalum.value.decision.Decision;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.RunState;

/** 比较用内核重放和用结果行折叠得到的每一步（检查工具，record-results R12）。不一致说明内核或钩子的行为变了。 */
public final class ReplayCompare {

    private ReplayCompare() {}

    /** 第一处不同：哪一步（事件序号）、哪个部分。 */
    public record Difference(long seq, String field) {}

    /** 一步里属于这个会话的部分：内核的状态里还有别的会话，折叠的没有。 */
    private record Step(long seq, Object commands, Object facts, Object session, Object runs) {}

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
