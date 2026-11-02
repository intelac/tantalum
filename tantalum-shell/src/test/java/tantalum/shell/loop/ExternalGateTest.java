package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tantalum.shell.loop.ExternalGate.Verdict;
import tantalum.value.id.Principal;

class ExternalGateTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    private int active;
    private boolean halted;

    private ExternalGate gate(int maxActive, int perUser, int total) {
        return new ExternalGate(new ExternalGate.Limits(maxActive, perUser, total), () -> active, () -> halted);
    }

    @Test
    void admitsUnderTheLimits() {
        assertEquals(Verdict.ADMITTED, gate(4, 2, 8).admit(ALICE, false));
    }

    @Test
    void userOverLimitIsRejectedWithoutAffectingOthers() {
        ExternalGate gate = gate(4, 1, 8);
        assertEquals(Verdict.ADMITTED, gate.admit(ALICE, false));
        assertEquals(Verdict.OVER_LIMIT, gate.admit(ALICE, false));
        assertEquals(Verdict.ADMITTED, gate.admit(BOB, false));
    }

    @Test
    void releaseFreesTheSlot() {
        ExternalGate gate = gate(4, 1, 8);
        gate.admit(ALICE, false);
        gate.release(ALICE);
        assertEquals(Verdict.ADMITTED, gate.admit(ALICE, false));
    }

    /** 多余的释放不能把计数变负，否则之后的限额形同虚设；归零的用户不留条目。 */
    @Test
    void extraReleasesDoNotWeakenTheLimits() {
        ExternalGate gate = gate(4, 1, 1);
        gate.release(ALICE);
        gate.releaseNewRun();
        assertEquals(0, gate.trackedUsers());
        assertEquals(Verdict.ADMITTED, gate.admit(ALICE, true));
        assertEquals(1, gate.trackedUsers());
        gate.release(ALICE);
        gate.release(ALICE);
        gate.releaseNewRun();
        gate.releaseNewRun();
        assertEquals(0, gate.trackedUsers());
        assertEquals(Verdict.ADMITTED, gate.admit(ALICE, false));
        assertEquals(Verdict.OVER_LIMIT, gate.admit(ALICE, false));
        assertEquals(Verdict.OVER_LIMIT, gate.admit(BOB, false));
    }

    @Test
    void rejectedRequestsLeaveNoEntry() {
        ExternalGate gate = gate(4, 1, 1);
        gate.admit(ALICE, false);
        assertEquals(Verdict.OVER_LIMIT, gate.admit(BOB, false));
        assertEquals(1, gate.trackedUsers());
    }

    @Test
    void totalLimitIsEnforced() {
        ExternalGate gate = gate(4, 8, 1);
        gate.admit(ALICE, false);
        assertEquals(Verdict.OVER_LIMIT, gate.admit(BOB, false));
    }

    @Test
    void newRunOverTheActiveLimitIsRejectedButExistingRunsMayContinue() {
        ExternalGate gate = gate(2, 8, 8);
        active = 2;
        assertEquals(Verdict.OVER_LIMIT, gate.admit(ALICE, true));
        assertEquals(Verdict.ADMITTED, gate.admit(ALICE, false));
    }

    @Test
    void inFlightSubmissionsCountAgainstTheActiveLimit() {
        ExternalGate gate = gate(2, 8, 8);
        active = 1;
        assertEquals(Verdict.ADMITTED, gate.admit(ALICE, true));
        assertEquals(Verdict.OVER_LIMIT, gate.admit(BOB, true));
    }

    @Test
    void haltedProcessRefusesEverything() {
        ExternalGate gate = gate(4, 8, 8);
        halted = true;
        assertEquals(Verdict.HALTED, gate.admit(ALICE, false));
    }
}
