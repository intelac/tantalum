package tantalum.value.command;

import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.ModelChoice;

/**
 * 把一个任务送到为它开的会话 {@code session}：处理器读 agent 定义，往那个会话放一条 {@code TaskAssigned}。
 * {@code agent} 是模型点的名字（没点为 null）；{@code dir}、{@code offRecord} 照放出它的会话。
 * {@code model} 是放出它的那一轮用的模型：任务用同一个，除非 agent 的定义另外指定。不带上的话任务会落到默认路线上。
 */
public record StartTask(CommandId id, RunRef run, String task, SessionId session, String agent, String prompt, String dir,
                        boolean offRecord, ModelChoice model) implements Command {

    public StartTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(prompt, "prompt");
    }
}
