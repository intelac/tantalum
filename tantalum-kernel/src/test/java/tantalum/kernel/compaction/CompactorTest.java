package tantalum.kernel.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tantalum.kernel.projection.ContextUpdate;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.Compacting.Trigger;
import tantalum.value.state.Compaction;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.Plan;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;
import tantalum.value.state.UsageAnchor;

class CompactorTest {

    /** 窗口 1000、预留 200：阈值 800，K = min(100, 200) = 100。 */
    private static final CompactionSettings SETTINGS = new CompactionSettings(1000, 200, 100, 60, 30, 500);
    private static final List<ToolSpec> NO_TOOLS = List.of();

    /** 每条消息 30 token（90 字节）。 */
    private static Message user(int tag) {
        return Message.user(("u" + tag + " ").repeat(90 / ("u" + tag + " ").length()).substring(0, 90));
    }

    private static Message assistant(int tag) {
        return new Message(Role.ASSISTANT, List.of(new TextBlock(("a" + tag + " ").repeat(30).substring(0, 90))), false);
    }

    private static SessionState session(CompactionSettings settings, Message... messages) {
        SessionState session = SessionState.start(new SessionId("s"), new Principal("u"))
                .withContext(new SessionContext("sys", NO_TOOLS, settings, List.of()));
        for (Message message : messages) {
            session = session.append(message);
        }
        return session;
    }

    @Test
    void noSettingsMeansNoCompaction() {
        SessionState session = session(null, user(1), assistant(1), user(2), assistant(2), user(3), assistant(3));
        assertTrue(Compactor.plan(session, "sys", NO_TOOLS, Trigger.AUTO).isEmpty());
        assertTrue(Compactor.plan(session, "sys", NO_TOOLS, Trigger.OVERFLOW).isEmpty());
    }

    @Test
    void sizeUsesTheAnchorWhenPresentAndEstimatesOtherwise() {
        SessionState session = session(SETTINGS, user(1), assistant(1), user(2));
        assertEquals(TokenEstimate.ofText("sys") + 90, Compactor.size(session, "sys", NO_TOOLS));
        SessionState anchored = session.withAnchor(new UsageAnchor(700, 2));
        assertEquals(730, Compactor.size(anchored, "sys", NO_TOOLS), "anchor plus the one message added since");
    }

    @Test
    void autoTriggersOnlyAboveTheThreshold() {
        SessionState session = session(SETTINGS, user(1), assistant(1), user(2), assistant(2));
        assertTrue(Compactor.plan(session.withAnchor(new UsageAnchor(800, 4)), "sys", NO_TOOLS, Trigger.AUTO).isEmpty(),
                "exactly at the threshold does not trigger");
        assertTrue(Compactor.plan(session.withAnchor(new UsageAnchor(801, 4)), "sys", NO_TOOLS, Trigger.AUTO).isPresent());
        assertTrue(Compactor.plan(session, "sys", NO_TOOLS, Trigger.MANUAL).isPresent(), "manual ignores the threshold");
    }

    @Test
    void keepsRecentUnitsWithinTheBudgetAndAlwaysTheLastOne() {
        SessionState session = session(SETTINGS, user(1), assistant(1), user(2), assistant(2), user(3), assistant(3))
                .withAnchor(new UsageAnchor(900, 6));
        Plan plan = Compactor.plan(session, "sys", NO_TOOLS, Trigger.AUTO).orElseThrow();
        // K = 100：最后三条（90 token）留下，再加一条会到 120。
        assertEquals(3, plan.headMessages());
        assertEquals(3, plan.headUnits());
        assertEquals(3, plan.keptUnits());
        assertEquals(90, plan.headEstimate());
        assertNull(plan.request(), "a newer user message is kept, so the old one in the head only goes into the summary");

        Message huge = Message.user("x".repeat(900));
        SessionState bigTail = session(SETTINGS, user(1), assistant(1), huge).withAnchor(new UsageAnchor(900, 3));
        Plan kept = Compactor.plan(bigTail, "sys", NO_TOOLS, Trigger.AUTO).orElseThrow();
        assertEquals(2, kept.headMessages(), "the last unit is kept even though it exceeds K");
        assertEquals(1, kept.keptUnits());
    }

    /** 规则 5：工具链进行中，保留段里没有用户亲笔消息，头部的请求就是当前请求，原文必须留下。 */
    @Test
    void requestInTheHeadIsKeptWhenTheTailHasNoUserText() {
        Message call = new Message(Role.ASSISTANT, List.of(new TextBlock("x".repeat(600))), false);
        Message results = new Message(Role.USER, List.of(new ToolResultBlock("t", "r", false, null)), false);
        SessionState session = session(SETTINGS, user(1), call, results, assistant(2), results).withAnchor(new UsageAnchor(900, 5));
        Plan plan = Compactor.plan(session, "sys", NO_TOOLS, Trigger.AUTO).orElseThrow();
        assertEquals(user(1), plan.request());
    }

