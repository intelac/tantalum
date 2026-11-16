package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import tantalum.shell.conversation.SessionSummary;
import tantalum.shell.project.Project;
import tantalum.value.id.SessionId;

/** 上下文里的“工作区”一段：模型看到有哪些工作区、各自有哪些会话，据此决定交给谁、开在哪、要不要新建。 */
class WorkspaceSectionTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final Project SHOP = new Project("p1", "shop", "/home/yi/shop", NOW);
    private static final Project DOCS = new Project("p2", "docs", "C:\\Work\\Docs", NOW);

    private static WorkspaceSection.Row row(String id, String title, Duration ago, String status, String dir, Long tokens) {
        return new WorkspaceSection.Row(new SessionSummary(new SessionId(id), title, NOW.minus(ago), status, dir),
                tokens == null ? OptionalLong.empty() : OptionalLong.of(tokens));
    }

    @Test
    void everyLineSaysWhichWorkspaceTheConversationWorksIn() {
        String text = WorkspaceSection.text(List.of(SHOP, DOCS), List.of(
                row("s-a", "fix the cart", Duration.ofMinutes(5), SessionSummary.IDLE, "/home/yi/shop/", 42_500L),
                row("s-b", "write the\n guide", Duration.ofDays(3), "running", "c:/work/docs", 131_000L),
                row("main-1", "hello", Duration.ofMinutes(1), "running", null, 9_000L)), new SessionId("main-1"), NOW);
        String[] lines = text.split("\n");
        assertEquals("- workspace \"shop\" — /home/yi/shop", lines[1]);
        assertEquals("- workspace \"docs\" — C:\\Work\\Docs", lines[2]);
        assertEquals("Recent conversations:", lines[3]);
        assertEquals("- s-a \"fix the cart\" · in \"shop\" · active in the last day · ~40k tokens in context", lines[4]);
        assertEquals("- s-b \"write the guide\" · in \"docs\" · 3 d ago · ~130k tokens in context (large)", lines[5],
                "the folder is matched whatever the case and slashes; a large context is flagged; who is running is not said");
        assertEquals("- this conversation · in no workspace", lines[6], "its own line has no id, title or time, and no size while small");
        assertTrue(text.contains("delegate with session=<id>") && text.contains("workspace=<its path>") && text.contains("workspace_add"));
    }

    @Test
    void withNoWorkspacesItSaysSoSoTheModelKnowsItCanAddOne() {
        SessionId current = new SessionId("s-a");
        String alone = WorkspaceSection.text(List.of(), List.of(row("s-a", "x", Duration.ZERO, "running", null, 5L)), current, NOW);
        assertTrue(alone.contains("- (no workspaces yet)\nRecent conversations:\n- this conversation · in no workspace\n"), alone);
        String nothing = WorkspaceSection.text(List.of(), List.of(), current, NOW);
        assertTrue(nothing.contains("- (no workspaces yet)\nTo hand work"), nothing);
    }

    @Test
    void onlyTheMostRecentFewAreShownAndTheirOrderDoesNotFollowWhoSpokeLast() {
        List<WorkspaceSection.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            rows.add(row("s-" + i, "t" + i, Duration.ofDays(i), SessionSummary.IDLE, "/home/yi/shop", null));
        }
        String before = WorkspaceSection.text(List.of(SHOP), rows, new SessionId("x"), NOW);
        assertTrue(before.contains("s-5") && !before.contains("s-6 "), before);
        assertTrue(before.contains("- (2 older conversations in \"shop\" not shown)"));
        // s-4 刚说了话：还是同样五个，位置不变，只有它那一行的时间变了
        rows.set(3, row("s-4", "t4", Duration.ZERO, SessionSummary.IDLE, "/home/yi/shop", null));
        String after = WorkspaceSection.text(List.of(SHOP), rows, new SessionId("x"), NOW);
        assertEquals(before.replace("s-4 \"t4\" · in \"shop\" · 4 d ago", "s-4 \"t4\" · in \"shop\" · active in the last day"), after);
    }

    @Test
    void theSameFactsGiveTheSameTextAsMinutesPassAndRunsStartAndStop() {
        List<WorkspaceSection.Row> rows = List.of(row("s-a", "a", Duration.ofMinutes(5), SessionSummary.IDLE, "/home/yi/shop", 41_000L));
        List<WorkspaceSection.Row> later = List.of(row("s-a", "a", Duration.ofMinutes(50), "running", "/home/yi/shop", 48_000L));
        assertEquals(WorkspaceSection.text(List.of(SHOP), rows, new SessionId("x"), NOW), WorkspaceSection.text(List.of(SHOP), later, new SessionId("x"), NOW),
                "changed lines are told to the model again, so small changes must not show");
        assertEquals("3 w ago", WorkspaceSection.ago(Duration.ofDays(21)));
        assertEquals("13 d ago", WorkspaceSection.ago(Duration.ofDays(13)));
    }

    @Test
    void tooManyWorkspacesAreCut() {
        List<Project> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(new Project("p" + i, "w" + i, "/w/" + i, NOW));
        }
        String text = WorkspaceSection.text(many, List.of(row("s-a", "a", Duration.ZERO, SessionSummary.IDLE, "/w/14", null)), new SessionId("x"), null);
        assertTrue(text.contains("- (3 more workspaces not shown)"));
        assertTrue(text.contains("- s-a \"a\" · in no workspace\n"), "a conversation in a workspace that is not shown, and no time without a clock: " + text);
    }
}
