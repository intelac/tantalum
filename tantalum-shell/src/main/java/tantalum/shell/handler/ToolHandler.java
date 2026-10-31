package tantalum.shell.handler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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

    public ToolHandler(ToolCatalog catalog, Workspaces workspaces, List<AfterToolHook> afterHooks, Duration timeout) {
        this.catalog = catalog;
        this.workspaces = workspaces;
        this.afterHooks = List.copyOf(afterHooks);
        this.timeout = timeout;
    }

    @Override
    public Event handle(Command command) throws InterruptedException {
        RunTool run = (RunTool) command;
        Optional<Tool> tool = catalog.find(run.call().name());
        if (tool.isEmpty()) {
            return finished(run, ToolOutput.error("Unknown tool: " + run.call().name()));
        }
        ToolContext context;
        try {
            context = new ToolContext(run.run().principal(), workspaces.of(run.run().principal()));
        } catch (RuntimeException failure) {
            return finished(run, ToolOutput.error("Workspace unavailable: " + failure.getMessage()));
        }
        ToolOutput output = execute(tool.get(), context, run.call().arguments());
        return finished(run, applyHooks(tool.get(), context, run.call().arguments(), output));
    }

    /** 等待名额计入超时。名额由执行工具的线程持有，工具真正结束才归还，超时也不会超过服务端上限。 */
    private ToolOutput execute(Tool tool, ToolContext context, Json arguments) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        CallLimiter.Permit permit;
        try {
            permit = catalog.limiterFor(tool).acquire(timeout);
        } catch (TimeoutException waiting) {
            return ToolOutput.error("Tool timed out after " + timeout + " waiting for server capacity.");
        }

        CompletableFuture<ToolOutput> result = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try (permit) {
                result.complete(tool.run(context, arguments));
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            worker.interrupt();
            return ToolOutput.error("Tool timed out after " + timeout + ". Its outcome is unknown.");
        } catch (InterruptedException cancelled) {
            worker.interrupt();
            throw cancelled;
        } catch (ExecutionException failure) {
            return ToolOutput.error("Tool failed: " + failure.getCause());
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

    private static ToolFinished finished(RunTool run, ToolOutput output) {
        return new ToolFinished(run.id(), run.call().id(), output.text(), output.error());
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return finished((RunTool) command, ToolOutput.error("Tool failed: " + failure));
    }
}
