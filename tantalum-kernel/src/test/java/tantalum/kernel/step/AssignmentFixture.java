package tantalum.kernel.step;

import java.util.Map;
import tantalum.kernel.LoopFixture;
import tantalum.value.assignment.AssignmentTools;
import tantalum.value.command.Assign;
import tantalum.value.decision.Decision;
import tantalum.value.event.Assignment;
import tantalum.value.event.SessionCreated;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.HelperOf;
import tantalum.value.state.SessionKind;

/** 交代的测试共用的几样：模型调 {@code task}、{@code task_stop} 的样子，和外壳的处理器把一次交代送到时做的两件事。 */
final class AssignmentFixture {

    private AssignmentFixture() {}

    static ToolCallBlock task(String callId, String prompt) {
        return new ToolCallBlock(callId, AssignmentTools.TASK, "{}", new Json.Obj(Map.of(AssignmentTools.PROMPT, new Json.Str(prompt))));
    }

    static ToolCallBlock stop(String callId, String id) {
        return new ToolCallBlock(callId, AssignmentTools.TASK_STOP, "{}", new Json.Obj(Map.of(AssignmentTools.ID, new Json.Str(id))));
    }

    /** 建帮手会话的那个事件：出身照 {@code Assign} 命令。 */
    static SessionCreated helperFor(Assign start) {
        return new SessionCreated(SessionKind.HELPER, start.dir(), start.offRecord(),
                new HelperOf(start.run().session(), start.assignment(), start.agent(), null, null));
    }

    /** 放进帮手会话的那条交代。 */
    static Assignment assignmentOf(Assign start) {
        return new Assignment(start.assignment(), start.run().session(), start.root(), start.prompt(), start.model());
    }

    /** 像外壳的处理器那样把交代送到：建帮手会话，放进那条交代。返回放进交代的那一步。 */
    static Decision deliver(LoopFixture loop, Assign start) {
        loop.create(start.session(), helperFor(start));
        return loop.feed(start.session(), null, assignmentOf(start));
    }
}
