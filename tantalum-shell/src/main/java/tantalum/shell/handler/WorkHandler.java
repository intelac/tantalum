package tantalum.shell.handler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import tantalum.shell.access.PluginGate;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.tool.Workspaces;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.event.Event;
import tantalum.value.event.WorkDone;
import tantalum.value.json.Json;

/** 按类型执行插件工作。和工具一样有超时：一个阻塞的插件不能永久占住命令。要等人的事不在这里做，钩子返回 {@code Ask}。 */
public final class WorkHandler implements Handler {

    private final Map<String, WorkType> types = new HashMap<>();
    private final Workspaces workspaces;
    private final PluginGate gate;
    private final Duration timeout;
    private final Clock clock;

    /** 工作类型取自 {@code gate}：插件页上列出的和这里能执行的是同一份。 */
    public WorkHandler(Workspaces workspaces, PluginGate gate, Duration timeout, Clock clock) {
        this.workspaces = workspaces;
        this.gate = gate;
        this.timeout = timeout;
        this.clock = clock;
        for (WorkType type : gate.workTypes()) {
            if (types.putIfAbsent(type.name(), type) != null) {
                throw new IllegalStateException("work type already registered: " + type.name());
            }
        }
    }

    @Override
    public Event handle(Command command) throws Exception {
        DoWork work = (DoWork) command;
        WorkType type = types.get(work.workType());
        if (type == null) {
            return failed(work, "unknown work type: " + work.workType(), null);
        }
        ToolContext context = new ToolContext(work.run().principal(), work.run().session(), workspaces.of(work.run().principal()));
        // 工作没有名额，几乎等于发出时刻；记下它是为了和模型、工具的计时一致。
        Instant startedAt = clock.instant();
        try {
            WorkResult done = Deadline.call(() -> gate.runWork(context, type, work.input()), timeout);
            return new WorkDone(work.id(), work.workType(), done.result(), false, done.continueText(), startedAt, done.note());
        } catch (TimeoutException late) {
            return failed(work, "work timed out after " + timeout, startedAt);
        } catch (ExecutionException failure) {
            return failed(work, failure.getCause().toString(), startedAt);
        }
    }

    private static WorkDone failed(DoWork work, String detail, Instant startedAt) {
        return new WorkDone(work.id(), work.workType(), new Json.Str(detail), true, null, startedAt);
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return failed((DoWork) command, failure.toString(), null);
    }
}
