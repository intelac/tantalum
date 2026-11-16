package tantalum.value.event;

import java.util.List;
import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.model.ModelChoice;

/**
 * 交活（kernel-delegation）：父运行把一件事交给这个会话，进子会话的账。它不是 {@link UserPrompt}：账里看得出这句话是父运行交代的，
 * 不是用户说的。{@code delegation} 是委派号（父运行那次 {@code delegate} 调用的命令号），一次交活恰好对应一次交回
 * （{@link DelegateReturned}）；同一个委派号再送来（重启后重发）不重做。{@code from} 是交活的父运行。{@code kind}：新活、
 * 接着做（给已有的子会话再交一件）、回答子会话问的问题（{@code question} 是问题 id）。{@code depth} 是子会话在第几层（父会话加一）。
 * {@code model}、{@code dir}、{@code offRecord}、{@code tools}（限定的工具，null 是不限定）只在子会话第一次收到交活时定下。
 */
public record Delegated(CommandId delegation, RunRef from, Kind kind, String text, String question, int depth,
                        ModelChoice model, String dir, Boolean offRecord, List<String> tools) implements Event {

    public static final String TYPE = "Delegated";

    public enum Kind { NEW, CONTINUE, ANSWER }

    public Delegated {
        Objects.requireNonNull(delegation, "delegation");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        tools = tools == null ? null : List.copyOf(tools);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