    /** 需求 1.8：手动压缩只留最新单元，同 DSH 的 compactNow。 */
    @Test
    void manualKeepsOnlyTheLastUnit() {
        SessionState session = session(SETTINGS, user(1), assistant(1));
        Plan plan = Compactor.plan(session, "sys", NO_TOOLS, Trigger.MANUAL).orElseThrow();
        assertEquals(1, plan.headMessages());
        assertEquals(1, plan.keptUnits());
    }

    /** 需求 1.3：头部放不下时先丢别的单元，上一次的摘要永不丢。 */
    @Test
    void previousSummaryIsNeverDropped() {
        Message summary = SummaryPrompt.summaryMessage("constraint: never touch A");
        List<Message> view = List.of(summary, user(1), assistant(1), user(2));
        Plan plan = new Plan(3, 3, 1, 90, null);
        long fixed = SETTINGS.threshold() - TokenEstimate.ofMessage(summary) - 5;
        Compactor.Head head = Compactor.headForSummary(plan, view, SETTINGS, fixed);
        assertTrue(head.dropped());
        assertEquals(List.of(summary), head.messages());
        assertTrue(head.nothingToSummarize(), "only the old summary is left");
        assertFalse(Compactor.headForSummary(plan, view, SETTINGS, 0).nothingToSummarize());
        assertFalse(new Compactor.Head(List.of(user(1)), true).nothingToSummarize());
        assertTrue(new Compactor.Head(List.of(), true).nothingToSummarize());
    }

