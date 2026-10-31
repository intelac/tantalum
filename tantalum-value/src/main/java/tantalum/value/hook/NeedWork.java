package tantalum.value.hook;

import java.util.Objects;
import tantalum.value.json.Json;

/** 需要先做 I/O。内核据此发 {@code DoWork} 命令并进入等待。 */
public record NeedWork(String workType, Json input) implements HookResult {

    public NeedWork {
        Objects.requireNonNull(workType, "workType");
        Objects.requireNonNull(input, "input");
    }
}
