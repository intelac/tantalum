package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.shell.conversation.TranscriptFixture.text;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.trace.Trace;
import tantalum.shell.trace.TraceJson;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.event.TextWithdrawn;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;

/**
 * history-window 需求 3.3：同一串事件（自动压缩、手动压缩、插话、工具轮、重试、压缩之后撤下压缩前说的话、再问），压缩时去掉历史
 * 和不去掉（之前的样子）各跑一遍：每一步发给模型的命令、推送的条目和轨迹记录都一样。
 */
class HistoryWindowEquivalenceTest {

    private final EventCodec codec = EventCodec.withBuiltins();

    private static TranscriptFixture run(TranscriptFixture fx) {
        new WireFormatTest().traceScenario(fx);
        // 两次压缩都去掉了 q4：撤下它，再问一句，模型看到的替换段和之后的原话里都换掉
        fx.feed(null, new TextWithdrawn(new RequestId("w"), List.of(new TextWithdrawn.Swap(WireFormatTest.block("q4").substring(0, 20), "[gone]"),
                new TextWithdrawn.Swap("Goal: r", "Goal: [gone]")), TextWithdrawn.By.user()));
        RunId after = WireFormatTest.prompt(fx, "q6", "sys v2", List.of(WireFormatTest.memory("a\nb")));
        fx.feed(after, fx.reply(text("a6")));
        return fx;
    }

    private List<String> outputs(TranscriptFixture fx) throws Exception {
        List<String> out = new ArrayList<>();
        Trace trace = new Trace();
        for (int i = 0; i < fx.envelopes.size(); i++) {
            long seq = fx.envelopes.get(i).seq();
            out.add(seq + " commands " + fx.decisions.get(i).commands());
            // 有意改变的一处：撤下换了几处只数内存里的，压缩去掉的不数（requirements 的表）
            out.add(seq + " facts " + fx.decisions.get(i).facts().stream()
                    .map(fact -> fact instanceof tantalum.value.decision.TextSwapped swapped ? "TextSwapped" : fact.toString()).toList());
            for (TranscriptFixture.Change change : fx.changes) {
                if (change.seq() == seq) {
                    out.add(seq + " item " + codec.mapper().writeValueAsString(ItemJson.toMap(codec.mapper(), change.item())));
                }
            }
            for (TraceRecord record : trace.apply(fx.envelopes.get(i), fx.befores.get(i), fx.decisions.get(i))) {
                out.add(seq + " trace " + codec.mapper().writeValueAsString(TraceJson.toMap(codec.mapper(), record)));
            }
        }
        return out;
    }

    @Test
    void droppingTheHistoryChangesNothingAnyoneSees() throws Exception {
        TranscriptFixture dropping = run(WireFormatTest.fixture());
        TranscriptFixture keeping = run(WireFormatTest.fixture().keepingWholeHistory());

        var window = dropping.state().sessions().get(TranscriptFixture.SESSION);
        var whole = keeping.state().sessions().get(TranscriptFixture.SESSION);
        assertTrue(window.windowStart() > 0 && window.messages().size() < whole.messages().size(), "the scenario does drop: " + window.windowStart());
        assertEquals(whole.view(), window.view());
        // 去掉过历史时不知道原文在不在去掉的部分里，替换非空就记：多记的只会让读账的人多换一次本来就该换的
        assertTrue(window.withdrawn().containsAll(whole.withdrawn()), "every withdrawal is recorded for the journal readers");

        List<String> expected = outputs(keeping);
        List<String> actual = outputs(dropping);
        assertTrue(actual.stream().anyMatch(line -> line.contains("commands [CallModel") && line.contains("[gone]")),
                "the withdrawal reached a later request");
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), "output " + i);
        }
    }

    /** 只裁剪的压缩：工具结果太大、头部很小，在追加工具结果的同一步里压缩；这一步追加的留在窗口里，推送的条目一样。 */
    private static TranscriptFixture trimmed(TranscriptFixture fx) {
        RunId run = fx.start(WireFormatTest.block("q1"), WireFormatTest.SETTINGS);
        fx.feed(run, fx.reply(TranscriptFixture.call("c1", "read_file")));
        fx.feed(run, fx.finished(run, "c1", "y".repeat(30_000), false));
        WireFormatTest.assembled(fx, run, "sys", List.of());
        fx.feed(run, fx.reply(text("a1")));
        RunId next = WireFormatTest.prompt(fx, "q2", "sys", List.of());
        fx.feed(next, fx.reply(text("a2")));
        return fx;
    }

    @Test
    void aTrimInTheStepThatAppendedTheResultChangesNothingAnyoneSees() throws Exception {
        TranscriptFixture dropping = trimmed(new TranscriptFixture());
        TranscriptFixture keeping = trimmed(new TranscriptFixture().keepingWholeHistory());
        var window = dropping.state().sessions().get(TranscriptFixture.SESSION);
        assertTrue(window.windowStart() > 0 && window.messages().size() < keeping.state().sessions().get(TranscriptFixture.SESSION).messages().size(),
                "the trim does drop: " + window.windowStart());
        assertEquals(outputs(keeping), outputs(dropping));
    }
}
