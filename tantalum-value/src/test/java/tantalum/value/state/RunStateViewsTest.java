package tantalum.value.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.PromptPatch;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;

/** review-fixes 需求 13.3：运行状态按阶段分成三组视图，整组替换不动别的组；记录本身不变，journal 形状不变。 */
class RunStateViewsTest {

    private static final SessionId SESSION = new SessionId("s");
    private static final RunRef REF = new RunRef(new Principal("alice"), SESSION, new RunId("s_1"));

    @Test
    void theThreeViewsCoverEveryFieldAndReplaceOnlyTheirOwnGroup() {
        RunState run = RunState.start(REF, new OwnerRef.OfSession(SESSION));
        assertEquals(new RunState.Flow(new Deciding(), null, List.of(), 0, null, 0, 0), run.flow());
        assertEquals(new RunState.Recovery(null, false, false), run.recovery());
        assertEquals(new RunState.Models(null, null), run.models());

        Compacting compacting = new Compacting(new Compacting.Auto(), new Plan(3, 2, 1, 900, null), PromptPatch.NONE);
        RunState recovering = run.withRecovery(new RunState.Recovery(compacting, true, true));
        assertEquals(compacting, recovering.compacting());
        assertTrue(recovering.autoCompactionFailed() && recovering.overflowRecovered());
        assertEquals(run.flow(), recovering.flow(), "the other groups are untouched");
        assertEquals(run.models(), recovering.models());

        ModelChoice choice = new ModelChoice("claude", "m", null);
        RunState chosen = recovering.withModels(new RunState.Models(choice, null));
        assertEquals(choice, chosen.model());
        assertNull(chosen.modelBefore());
        assertEquals(recovering.recovery(), chosen.recovery());

        RunState.Flow flow = new RunState.Flow(new Ended(tantalum.value.state.EndReason.COMPLETED, null), null,
                List.of(Message.user("steer")), 2, HookPoint.BEFORE_RUN_END, 1, 3);
        RunState flowed = chosen.withFlow(flow);
        assertEquals(flow, flowed.flow());
        assertTrue(flowed.isEnded());
        assertEquals(chosen.recovery(), flowed.recovery());
        assertEquals(chosen.models(), flowed.models());
        // 分组和单个 withX 说的是同一份字段
        assertEquals(flowed.withRetries(5).flow().retries(), 5);
    }
}
