package tantalum.shell.skill;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import tantalum.local.wire.SkillList;
import tantalum.local.wire.SkillLoaded;
import tantalum.shell.local.LocalLinks.Reply;
import tantalum.shell.local.LocalWorkspace;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.model.ModelChoice;

/**
 * 任务的 agent 就是用户自己的技能（subagent 需求 8）：{@code task(prompt, agent="名字")} 让那个任务照这个技能的写法做事。
 * 技能在用户电脑上的技能目录里，经连接程序读，和用户用 {@code /名字} 点名技能是同一条路；所以关掉的技能、不让点名的技能不能当 agent。
 * 技能的属性块里可以多写两样：{@code tools: read_file, grep}（这个任务只能用这些工具）、{@code model: 路线/模型}。
 * 内容在放出任务时读一次，写进账里，之后技能文件再改也不影响这个任务。
 */
public final class SkillAgents implements Agents {

    private final LocalWorkspace local;
    private final SkillCatalog catalog;

    public SkillAgents(LocalWorkspace local, SkillCatalog catalog) {
        this.local = local;
        this.catalog = catalog;
    }

    /** 上一次清单里能当 agent 的技能（用户能点名、没关掉的）。不为这个去问电脑。 */
    @Override
    public List<Agent> list(Principal user, String dir) {
        Set<String> off = local.dirs().off(user);
        return catalog.last(user).map(SkillCatalog.Listing::skills).map(SkillList::items).orElse(List.of()).stream()
                .filter(item -> item.user() && !off.contains(item.name()))
                .map(item -> new Agent(item.name(), item.description(), null, item.tools(), model(item.taskModel()))).toList();
    }

    @Override
    public Found find(Principal user, SessionId session, String name, String dir) throws InterruptedException {
        if (name == null) {
            return new Found(null, null);
        }
        Optional<String> skills = local.skillsDir(user);
        if (skills.isEmpty() || local.dirs().off(user).contains(name)) {
            return missing(user, name, dir);
        }
        Reply<SkillLoaded> reply = local.links().skillLoad(user, session.value(), skills.get(), List.of(name));
        if (reply.outcome() != Reply.Outcome.OK) {
            return new Found(null, "Not started: the user's computer did not answer when reading the skill \"" + name
                    + "\". Start the task again, or without \"agent\" to use the general one.");
        }
        String content = reply.result().items().stream().filter(item -> item.name().equals(name)).map(SkillLoaded.Loaded::content)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (content == null) {
            return missing(user, name, dir);
        }
        Agent listed = list(user, dir).stream().filter(agent -> agent.name().equals(name)).findFirst().orElse(null);
        return new Found(new Agent(name, listed == null ? "" : listed.description(), content, listed == null ? null : listed.tools(),
                listed == null ? null : listed.model()), null);
    }

    private Found missing(Principal user, String name, String dir) {
        List<String> known = list(user, dir).stream().map(Agent::name).toList();
        return new Found(null, "Not started: there is no skill named \"" + name + "\" to use as the agent ("
                + (known.isEmpty() ? "the user has no skills" : String.join(", ", known))
                + "). Start the task again without \"agent\" to use the general one.");
    }

    /** {@code 路线/模型}；写得不对就当没写，用放出任务的会话的模型。 */
    static ModelChoice model(String written) {
        if (written == null) {
            return null;
        }
        int slash = written.indexOf('/');
        if (slash <= 0 || slash == written.length() - 1) {
            return null;
        }
        return new ModelChoice(written.substring(0, slash).strip(), written.substring(slash + 1).strip(), null);
    }
}
