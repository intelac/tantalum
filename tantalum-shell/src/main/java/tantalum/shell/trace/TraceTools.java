package tantalum.shell.trace;

import java.time.Instant;
import java.util.List;
import tantalum.kernel.step.CancelSteps;
import tantalum.kernel.tool.ToolPairing;
import tantalum.shell.trace.TracePass.Place;
import tantalum.shell.trace.TraceRecord.ModelRequest;
import tantalum.shell.trace.TraceRecord.ToolCall;
import tantalum.shell.trace.TraceRecord.ToolCall.Status;
import tantalum.value.command.RunTool;
import tantalum.value.decision.Fact;
import tantalum.value.decision.HookDecided;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.RunId;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.Ended;
import tantalum.value.state.RunState;
import tantalum.value.state.ToolSlot;

/** 工具记录：回复里的调用、发出、结果、拦截、运行提前结束时的合成结果、并行组、MCP 拆名。 */
final class TraceTools {

    /** 参数不是合法 JSON：内核直接给错误结果，不过钩子。 */
    static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    private static final String MCP_PREFIX = "mcp__";

    private final TracePass pass;

    TraceTools(TracePass pass) {
        this.pass = pass;
    }

    /** {@code mcp__<服务器>__<工具>} 取服务器名；名字被截短加哈希的，取到的只是公开名的前缀。不是 MCP 工具时为空。 */
    static String server(String name) {
        if (!name.startsWith(MCP_PREFIX)) {
            return null;
        }
        int end = name.indexOf("__", MCP_PREFIX.length());
        return end <= MCP_PREFIX.length() ? null : name.substring(MCP_PREFIX.length(), end);
    }

    /** 回复里的每个调用一条，ID 和对话页工具步骤的条目 ID（序号.块下标）对应。 */
    void created(ModelReplied reply, ModelRequest request) {
        RunId run = request.head().run();
        List<ContentBlock> content = reply.content();
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i) instanceof ToolCallBlock call) {
                Place place = pass.current(run);
                String id = "t:" + pass.seq + "." + i;
                pass.add(new ToolCall(pass.head(id, run, place, pass.at), call.id(), call.name(), server(call.name()), call.rawArguments(),
                        call.arguments(), Status.WAITING, null, null, null, null, null, null, request.id()), place, true);
            }
        }
    }

    /** 还没结束的工具记录 ID，没有时为空。 */
    String idOf(String callId) {
        ToolCall tool = open(callId);
        return tool == null ? null : tool.id();
    }

    private ToolCall open(String callId) {
        for (TraceRecord record : pass.book.open.values()) {
            if (record instanceof ToolCall tool && tool.call().equals(callId)) {
                return tool;
            }
        }
        return null;
    }

    /** 同一事件发出的调用是一组并行调用，组名取事件序号，和对话页一致。 */
    void dispatched(RunTool command) {
        ToolCall tool = open(command.call().id());
        if (tool != null) {
            pass.put(with(tool, Status.RUNNING, null, null, null, "g" + pass.seq, pass.at, null), true);
        }
    }

    /** 出错时原样保留结果文字，包括只有空白的文字。 */
    void finished(ToolFinished finished) {
        ToolCall tool = open(finished.toolCallId());
        if (tool != null && tool.status() == Status.RUNNING && pass.awaited(finished)) {
            ToolCall done = with(tool, finished.error() ? Status.ERROR : Status.DONE, finished.text(), null, null, tool.group(),
                    tool.issuedAt(), finished.startedAt());
            pass.put(done.withHead(tool.head().ended(pass.at)), false);
        }
    }

    /** 被钩子拦下或参数无效的调用在工具轮里直接带着结果，没有自己的事件。 */
    void blocked() {
        for (var entry : pass.book.runs.entrySet()) {
            if (!(pass.state.runs().get(entry.getKey()) instanceof RunState run) || run.tools() == null) {
                continue;
            }
            for (ToolSlot slot : run.tools().slots()) {
                ToolCall tool = open(slot.call().id());
                if (slot.status() == ToolSlot.Status.BLOCKED && tool != null) {
                    block(tool, slot.result());
                }
            }
        }
    }

    /** 工具轮结束时结果追加成一条消息。还没定下来的只剩：整轮被拦下，或运行提前结束时内核补的合成结果（{@link ToolPairing}）。 */
    void appended() {
        for (Message message : pass.appended()) {
            if (message.role() != Role.USER) {
                continue;
            }
            for (ContentBlock block : message.content()) {
                if (!(block instanceof ToolResultBlock result) || open(result.toolCallId()) == null) {
                    continue;
                }
                ToolCall tool = open(result.toolCallId());
                Ended end = pass.state.runs().get(tool.head().run()) instanceof RunState run && run.phase() instanceof Ended ended
                        ? ended : null;
                if (ToolPairing.OUTCOME_UNKNOWN.equals(result.code())) {
                    String why = end != null && CancelSteps.INTERRUPTED_BY_RESTART.equals(end.detail()) ? "restart" : "run ended";
                    settle(tool, Status.UNKNOWN, result, why);
                } else if (ToolPairing.ABORTED_BEFORE_DISPATCH.equals(result.code())) {
                    String why = end == null ? null : end.reason() + (end.detail() == null ? "" : ": " + end.detail());
                    settle(tool, Status.NOT_SENT, result, why);
                } else if (result.code() == null) {
                    block(tool, result);
                } else {
                    settle(tool, Status.ERROR, result, null);
                }
            }
        }
    }

    /** 被拦下：原因取拦下它的钩子（工具调用前，或工作结果后）的理由；参数无效没有钩子，单独给错误码。 */
    private void block(ToolCall tool, ToolResultBlock result) {
        if (tool.arguments() == null) {
            ToolCall blocked = with(tool, Status.BLOCKED, result.text(), INVALID_ARGUMENTS, null, tool.group(), tool.issuedAt(),
                    null);
            pass.put(blocked.withHead(tool.head().ended(pass.at)), false);
            return;
        }
        String own = null;
        String afterWork = null;
        for (Fact fact : pass.decision.facts()) {
            if (fact instanceof HookDecided decided && decided.kind() == HookDecided.Kind.STOP) {
                if (tool.call().equals(decided.toolCall())) {
                    own = decided.reason();
                } else if (decided.point() == HookPoint.ON_WORK_RESULT) {
                    afterWork = decided.reason();
                }
            }
        }
        settle(tool, Status.BLOCKED, result, own != null ? own : afterWork);
    }

    private void settle(ToolCall tool, Status status, ToolResultBlock result, String reason) {
        ToolCall settled = with(tool, status, result.text(), result.code(), reason, tool.group(), tool.issuedAt(), null);
        pass.put(settled.withHead(tool.head().ended(pass.at)), false);
    }

    /** 运行结束后还没定下来的工具不会再有结果（内核不处理迟到的结果）。 */
    void runEnded(RunId run) {
        pass.book.open.values().removeIf(record -> record instanceof ToolCall && record.head().run().equals(run));
    }

    private static ToolCall with(ToolCall tool, Status status, String result, String code, String reason, String group,
                                 Instant issuedAt, Instant startedAt) {
        return new ToolCall(tool.head(), tool.call(), tool.name(), tool.server(), tool.rawArguments(), tool.arguments(), status, result,
                code, reason, group, issuedAt, startedAt, tool.request());
    }
}
