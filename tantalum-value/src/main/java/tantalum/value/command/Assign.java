package tantalum.value.command;

import java.util.Objects;
import tantalum.value.event.Root;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.ModelChoice;

/**
 * 把一次交代送到接它的会话 {@code session}：处理器读 agent 定义，建帮手会话，往里放一条 {@code Assignment}。
 * {@code agent} 是模型点的名字（没点为 null）；{@code dir}、{@code offRecord} 照交代它的会话。
 * {@code model} 是交代它的那一轮用的模型：帮手用同一个，除非 agent 的定义另外指定。{@code root} 是交代它的那一轮的。
 */
public record Assign(CommandId id, RunRef run, String assignment, SessionId session, String agent, String prompt, String dir,
                     boolean offRecord, ModelChoice model, Root root) implements Command {
    public Assign {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(root, "root");
    }
}
