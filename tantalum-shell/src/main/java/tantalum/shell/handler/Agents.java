package tantalum.shell.handler;

import java.util.List;
import tantalum.value.id.Principal;
import tantalum.value.model.ModelChoice;

/**
 * 任务能用的 agent 定义从哪来。定义在放出任务时读一次，内容写进 {@code TaskAssigned}，回放不读文件。
 * 没点名（{@code name} 为 null）用默认的 agent：没有另外的提示词，工具不另外限制，模型照放出它的会话。
 */
public interface Agents {

    /** 一个 agent：{@code tools} 为 null 是不另外限制，{@code model} 为 null 是照放出它的会话。 */
    record Agent(String name, String description, String instructions, List<String> tools, ModelChoice model) {}

    /** {@code agent} 为 null 时 {@code problem} 说明为什么（点了名却没找到时才有）。 */
    record Found(Agent agent, String problem) {}

    /** 没有任何定义：只有默认的 agent。 */
    Agents NONE = new Agents() {
        @Override
        public List<Agent> list(Principal user, String dir) {
            return List.of();
        }
    };

    /** 这个人现在有哪些 agent。{@code dir} 是会话的工作目录，没有为 null。 */
    List<Agent> list(Principal user, String dir);

    /**
     * 找点了名的 agent，连同它的提示词。{@code session} 是为任务开的会话（读文件的那一方据此记账）。
     * 这里可以去问用户的电脑，所以会等、会被中断。
     */
    default Found find(Principal user, tantalum.value.id.SessionId session, String name, String dir) throws InterruptedException {
        if (name == null) {
            return new Found(null, null);
        }
        List<Agent> known = list(user, dir);
        for (Agent agent : known) {
            if (agent.name().equals(name)) {
                return new Found(agent, null);
            }
        }
        String names = known.isEmpty() ? "none are defined" : String.join(", ", known.stream().map(Agent::name).toList());
        return new Found(null, "Not started: there is no agent named \"" + name + "\" (" + names
                + "). Start the task again without \"agent\" to use the general one.");
    }
}
