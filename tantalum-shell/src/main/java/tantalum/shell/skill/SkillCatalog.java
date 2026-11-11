package tantalum.shell.skill;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.local.wire.ContextResult;
import tantalum.local.wire.SkillList;
import tantalum.local.wire.WikiList;
import tantalum.shell.local.LocalLinks.Reply;
import tantalum.shell.local.LocalWorkspace;
import tantalum.value.id.Principal;

/**
 * 向用户的电脑要这次运行的清单：{@code AGENTS.md}、技能、wiki 页，一次往返。每个用户留最近一次要到的技能和 wiki，
 * 电脑没按时回就用它——不能因为一次没回来就告诉模型“技能都删了”。留的只在目录设置没变时才用。
 */
public final class SkillCatalog {

    /** 没问或没有的那一样为 null。 */
    public record Listing(SkillList skills, WikiList wiki, String agents) {

        static final Listing NONE = new Listing(null, null, null);
    }

    /** 留下的一份，连同当时问的两个目录。 */
    private record Kept(String skillsDir, String wikiDir, SkillList skills, WikiList wiki) {}

    private final LocalWorkspace local;
    private final Map<Principal, Kept> kept = new ConcurrentHashMap<>();

    public SkillCatalog(LocalWorkspace local) {
        this.local = local;
    }

    /** {@code sessionDir} 是会话的工作目录（读 {@code AGENTS.md} 用，没有为 null）。没连上、总开关关着时三样都没有。 */
    public Listing fetch(Principal user, String sessionDir) throws InterruptedException {
        String skillsDir = local.skillsDir(user).orElse(null);
        String wikiDir = local.wikiDir(user).orElse(null);
        Reply<ContextResult> reply = local.context(user, sessionDir);
        return switch (reply.outcome()) {
            case OK -> {
                ContextResult result = reply.result();
                // 没问的那一样不认：电脑多答的不进提示词
                SkillList skills = skillsDir == null ? null : result.skills();
                WikiList wiki = wikiDir == null ? null : result.wiki();
                kept.put(user, new Kept(skillsDir, wikiDir, skills, wiki));
                yield new Listing(skills, wiki, result.agents());
            }
            case NO_ANSWER -> {
                Kept last = kept.get(user);
                yield last == null ? Listing.NONE : new Listing(
                        Objects.equals(last.skillsDir(), skillsDir) ? last.skills() : null,
                        Objects.equals(last.wikiDir(), wikiDir) ? last.wiki() : null, null);
            }
            case NOT_CONNECTED -> Listing.NONE;
        };
    }

    /** 这个用户最近一次要到的技能和 wiki（不管目录设置后来变没变）。 */
    public Optional<Listing> last(Principal user) {
        Kept last = kept.get(user);
        return last == null ? Optional.empty() : Optional.of(new Listing(last.skills(), last.wiki(), null));
    }
}
