package tantalum.shell.handler;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.tool.Workspaces;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.event.Event;
import tantalum.value.event.WorkDone;
import tantalum.value.json.Json;

/** 按类型执行插件工作。和工具一样有超时：一个阻塞的插件不能永久占住命令。 */
public final class WorkHandler implements Handler {

    private final Map<String, WorkType> types = new HashMap<>();
    private final Workspaces workspaces;
    private final Duration timeout;

    public WorkHandler(Workspaces workspaces, List<WorkType> workTypes, Duration timeout) {
        this.workspaces = workspaces;
        this.timeout = timeout;
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
            return failed(work, "unknown work type: " + work.workType());
        }
        ToolContext context = new ToolContext(work.run().principal(), workspaces.of(work.run().principal()));
        CompletableFuture<WorkResult> result = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                result.complete(type.run(context, work.input()));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            WorkResult done = result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return new WorkDone(work.id(), work.workType(), done.result(), false, done.continueText());
        } catch (TimeoutException late) {
            worker.interrupt();
            return failed(work, "work timed out after " + timeout);
        } catch (InterruptedException cancelled) {
            worker.interrupt();
            throw cancelled;
        } catch (ExecutionException failure) {
            return failed(work, failure.getCause().toString());
        }
    }

    private static WorkDone failed(DoWork work, String detail) {
        return new WorkDone(work.id(), work.workType(), new Json.Str(detail), true, null);
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return failed((DoWork) command, failure.toString());
    }
}
