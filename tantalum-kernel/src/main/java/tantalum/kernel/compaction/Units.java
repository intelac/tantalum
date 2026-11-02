package tantalum.kernel.compaction;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.ToolResultBlock;

/**
 * 把视图切成不可分的单元（规则 4）：一条用户消息是一个单元；一条助手消息连同紧随其后、只含工具结果的用户消息是一个单元。
 * 单元用它在视图里的起止下标表示，{@code to} 不含。
 */
public final class Units {

    public record Unit(int from, int to) {}

    private Units() {}

    public static List<Unit> split(List<Message> view) {
        List<Unit> units = new ArrayList<>();
        int i = 0;
        while (i < view.size()) {
            int from = i;
            i++;
            if (view.get(from).role() == Role.ASSISTANT) {
                while (i < view.size() && isToolResults(view.get(i))) {
                    i++;
                }
            }
            units.add(new Unit(from, i));
        }
        return List.copyOf(units);
    }

    /** 只含工具结果块的用户消息：它属于前面那条助手消息。 */
    public static boolean isToolResults(Message message) {
        if (message.role() != Role.USER || message.content().isEmpty()) {
            return false;
        }
        for (ContentBlock block : message.content()) {
            if (!(block instanceof ToolResultBlock)) {
                return false;
            }
        }
        return true;
    }
}
