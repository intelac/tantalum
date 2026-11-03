package tantalum.shell.handler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.tool.Workspaces;
import tantalum.value.command.Command;
import tantalum.value.command.RunTool;
import tantalum.value.event.Event;
import tantalum.value.event.ToolFinished;
import tantalum.value.json.Json;

/** 执行工具调用。失败和超时都变成错误结果交给模型；结果不明的调用不自动重试。 */
public final class ToolHandler implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(ToolHandler.class);

    private final ToolCatalog catalog;
    private final Workspaces workspaces;
    private final List<AfterToolHook> afterHooks;
    private final Duration timeout;
    /** 每个服务端一份名额，同一服务端的工具共用。 */
    private final Map<String, CallLimiter> limiters = new ConcurrentHashMap<>();
    private final Clock clock;

    /** 执行的结果和真正开始的时刻；没拿到名额时开始时刻为 null。 */
    private record Ran(ToolOutput output, Instant startedAt) {}

    public ToolHandler(ToolCatalog catalog, Workspaces workspaces, List<AfterToolHook> afterHooks, Duration timeout, Clock clock) {
        this.catalog = catalog;
        this.workspaces = workspaces;
        this.afterHooks = List.copyOf(afterHooks);
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public Event handle(Command command) throws InterruptedException {
        RunTool run = (RunTool) command;
        Optional<Tool> tool = catalog.find(run.call().name());
        if (tool.isEmpty()) {
            return finished(run, ToolOutput.error("Unknown tool: " + run.call().name()), null);
        }
        ToolContext context;
        try {
            context = new ToolContext(run.run().principal(), workspaces.of(run.run().principal()));
        } catch (RuntimeException failure) {
            return finished(run, ToolOutput.error("Workspace unavailable: " + failure.getMessage()), null);
        }
        Ran ran = execute(tool.get(), context, run.call().arguments());
        return finished(run, applyHooks(tool.get(), context, run.call().arguments(), ran.output()), ran.startedAt());
    }

    CallLimiter limiterFor(Tool tool) {
        return limiters.computeIfAbsent(tool.server(), server -> new CallLimiter(tool.serverConcurrency()));
    }

    /** 等待名额计入超时。名额由执行工具的线程持有，工具真正结束才归还，超时也不会超过服务端上限。 */
    private Ran execute(Tool tool, ToolContext context, Json arguments) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        CallLimiter.Permit permit;
        try {
            permit = limiterFor(tool).acquire(timeout);
        } catch (TimeoutException waiting) {
            return new Ran(ToolOutput.error("Tool timed out after " + timeout + " waiting for server capacity."), null);
        }

        // 等名额是排队：同一服务端的调用会排在后面，开始时刻从拿到名额算。
        Instant startedAt = clock.instant();
        try {
            return new Ran(Deadline.call(() -> {
                try (permit) {
                    return tool.run(context, arguments);
                }
            }, Duration.ofNanos(deadline - System.nanoTime())), startedAt);
        } catch (TimeoutException late) {
            return new Ran(ToolOutput.error("Tool timed out after " + timeout + ". Its outcome is unknown."), startedAt);
        } catch (ExecutionException failure) {
            return new Ran(ToolOutput.error("Tool failed: " + failure.getCause()), startedAt);
        }
    }

    private ToolOutput applyHooks(Tool tool, ToolContext context, Json arguments, ToolOutput first) {
        ToolOutput output = first;
        for (AfterToolHook hook : afterHooks) {
            try {
                output = hook.afterToolExecution(context, tool.name(), arguments, output);
            } catch (Exception failure) {
                LOG.error("afterToolExecution hook failed for tool {}", tool.name(), failure);
            }
        }
        return output;
    }

    private static ToolFinished finished(RunTool run, ToolOutput output, Instant startedAt) {
        return new ToolFinished(run.id(), run.call().id(), output.text(), output.error(), startedAt, output.images());
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return finished((RunTool) command, ToolOutput.error("Tool failed: " + failure), null);
    }
}
