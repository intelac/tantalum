package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.shell.conversation.TranscriptFixture.text;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.journal.EventCodec;
import tantalum.value.event.TextWithdrawn;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;

/**
 * live-views 需求 2、3：同一串事件、同一个内核，对话视图“全留”和“只留尾巴”：每一步推送的条目逐字节相同；结束后对每一个
 * {@code before} 取对话页，两边逐字节相同；半路导出、恢复再接着走也一样。场景有工具轮、拦下的工具、压缩、插话、重试，几十轮问答，
 * 中间撤下最早说过的话（原文只在已经去掉的条目里）。
 */
class LiveViewsEquivalenceTest {

    private final EventCodec codec = EventCodec.withBuiltins();

    private static TranscriptFixture scenario() {
        TranscriptFixture fx = new WireFormatTest().traceScenario(WireFormatTest.fixture());
        String[] task = new String[1];
        for (int i = 0; i < 45; i++) {
            if (i == 30) {
                fx.feed(null, new TextWithdrawn(new RequestId("w"), List.of(new TextWithdrawn.Swap(WireFormatTest.block("q1").substring(0, 20),
                        "[gone]"), new TextWithdrawn.Swap("answer 3", "answer [gone]")), TextWithdrawn.By.user()));
            }
            if (i == 40) {
                // 第 5 轮交代出去的事这时才回来：卡片早就不在最新一页，但还会变，必须留在内存
                fx.feed(null, new tantalum.value.event.Report(task[0], tantalum.value.assignment.AssignmentOutcome.COMPLETED, "Module A is fine.",
                        tantalum.value.event.Root.USER));
                RunId woke = fx.state().sessions().get(TranscriptFixture.SESSION).activeRun();
                if (woke != null) {
                    WireFormatTest.assembled(fx, woke, "sys v2", List.of(WireFormatTest.memory("a\nb")));
                    fx.feed(woke, fx.reply(text("noted")));
                }
            }
            RunId run = WireFormatTest.prompt(fx, "question " + i, "sys v2", List.of(WireFormatTest.memory("a\nb")));
            if (i == 5) {
                fx.feed(run, fx.reply(new tantalum.value.model.ToolCallBlock("t5", "task", "{}", new tantalum.value.json.Json.Obj(java.util.Map.of(
                        "prompt", new tantalum.value.json.Json.Str("Check module A."))))));
                task[0] = fx.items(TranscriptItem.Task.class).getFirst().task();
            }
            fx.feed(run, fx.reply(text("answer " + i)));
        }
        assertEquals("COMPLETED", fx.items(TranscriptItem.Task.class).getFirst().outcome(), "the late report reached the card");
        return fx;
    }

    private String json(TranscriptItem item) throws Exception {
        return codec.mapper().writeValueAsString(ItemJson.toMap(codec.mapper(), item));
    }

    private String page(MessagePage page) throws Exception {
        StringBuilder out = new StringBuilder(page.start() + " " + page.hasMore() + "\n");
        for (TranscriptItem item : page.items()) {
            out.append(json(item)).append('\n');
        }
        return out.toString();
    }

    @Test
    void keepingOnlyTheTailChangesNoPushAndNoPage() throws Exception {
        TranscriptFixture fx = scenario();
        Transcript tail = new Transcript();
        Transcript restored = null;
        int half = fx.envelopes.size() / 2;
        List<String> pushedWhole = new ArrayList<>();
        List<String> pushedTail = new ArrayList<>();
        int withdrawals = 0;
        for (int i = 0; i < fx.envelopes.size(); i++) {
            long seq = fx.envelopes.get(i).seq();
            List<TranscriptItem> whole = fx.changes.stream().filter(change -> change.seq() == seq).map(TranscriptFixture.Change::item).toList();
            List<TranscriptItem> kept = tail.apply(fx.envelopes.get(i), fx.befores.get(i), fx.decisions.get(i));
            if (fx.envelopes.get(i).event() instanceof TextWithdrawn) {
                // 有意改变的一处：撤下时已经不在尾巴里的条目不推（网页刷新或往前翻时拿到换过的）
                java.util.Set<String> inTail = tail.view(TranscriptFixture.SESSION).items().stream().map(TranscriptItem::id)
                        .collect(java.util.stream.Collectors.toSet());
                whole = whole.stream().filter(item -> inTail.contains(item.id())).toList();
                withdrawals++;
            }
            for (TranscriptItem item : whole) {
                pushedWhole.add(seq + " " + json(item));
            }
            for (TranscriptItem item : kept) {
                pushedTail.add(seq + " " + json(item));
            }
            if (i == half) {
                restored = new Transcript();
                restored.restore(TranscriptFixture.SESSION, codec.mapper().readValue(
                        codec.mapper().writeValueAsString(tail.export(TranscriptFixture.SESSION)), Transcript.Saved.class));
            } else if (restored != null) {
                restored.apply(fx.envelopes.get(i), fx.befores.get(i), fx.decisions.get(i));
            }
        }
        assertEquals(1, withdrawals);
        assertEquals(String.join("\n", pushedWhole), String.join("\n", pushedTail));

        List<TranscriptItem> everything = fx.items();
        Transcript.View view = tail.view(TranscriptFixture.SESSION);
        assertTrue(view.offset() > 0, "the scenario does drop items: " + view.offset());
        assertEquals(everything.size(), view.offset() + view.items().size());
        assertEquals(view, restored.view(TranscriptFixture.SESSION), "exported half way, restored and continued");
        assertEquals(SessionSummaryOf(everything), SessionSummary.of(TranscriptFixture.SESSION, null, view).title());
        for (Integer before = null; ; before = before == null ? everything.size() : before - 1) {
            assertEquals(page(MessagePage.of(everything, before)), page(MessagePage.of(view.offset(), view.items(), before, () -> everything)),
                    "page before " + before);
            if (before != null && before == 0) {
                break;
            }
        }
        int[] replays = {0};
        MessagePage.of(view.offset(), view.items(), null, () -> {
            replays[0]++;
            return everything;
        });
        assertEquals(0, replays[0], "the latest page comes from memory");
    }

    private static String SessionSummaryOf(List<TranscriptItem> items) {
        return items.stream().filter(TranscriptItem.UserMessage.class::isInstance).map(TranscriptItem.UserMessage.class::cast).findFirst()
                .map(message -> SessionSummary.titleOf(message.text())).orElse("");
    }
}
