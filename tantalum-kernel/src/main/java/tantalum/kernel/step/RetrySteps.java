package tantalum.kernel.step;

import java.time.Duration;
import java.util.Set;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.Decision;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.PromptPatch;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.model.FailureReason;
import tantalum.value.state.Compacting;
import tantalum.value.state.EndReason;
import tantalum.value.state.RunState;

/** 模型失败和重试的处理函数。 */
public final class RetrySteps {

    public static final String AWAITING_RETRY = "AwaitingRetry";
    /** 一个请求最多重试几次；轨迹显示“第几次、最多几次”时也用它。 */
    public static final int MAX_RETRIES = 3;
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private RetrySteps() {}

    public static Decision modelFailed(StepContext context) {
        ModelFailed event = (ModelFailed) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();

        HookOutcome hook = out.hooks.run(HookPoint.ON_FAILURE, out.state(),
                out.hookContext(run.ref()).withFailure(event));
        out.report(hook.facts());
        if (hook.result() instanceof Stop stop) {
            RunEnd.finish(out, run, EndReason.FAILED, stop.reason());
        } else if (hook.result() instanceof NeedWork need) {
            WorkSteps.start(out, run, need, hook.decidedBy(), HookPoint.ON_FAILURE);
        } else if (event.reason() == FailureReason.CONTEXT_OVERFLOW && !run.overflowRecovered()) {
            // 超长恢复（规则 2、3.2）：只留最新单元压缩后重试一次；无可压缩或摘要失败都以原始错误结束。
            RunState recovering = run.withOverflowRecovered(true);
            out.put(recovering);
            if (CompactSteps.tryStart(out, recovering, PromptPatch.NONE, new Compacting.Overflow(event.detail())) == CompactSteps.Start.PROCEED) {
                RunEnd.finish(out, recovering, EndReason.FAILED, event.detail());
            }
        } else if (!event.retryable() || !scheduleRetry(out, run, event.retryAfter(), AWAITING_RETRY)) {
            RunEnd.finish(out, run, EndReason.FAILED, event.detail());
        }
        return out.decision();
    }

    /** 退避后在 {@code resumeAt} 重来，占用一次重试；次数用完时什么都不做，返回 false。 */
    static boolean scheduleRetry(Outcome out, RunState run, Duration retryAfter, String resumeAt) {
        if (run.retries() >= MAX_RETRIES) {
            return false;
        }
        CommandId id = out.nextCommandId();
        out.emit(new StartTimer(id, run.ref(), backoff(run.retries(), retryAfter)));
        out.await(run.withRetries(run.retries() + 1), resumeAt, Set.of(id));
        return true;
    }

    /** 失败钩子申请的工作完成后再调模型，和定时重试一样占用重试次数，失败持续时不会无限循环。 */
    static void retryAfterWork(Outcome out, RunState run, WorkDone work) {
        if (run.retries() >= MAX_RETRIES) {
            RunEnd.finish(out, run, EndReason.FAILED, "model call still failing after " + MAX_RETRIES + " retries");
            return;
        }
        RunState retrying = run.withRetries(run.retries() + 1);
        out.put(retrying);
        if (!WorkSteps.continueWithText(out, retrying, work)) {
            ModelSteps.callModel(out, retrying, work);
        }
    }

    /** 指数退避，供应商给的 {@code retryAfter} 更长时用它，但不超过上限。 */
    static Duration backoff(int retries, Duration retryAfter) {
        Duration exponential = Duration.ofSeconds(1L << retries);
        Duration chosen = retryAfter != null && retryAfter.compareTo(exponential) > 0 ? retryAfter : exponential;
        return chosen.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : chosen;
    }

    public static Decision timerFired(StepContext context) {
        Outcome out = new Outcome(context);
        ModelSteps.callModel(out, context.run(), null);
        return out.decision();
    }
}