    /** 需求 1.4：文件列表由程序提取，再次压缩时累积，不靠模型。 */
    @Test
    void filesTouchedComeFromToolCallsAndAccumulate() {
        Message call = new Message(Role.ASSISTANT, List.of(
                new tantalum.value.model.ToolCallBlock("a", "read_file", "{}", new Json.Obj(java.util.Map.of("path", new Json.Str("src/A.java")))),
                new tantalum.value.model.ToolCallBlock("b", "read_file", "{}", new Json.Obj(java.util.Map.of("path", new Json.Str("src/B.java")))),
                new tantalum.value.model.ToolCallBlock("c", "grep", "{}", new Json.Obj(java.util.Map.of("pattern", new Json.Str("x")))),
                new tantalum.value.model.ToolCallBlock("d", "bad", "{oops", null)), false);
        List<Message> view = List.of(user(1), call, user(2));
        List<Message> first = Compactor.replacement(new Plan(2, 2, 1, 900, null), view, "summary one", SETTINGS);
        String text = ((TextBlock) first.getFirst().content().getFirst()).text();
        assertTrue(text.contains("<files-touched>\nsrc/A.java\nsrc/B.java\n</files-touched>"), text);

        Message again = new Message(Role.ASSISTANT, List.of(
                new tantalum.value.model.ToolCallBlock("e", "read_file", "{}", new Json.Obj(java.util.Map.of("path", new Json.Str("src/C.java")))),
                new tantalum.value.model.ToolCallBlock("f", "read_file", "{}", new Json.Obj(java.util.Map.of("path", new Json.Str("src/A.java"))))), false);
        List<Message> second = Compactor.replacement(new Plan(2, 2, 1, 900, null), List.of(first.getFirst(), again, user(3)), "summary two", SETTINGS);
        String merged = ((TextBlock) second.getFirst().content().getFirst()).text();
        assertTrue(merged.contains("<files-touched>\nsrc/A.java\nsrc/B.java\nsrc/C.java\n</files-touched>"), merged);
        assertEquals(1, merged.split("<files-touched>", -1).length - 1, "the list is not nested");

        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 250; i++) {
            many.add("f" + i);
        }
        List<String> capped = SummaryPrompt.capFiles(many);
        assertEquals(200, capped.size());
        assertEquals("f249", capped.getLast(), "the most recent paths stay");
    }

    @Test
    void overflowKeepsOnlyTheLastUnit() {
        SessionState session = session(SETTINGS, user(1), assistant(1), user(2), assistant(2));
        Plan plan = Compactor.plan(session, "sys", NO_TOOLS, Trigger.OVERFLOW).orElseThrow();
        assertEquals(3, plan.headMessages());
        assertEquals(1, plan.keptUnits());
    }

    @Test
    void nothingToCompactWhenOneUnitOrHeadWouldBeEmpty() {
        assertTrue(Compactor.plan(session(SETTINGS, user(1)).withAnchor(new UsageAnchor(900, 1)), "sys", NO_TOOLS, Trigger.AUTO).isEmpty());
        assertTrue(Compactor.plan(session(SETTINGS), "sys", NO_TOOLS, Trigger.MANUAL).isEmpty());
        // 两个小单元都在预算内 → 头部为空 → 不压缩。
        SessionState small = session(SETTINGS, Message.user("a"), new Message(Role.ASSISTANT, List.of(new TextBlock("b")), false))
                .withAnchor(new UsageAnchor(900, 2));
        assertTrue(Compactor.plan(small, "sys", NO_TOOLS, Trigger.AUTO).isEmpty());
    }

    @Test
    void requestSkipsContextUpdatesSummariesAndToolResults() {
        Message update = ContextUpdate.message("idx");
        Message summary = SummaryPrompt.summaryMessage("old");
        Message results = new Message(Role.USER, List.of(new ToolResultBlock("t", "r", false, null)), false);
        assertEquals(user(1), Compactor.request(List.of(user(1), update, summary, results)));
        assertNull(Compactor.request(List.of(update, summary, results)));
        assertFalse(Compactor.isUserText(new Message(Role.USER, List.of(new TextBlock("a"), new ToolResultBlock("t", "r", false, null)), false)));
        assertTrue(Compactor.isUserText(Message.user("steer")));
    }

    @Test
    void replacementIsSummaryRequestAndTrimmedTail() {
        ReasoningBlock thinking = new ReasoningBlock("claude", new Json.Str("t"));
        Message tailAssistant = new Message(Role.ASSISTANT, List.of(thinking, new TextBlock("a")), false);
        Message tailResults = new Message(Role.USER, List.of(new ToolResultBlock("t", "x".repeat(100), false, null)), false);
        Message onlyThinking = new Message(Role.ASSISTANT, List.of(thinking), false);
        List<Message> view = List.of(user(1), assistant(1), tailAssistant, tailResults, onlyThinking);
        Plan plan = new Plan(2, 2, 2, 60, user(1));

        List<Message> replacement = Compactor.replacement(plan, view, "the summary", SETTINGS);

        assertEquals(4, replacement.size(), "summary, request, two tail messages; the thinking-only message is dropped");
        assertEquals(SummaryPrompt.summaryMessage("the summary"), replacement.get(0));
        assertEquals(user(1), replacement.get(1));
        assertEquals(List.of(new TextBlock("a")), replacement.get(2).content());
        assertTrue(((ToolResultBlock) replacement.get(3).content().getFirst()).text().contains("of 100 bytes truncated"));

        List<Message> noRequest = Compactor.replacement(new Plan(2, 2, 2, 60, null), view, "s", SETTINGS);
        assertEquals(3, noRequest.size());
    }

    @Test
    void headForSummaryTrimsAndDropsOldestUnitsUntilItFits() {
        ReasoningBlock thinking = new ReasoningBlock("openai", new Json.Str("t"));
        Message big = new Message(Role.USER, List.of(new ToolResultBlock("t", "y".repeat(200), false, null)), false);
        List<Message> view = List.of(user(1), new Message(Role.ASSISTANT, List.of(thinking, new TextBlock("a")), false), big,
                user(2), assistant(2));
        Plan plan = new Plan(3, 2, 2, 0, user(1));

        Compactor.Head fits = Compactor.headForSummary(plan, view, SETTINGS, 0);
        assertFalse(fits.dropped());
        assertEquals(3, fits.messages().size());
        assertEquals(List.of(new TextBlock("a")), fits.messages().get(1).content());
        assertTrue(((ToolResultBlock) fits.messages().get(2).content().getFirst()).text().contains("of 200 bytes truncated"));

        // 固定部分已占 795，阈值 800：只剩 5 token，两个单元都放不下，空了。
        Compactor.Head dropped = Compactor.headForSummary(plan, view, SETTINGS, 795);
        assertTrue(dropped.dropped());
        assertTrue(dropped.messages().isEmpty());

        Compactor.Head partial = Compactor.headForSummary(plan, view, SETTINGS, 770);
        assertTrue(partial.dropped());
        assertEquals(2, partial.messages().size(), "the first unit went, the assistant unit (about 20 tokens) stays");
    }

    @Test
    void planOnTheViewAfterAnEarlierCompaction() {
        Message summary = SummaryPrompt.summaryMessage("earlier");
        SessionState session = session(SETTINGS, user(1), assistant(1), user(2), assistant(2), user(3), assistant(3))
                .compacted(new Compaction(List.of(summary, user(2), assistant(2)), 6))
                .append(user(4)).append(assistant(4)).append(user(5)).append(assistant(5));
        assertNull(session.anchor());
        Optional<Plan> plan = Compactor.plan(session.withAnchor(new UsageAnchor(900, 10)), "sys", NO_TOOLS, Trigger.AUTO);
        assertTrue(plan.isPresent());
        assertEquals(4, plan.orElseThrow().headMessages(), "the head is counted on the view, starting at the old summary");
        assertNull(plan.orElseThrow().request(), "user(5) in the kept tail is the current request");
    }
}
