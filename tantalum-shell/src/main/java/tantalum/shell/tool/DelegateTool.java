package tantalum.shell.tool;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.delegate.Delegations;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolArguments;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/**
 * {@code delegate}（memory-final-plan 第 12 节的第一版）：把一件事交给一个子会话去做。子会话是同一个人的普通会话，第一句是交代；
 * 等结果时这次工具调用一直等到子运行结束，结果是子运行最后的回答；不等时马上返回子会话编号，做完后把结果作为一条汇报送回这个会话。
 * 子会话继承“不进记忆”；最多两层；父运行被取消时子运行跟着取消。子运行只能用 memory_note 写记忆（记忆插件按委派记录判断）。
 */
public final class DelegateTool implements Tool {

    public static final String NAME = "delegate";
    static final ToolDispatch DISPATCH = ToolDispatch.parallel(NAME, "delegate", 8);
    static final int MAX_DEPTH = 2;
    static final Duration LIMIT = Duration.ofMinutes(10);
    static final Duration POLL = Duration.ofMillis(200);
    private static final Logger LOG = LoggerFactory.getLogger(DelegateTool.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 平台替这个工具做的事（引擎起来以后接上）。 */
    public interface Runner {
        /** 在子会话里提问，返回 HTTP 状态。 */
        int submit(Principal user, SessionId session, String text, String dir, boolean offRecord);

        /** 子会话这一轮结束了没有：没结束为空；结束了给最后的回答（没有回答为空串）。 */
        Optional<String> finished(Principal user, SessionId session);

        void cancel(Principal user, SessionId session);

        /** 把一段话作为提问送进父会话（不等结果的委派做完时）。 */
        void report(Principal user, SessionId session, String text);

        Delegations delegations();
    }

    private volatile Runner runner;

    public void bind(Runner bound) {
        this.runner = bound;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "Hand a self-contained piece of work to a sub-conversation of the same user and get its result. task: what to do; "
                + "brief: what it needs to know (it does not see this conversation); wait: true (default) waits for the result, false "
                + "returns at once and the result is reported back here later. The sub-conversation can read memory but only add notes; "
                + "at most two levels deep.";
    }
    @Override public Json schema() { return ToolArguments.schema(List.of("task"), List.of("brief", "wait")); }
    @Override public String server() { return DISPATCH.server(); }
    @Override public ToolDispatch dispatch() { return DISPATCH; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }
    /** 父运行在这里等子运行，子运行自己也可能委派：名额不能只有一个，否则子运行等父运行占着的名额，谁也动不了。 */
    @Override public int serverConcurrency() { return DISPATCH.serverConcurrency(); }
    @Override public Duration timeout(Json arguments, Duration standard) { return LIMIT.plusSeconds(30); }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) throws InterruptedException {
        Runner bound = runner;
        String task = ToolArguments.string(arguments, "task");
        if (bound == null) {
            return ToolOutput.error("Delegation is not available yet.");
        }
        if (task == null || task.isBlank()) {
            return ToolOutput.error("Missing argument: task");
        }
        String brief = ToolArguments.string(arguments, "brief");
        boolean wait = ToolArguments.bool(arguments, "wait", true);
        Principal user = context.principal();
        int depth;
        try {
            depth = bound.delegations().of(user, context.session()).map(Delegations.Delegation::depth).orElse(0) + 1;
        } catch (java.sql.SQLException failure) {
            return ToolOutput.error("Delegation is not available now.");
        }
        if (depth > MAX_DEPTH) {
            return ToolOutput.error("This conversation was itself delegated " + (depth - 1) + " levels deep; do the work here instead.");
        }
        SessionId child = new SessionId("d-" + HexFormat.of().formatHex(RANDOM.generateSeed(5)));
        String prompt = "You are doing a task delegated by another conversation of the same user (" + context.session().value()
                + "). It cannot see this conversation, so reply with a complete result; your final answer goes back to it.\n\nTask:\n"
                + task.strip() + (brief == null || brief.isBlank() ? "" : "\n\nWhat you need to know:\n" + brief.strip());
        try {
            bound.delegations().record(new Delegations.Delegation(user, child, context.session(),
                    context.command() == null ? null : context.command().value(), depth, Instant.now()));
        } catch (java.sql.SQLException failure) {
            return ToolOutput.error("Delegation is not available now.");
        }
        int status = bound.submit(user, child, prompt, context.dir(), context.offRecord());
        if (status != 200) {
            return ToolOutput.error("The sub-conversation could not start (HTTP " + status + ").");
        }
        if (!wait) {
            Thread.ofVirtual().name("delegate-" + child.value()).start(() -> reportLater(bound, user, context.session(), child));
            return ToolOutput.ok("Started sub-conversation " + child.value() + "; its result will be reported back here when it is done.");
        }
        Optional<String> answer = await(bound, user, child);
        if (answer.isEmpty()) {
            bound.cancel(user, child);
            return ToolOutput.error("The sub-conversation " + child.value() + " did not finish within " + LIMIT.toMinutes() + " minutes; it was stopped.");
        }
        return answer.get().isBlank()
                ? ToolOutput.error("The sub-conversation " + child.value() + " ended without an answer.")
                : ToolOutput.ok("Result from sub-conversation " + child.value() + ":\n" + answer.get());
    }

    /** 等子会话这一轮结束；等不到（超过上限）为空。父运行被取消（线程被打断）时取消子运行再往外抛。 */
    private static Optional<String> await(Runner bound, Principal user, SessionId child) throws InterruptedException {
        long deadline = System.nanoTime() + LIMIT.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                Thread.sleep(POLL);
                Optional<String> done = bound.finished(user, child);
                if (done.isPresent()) {
                    return done;
                }
            }
            return Optional.empty();
        } catch (InterruptedException cancelled) {
            bound.cancel(user, child);
            throw cancelled;
        }
    }

    private static void reportLater(Runner bound, Principal user, SessionId parent, SessionId child) {
        try {
            Optional<String> answer = await(bound, user, child);
            String text = answer.isEmpty() ? Delegations.REPORT_PROBLEM + child.value() + " did not finish in time and was stopped."
                    : answer.get().isBlank() ? Delegations.REPORT_PROBLEM + child.value() + " ended without an answer."
                    : Delegations.REPORT + child.value() + ":\n" + answer.get();
            if (answer.isEmpty()) {
                bound.cancel(user, child);
            }
            bound.report(user, parent, text);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            LOG.warn("cannot report delegated session {} back: {}", child.value(), failure.toString());
        }
    }
}
