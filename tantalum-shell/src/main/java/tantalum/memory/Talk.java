package tantalum.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.memory.recall.Overlap;
import tantalum.memory.recall.Terms;
import tantalum.shell.plugin.PluginServices;
import tantalum.shell.plugin.ToolContext;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 忘掉的另一半：过去对话里的原话（memory-forget-by-choice R2）。记忆账里去掉一行，只是记忆不再有它；你当时说的那句话还在会话里，
 * 模型接着那个会话聊、或者用 {@code history_search} 还能看到。这里把对话里可能说着它的行找出来当候选，选中的请平台撤下
 * （{@link PluginServices#withdraw}：模型以后不再看到，记录页和搜索也不再给出；原文还在账里，不是删除）。
 * <p>和记忆账那一半一样：词重合只用来找候选，选不选由模型或用户定，程序只照选中的办。
 */
public final class Talk {

    private Talk() {}

    static final String PREFIX = "talk:";
    static final int MAX_LINES = 12;
    /** 太短的行不拿来撤（“好”“ok”这样的会把整个会话里同样的字都换掉）。 */
    static final int MIN_CHARS = 3;
    private static final Pattern ID = Pattern.compile("talk:(\\d{1,18})#(\\d{1,6})@(.+)");

    /** 对话里的一处：某个会话某一条消息的某一行。{@code id} 是 {@code talk:序号#第几行@会话}；{@code role} 是谁说的。 */
    public record Line(String id, String session, String role, String text) {}

    /** 照选中的撤下的结果：送出去几处（一行在几个会话里出现就算几处）；{@code stale} 是没办的编号（那一条已经不在、已经撤下过，或者写得不对）。 */
    public record Done(int withdrawn, List<String> stale) {}

    static boolean is(String place) {
        return place != null && place.startsWith(PREFIX);
    }

    /** 过去对话里（含当前会话）可能说着这句话的行：你说的和助手说的，和它词重合达到找候选的门槛的。只是检索。 */
    public static List<Line> candidates(PluginServices platform, Principal user, String text) {
        if (platform == null || text == null || text.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>(Terms.of(text));
        if (terms.isEmpty()) {
            terms.add(text.strip());
        }
        Map<String, Line> found = new LinkedHashMap<>();
        for (String term : terms.subList(0, Math.min(6, terms.size()))) {
            for (PluginServices.ConversationLine row : platform.conversationLines(user, term, false, 30)) {
                String[] lines = row.text().split("\n");
                for (int i = 0; i < lines.length && found.size() < MAX_LINES; i++) {
                    String line = lines[i].strip();
                    if (usable(line) && Overlap.of(text, line) >= Forgetting.LOOSE) {
                        String id = PREFIX + row.seq() + "#" + i + "@" + row.session().value();
                        found.putIfAbsent(id, new Line(id, row.session().value(), row.role(), line));
                    }
                }
            }
        }
        return List.copyOf(found.values());
    }

    private static boolean usable(String line) {
        return line.codePointCount(0, line.length()) >= MIN_CHARS && !line.contains(PluginServices.WITHDRAWN);
    }

    /** 撤下选中的行（{@code places} 里 {@code talk:} 开头的；别的不看）。{@code origin} 是模型在哪次调用里选的，用户自己选的为 null。 */
    public static Done withdraw(PluginServices platform, Principal user, List<String> places, ToolContext origin) {
        Map<SessionId, Set<String>> bySession = new LinkedHashMap<>();
        List<String> stale = new ArrayList<>();
        for (String place : places) {
            if (!is(place)) {
                continue;
            }
            Matcher id = ID.matcher(place);
            String line = null;
            SessionId session = null;
            if (platform != null && id.matches()) {
                session = new SessionId(id.group(3));
                int index = Integer.parseInt(id.group(2));
                line = platform.conversationLine(user, session, Long.parseLong(id.group(1)))
                        .map(row -> row.text().split("\n")).filter(lines -> index < lines.length).map(lines -> lines[index].strip())
                        .filter(Talk::usable).orElse(null);
            }
            if (line == null) {
                stale.add(place);
            } else {
                bySession.computeIfAbsent(session, key -> new LinkedHashSet<>()).add(line);
                // 同一行字在别的会话里被逐字引用过的地方（比如一次 history_search 的结果）也撤下：逐字相同是事实
                spread(platform, user, line, bySession);
            }
        }
        return new Done(send(platform, user, bySession, origin), stale);
    }

    /**
     * 从记忆里去掉的行，对话里逐字写着它的地方也撤下：模型在哪个会话里忘的那个会话（它的上下文里正放着这一行），
     * 和别的逐字出现过它的会话（比如当时写进记忆的那次工具调用）。逐字相同是事实，不用再选。
     */
    public static int follow(PluginServices platform, Principal user, List<String> removed, ToolContext origin) {
        if (platform == null) {
            return 0;
        }
        Map<SessionId, Set<String>> bySession = new LinkedHashMap<>();
        for (String text : removed) {
            for (String each : text.split("\n")) {
                String line = each.strip();
                if (!usable(line)) {
                    continue;
                }
                if (origin != null) {
                    bySession.computeIfAbsent(origin.session(), key -> new LinkedHashSet<>()).add(line);
                }
                spread(platform, user, line, bySession);
            }
        }
        return send(platform, user, bySession, origin);
    }

    /** 这一行字逐字出现过的会话（你说的、助手说的、工具结果里的）都记上它。 */
    private static void spread(PluginServices platform, Principal user, String line, Map<SessionId, Set<String>> bySession) {
        for (PluginServices.ConversationLine row : platform.conversationLines(user, line, true, 50)) {
            bySession.computeIfAbsent(row.session(), key -> new LinkedHashSet<>()).add(line);
        }
    }

    private static int send(PluginServices platform, Principal user, Map<SessionId, Set<String>> bySession, ToolContext origin) {
        int sent = 0;
        for (Map.Entry<SessionId, Set<String>> each : bySession.entrySet()) {
            if (platform.withdraw(user, each.getKey(), List.copyOf(each.getValue()), origin)) {
                sent += each.getValue().size();
            }
        }
        return sent;
    }
}
