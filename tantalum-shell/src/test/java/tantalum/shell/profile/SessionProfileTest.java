package tantalum.shell.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.profile.SessionProfile.Action;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.SessionKind;

/** session-model 需求 5.2–5.6：外壳里“这种会话能做什么”只在这张表里。每一行、每一列一个断言。 */
class SessionProfileTest {

    private static final CompactionSettings BASE = new CompactionSettings(200_000, 16_000, 20_000, 32_768, 4096, 8192, 150_000, 3);

    @Test
    void theMainSessionHandsOutWorkAndKeepsOnlyARecentWindow() {
        SessionProfile main = SessionProfile.of(SessionKind.MAIN);
        assertEquals(SessionKind.MAIN, main.toolsOf(), "the main session has its own tools: task and task_stop (dispatch)");
        assertEquals(SessionProfile.MAIN_NOTE, main.note());
        assertEquals(SessionProfile.Compaction.RECENT_WINDOW, main.compaction());
        assertEquals(EnumSet.allOf(Action.class), main.actions());
        assertTrue(main.listed());
        assertFalse(main.inWorkspaces(), "its title is everyday talk; listing it would carry old words into every session");
        assertTrue(main.searchable());
        assertTrue(main.notifiesPlugins());
    }

    @Test
    void anOrdinarySessionCanDoEverything() {
        SessionProfile regular = SessionProfile.of(SessionKind.REGULAR);
        assertEquals(SessionKind.REGULAR, regular.toolsOf());
        assertNull(regular.note());
        assertEquals(SessionProfile.Compaction.STANDARD, regular.compaction());
        assertEquals(EnumSet.allOf(Action.class), regular.actions());
        assertTrue(regular.listed() && regular.inWorkspaces() && regular.searchable() && regular.notifiesPlugins());
    }

    /** 需求 5.4：帮手会话不能说话、上传、改队列、改名、压缩、叫停；能回答它的问题、取消它那一轮、撤下文字。 */
    @Test
    void aHelperSessionOnlyLetsYouAnswerCancelAndWithdraw() {
        SessionProfile helper = SessionProfile.of(SessionKind.HELPER);
        assertEquals(SessionKind.HELPER, helper.toolsOf());
        assertEquals(SessionProfile.HELPER_NOTE, helper.note());
        assertEquals(SessionProfile.Compaction.STANDARD, helper.compaction());
        assertEquals(EnumSet.of(Action.ANSWER, Action.CANCEL, Action.WITHDRAW), helper.actions());
        assertFalse(helper.listed());
        assertFalse(helper.inWorkspaces());
        assertFalse(helper.searchable());
        assertFalse(helper.notifiesPlugins(), "the one speaking there is another agent, not the person");
    }

    @Test
    void theRecentWindowKeepsOnlyItsBudgetAboveTheFixedPart() {
        SessionProfile main = SessionProfile.of(SessionKind.MAIN);
        CompactionSettings settings = main.settings(BASE, "x".repeat(30_000), List.of(), 8000);
        assertEquals(10_000 + 8000, settings.threshold(), "30 000 bytes of system at 3 bytes a token, plus the budget");
        assertEquals(4000, settings.keepRecent());
        assertEquals(BASE.window(), settings.window());
        assertEquals(BASE.capacity(), main.settings(BASE, "x".repeat(900_000), List.of(), 8000).threshold(), "never above what the model can take");
    }

    @Test
    void standardCompactionIsLeftAsItIs() {
        assertSame(BASE, SessionProfile.of(SessionKind.REGULAR).settings(BASE, "x", List.of(), 8000));
        assertNull(SessionProfile.of(SessionKind.MAIN).settings(null, "x", List.of(), 8000), "no settings, nothing to narrow");
    }

    /** 索引库的查询照表过滤：列进侧栏的、搜得到的是哪几种。 */
    @Test
    void theIndexFiltersByTheKindsTheTableNames() {
        assertEquals("('MAIN', 'REGULAR')", SessionProfile.kindsWhere(SessionProfile::listed));
        assertEquals("('MAIN', 'REGULAR')", SessionProfile.kindsWhere(SessionProfile::searchable));
        assertEquals("('REGULAR')", SessionProfile.kindsWhere(SessionProfile::inWorkspaces));
    }
}
