package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.id.RunRef;

/** 钩子抛了异常。内核不能写日志，所以用事实上报。 */
public record HookReported(RunRef run, String hook, String detail) implements Fact {

    public HookReported {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(hook, "hook");
    }

    @Override
    public String type() {
        return "HookReported";
    }
}
