package tantalum.kernel.table;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** {@code (状态名, 事件类型)} 到处理函数的注册表。同一行只能注册一次，内置行也不可覆盖。 */
public final class TransitionTable {

    /** 匹配任意状态的行，精确匹配优先。 */
    public static final String ANY_STATE = "*";

    private record Row(String state, String eventType) {}

    private final Map<Row, StepFunction> rows = new HashMap<>();

    public void register(String state, String eventType, StepFunction step) {
        if (rows.putIfAbsent(new Row(state, eventType), step) != null) {
            throw new IllegalStateException("transition already registered: (" + state + ", " + eventType + ")");
        }
    }

    public Optional<StepFunction> find(String state, String eventType) {
        StepFunction exact = rows.get(new Row(state, eventType));
        if (exact != null) {
            return Optional.of(exact);
        }
        return Optional.ofNullable(rows.get(new Row(ANY_STATE, eventType)));
    }
}
