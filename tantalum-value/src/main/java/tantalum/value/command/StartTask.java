package tantalum.value.command;

import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/**
 * 把一个任务送到为它开的会话 {@code session}：处理器读 agent 定义，往那个会话放一条 {@code TaskAssigned}。
 * {@code agent} 是模型点的名字（没点为 null）；{@code dir}、{@code offRecord} 照放出它的会话。
 */
public record StartTask(CommandId id, RunRef run, String task, SessionId session, String agent, String prompt, String dir,
                        boolean offRecord) implements Command {

    public StartTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(prompt, "prompt");
    }
}
