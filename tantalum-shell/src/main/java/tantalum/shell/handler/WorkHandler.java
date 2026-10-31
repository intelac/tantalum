package tantalum.shell.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.tool.Workspaces;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.event.Event;
import tantalum.value.event.WorkDone;
import tantalum.value.json.Json;

/** 按类型执行插件工作。 */
public final class WorkHandler implements Handler {

    private final Map<String, WorkType> types = new HashMap<>();
    private final Workspaces workspaces;

    public WorkHandler(Workspaces workspaces, List<WorkType> workTypes) {
        this.workspaces = workspaces;
        for (WorkType type : workTypes) {
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
            return new WorkDone(work.id(), work.workType(), new Json.Str("unknown work type: " + work.workType()), true, null);
        }
        ToolContext context = new ToolContext(work.run().principal(), workspaces.of(work.run().principal()));
        WorkResult result = type.run(context, work.input());
        return new WorkDone(work.id(), work.workType(), result.result(), false, result.continueText());
    }

    @Override
    public Event failed(Command command, Exception failure) {
        DoWork work = (DoWork) command;
        return new WorkDone(work.id(), work.workType(), new Json.Str(failure.toString()), true, null);
    }
}
