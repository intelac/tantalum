package tantalum.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tantalum.shell.plugin.PluginServices;
import tantalum.shell.plugin.ToolContext;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/**
 * 对话里的原话（memory-forget-by-choice R2）：程序只把可能说着它的行列出来；选中的行在它所在的会话、和别处逐字引用它的会话里撤下；
 * 没选的不动；编号对不上的照实说没办。
 */
class TalkTest {

    private static final Principal ALICE = new Principal("alice");

    /** 两个会话：s1 里你说了车牌（和一句不相干的），s2 里一次历史搜索的结果逐字引用了它。记下平台被要求撤下了什么。 */
    private static final class Platform implements PluginServices {
        final List<ConversationLine> rows = List.of(
                new ConversationLine(new SessionId("s1"), 3, "user", "帮我记一下\n我的车牌是 京A-7Q2KD\n另外周三要去物业"),
                new ConversationLine(new SessionId("s1"), 5, "assistant", "好的，记下了。"),
                new ConversationLine(new SessionId("s2"), 9, "tool", "history_search: - [s1 · user] 帮我记一下 我的车牌是 京A-7Q2KD 另外周三要去物业"));
        final List<String> sent = new ArrayList<>();

        @Override public List<ConversationLine> conversationLines(Principal user, String words, boolean tools, int limit) {
            return rows.stream().filter(row -> row.text().contains(words) && (tools || !row.role().equals("tool"))).toList();
        }
        @Override public Optional<ConversationLine> conversationLine(Principal user, SessionId session, long seq) {
            return rows.stream().filter(row -> row.session().equals(session) && row.seq() == seq).findFirst();
        }
        @Override public boolean withdraw(Principal user, SessionId session, List<String> originals, ToolContext origin) {
            sent.add(session.value() + (origin == null ? " by user " : " by agent ") + originals);
            return true;
        }
        @Override public Path dataDir(String plugin, String name, Principal user) { return null; }
        @Override public Path stateDir(String plugin, Principal user) { return null; }
        @Override public Optional<String> complete(String plugin, String system, String user, ModelPick pick, int maxTokens) { return Optional.empty(); }
        @Override public Json.Obj params(String plugin, Principal user) { return new Json.Obj(java.util.Map.of()); }
        @Override public boolean enabled(String plugin, Principal user) { return true; }
        @Override public List<ConversationHit> searchConversations(Principal user, String query, SessionId excluding, int limit) { return List.of(); }
    }

    @Test
    void linesThatMaySayItAreOnlyOfferedAndTheChosenOneIsWithdrawnWhereverItIsQuoted() {
        Platform platform = new Platform();
        List<Talk.Line> offered = Talk.candidates(platform, ALICE, "我的车牌号 京A-7Q2KD");
        assertEquals(List.of("我的车牌是 京A-7Q2KD"), offered.stream().map(Talk.Line::text).toList(), "the unrelated lines are not offered");
        assertEquals("user", offered.getFirst().role());
        assertTrue(platform.sent.isEmpty(), "looking changes nothing");

        Talk.Done done = Talk.withdraw(platform, ALICE, List.of(offered.getFirst().id(), "talk:3#9@s1", "talk:77#0@nowhere", "nonsense"), null);
        assertEquals(List.of("s1 by user [我的车牌是 京A-7Q2KD]", "s2 by user [我的车牌是 京A-7Q2KD]"), platform.sent,
                "its own conversation, and the one whose tool result quotes it word for word");
        assertEquals(2, done.withdrawn());
        assertEquals(List.of("talk:3#9@s1", "talk:77#0@nowhere"), done.stale(), "ids that point at nothing are reported; other kinds of ids are not ours");
    }

    @Test
    void aLineRemovedFromMemoryIsWithdrawnWhereItAppearsWordForWord() {
        Platform platform = new Platform();
        assertEquals(2, Talk.follow(platform, ALICE, List.of("我的车牌是 京A-7Q2KD", "好"), null), "a line too short to be safe is skipped");
        assertEquals(List.of("s1 by user [我的车牌是 京A-7Q2KD]", "s2 by user [我的车牌是 京A-7Q2KD]"), platform.sent);
        assertEquals(0, Talk.follow(null, ALICE, List.of("我的车牌是 京A-7Q2KD"), null), "without the platform only memory is handled");
    }
}
