package tantalum.shell.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tantalum.local.wire.SkillList;
import tantalum.local.wire.SkillLoad;
import tantalum.local.wire.SkillLoaded;
import tantalum.shell.local.LocalLinks.Reply;
import tantalum.shell.local.LocalWorkspace;
import tantalum.value.event.SkillUse;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 提交时找用户点名的技能：文字里以空白为界的 {@code /name}。让电脑当场读，读到的内容随这次提交记下来，之后技能文件再改也不变。
 * 对不上任何技能的 {@code /name} 就是普通文字，不报错（DSH、pi 都是这样）。
 */
public final class SkillMentions {

    /** 电脑没按时回时记在 {@link SkillUse#error} 里的代码。 */
    public static final String TIMEOUT = "timeout";
    private static final Pattern MENTION = Pattern.compile("(?<!\\S)/([a-z0-9-]{1,64})(?!\\S)");

    private final LocalWorkspace local;
    private final SkillCatalog catalog;

    public SkillMentions(LocalWorkspace local, SkillCatalog catalog) {
        this.local = local;
        this.catalog = catalog;
    }

    /** 文字里的候选名字：按出现顺序、不重复，最多 {@link SkillLoad#MAX_NAMES} 个。 */
    static List<String> candidates(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(text);
        while (matcher.find() && names.size() < SkillLoad.MAX_NAMES) {
            names.add(matcher.group(1));
        }
        return List.copyOf(names);
    }

    /**
     * 这次提交点名的技能，按出现顺序。“技能”关着、电脑没连上、连接程序太旧、没填目录、没有候选时是空列表，不问电脑。
     * 电脑没按时回：候选里在上一次清单里能点名的，记成没读到（原因 {@link #TIMEOUT}）；别的当普通文字。
     */
    public List<SkillUse> read(Principal user, SessionId session, String text) throws InterruptedException {
        List<String> candidates = candidates(text);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Optional<String> dir = local.skillsDir(user);
        if (dir.isEmpty()) {
            return List.of();
        }
        Set<String> off = local.dirs().off(user);
        List<String> names = candidates.stream().filter(name -> !off.contains(name)).toList();
        if (names.isEmpty()) {
            return List.of();
        }
        Reply<SkillLoaded> reply = local.links().skillLoad(user, session.value(), dir.get(), names);
        return switch (reply.outcome()) {
            case OK -> loaded(names, reply.result());
            case NO_ANSWER -> late(user, names);
            case NOT_CONNECTED -> List.of();
        };
    }

    private static List<SkillUse> loaded(List<String> names, SkillLoaded result) {
        Map<String, SkillLoaded.Loaded> byName = new LinkedHashMap<>();
        result.items().forEach(item -> byName.putIfAbsent(item.name(), item));
        List<SkillUse> uses = new ArrayList<>();
        for (String name : names) {
            SkillLoaded.Loaded item = byName.get(name);
            if (item == null) {
                continue;
            }
            if (item.content() != null) {
                uses.add(new SkillUse(name, item.content(), null));
            } else if (item.code() != null) {
                uses.add(new SkillUse(name, null, item.code()));
            }
        }
        return uses;
    }

    private List<SkillUse> late(Principal user, List<String> names) {
        Set<String> known = catalog.last(user).map(SkillCatalog.Listing::skills).map(SkillList::items).orElse(List.of()).stream()
                .filter(SkillList.Item::user).map(SkillList.Item::name).collect(Collectors.toSet());
        return names.stream().filter(known::contains).map(name -> new SkillUse(name, null, TIMEOUT)).toList();
    }
}
