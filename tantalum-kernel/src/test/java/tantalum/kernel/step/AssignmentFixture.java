package tantalum.kernel.step;

import tantalum.kernel.step.AssignmentViews.Assign;

import java.util.Map;
import tantalum.kernel.LoopFixture;
import tantalum.value.assignment.AssignmentTools;
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

    /** 这一步的命令，送达按送的东西换成 {@link AssignmentViews} 里的样子；回执和确认定时器不算。 */
    static java.util.List<Object> commands(Decision decision) {
        java.util.List<Object> all = new java.util.ArrayList<>();
        for (tantalum.value.command.Command command : decision.commands()) {
            if (!LoopFixture.bookkeeping(command)) {
                all.add(command instanceof tantalum.value.command.Deliver deliver ? view(deliver) : command);
            }
        }
        return all;
    }

    static java.util.List<Object> views(java.util.List<tantalum.value.command.Command> commands) {
        return commands.stream().map(command -> command instanceof tantalum.value.command.Deliver deliver ? view(deliver) : (Object) command).toList();
    }

    static Object view(tantalum.value.command.Deliver deliver) {
        return switch (deliver.what()) {
            case tantalum.value.delivery.AssignmentParcel given -> new Assign(deliver.id(), deliver.run(), given.assignment(), deliver.to(),
                    given.agent(), given.input().prompt(), given.dir(), given.offRecord(), given.input().model(), given.input().root());
            case tantalum.value.delivery.ReportParcel back -> new AssignmentViews.ReturnReport(deliver.id(), deliver.run(), deliver.to(),
                    back.assignment(), back.input().outcome(), back.input().text(), back.input().root());
            case tantalum.value.delivery.StopParcel stop -> new AssignmentViews.StopAssignment(deliver.id(), deliver.run(), stop.assignment(), deliver.to());
        };
    }

    /** 帮手交回的结果：来处是这次交代的帮手会话（交代方是 {@code LoopFixture.SESSION}）。 */
    static tantalum.value.event.Report report(String assignment, tantalum.value.assignment.AssignmentOutcome outcome, String text,
                                              tantalum.value.event.Root root) {
        return new tantalum.value.event.Report(assignment, outcome, text, root, tantalum.value.id.SessionIds.helper(LoopFixture.SESSION, assignment));
    }

    /** 交代方送来的叫停。 */
    static tantalum.value.event.AssignmentCancelled cancelled(String assignment) {
        return new tantalum.value.event.AssignmentCancelled(assignment, LoopFixture.SESSION);
    }

    /** 把一步里的回执（{@code Acknowledge}）像处理器那样送回对方：送到了是 {@code Delivered}，否则 {@code Refused}。 */
    static void acknowledge(LoopFixture loop, Decision decision) {
        for (tantalum.value.command.Command command : decision.commands()) {
            if (command instanceof tantalum.value.command.Acknowledge ack) {
                loop.feed(ack.to(), null, ack.refused() == null ? new tantalum.value.event.Delivered(ack.delivery())
                        : new tantalum.value.event.Refused(ack.delivery(), ack.refused()));
            }
        }
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
        Decision arrived = loop.feed(start.session(), null, assignmentOf(start));
        // 帮手回“送到了”，交代方划掉信箱里的这一条（delivery）；返回的仍是放进交代的那一步
        acknowledge(loop, arrived);
        // 测试接着看帮手这一步发的命令（它开的那一轮要组装上下文）
        loop.last = arrived;
        return arrived;
    }
}
