package tantalum.kernel.step;

import java.time.Duration;
import java.util.Set;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.hook.HookPoint;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.Decision;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.WorkDone;
import tantalum.value.model.Message;
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

    static final String AWAITING_RETRY = "AwaitingRetry";
    static final int MAX_RETRIES = 3;
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private RetrySteps() {}

    public static Decision modelFailed(StepContext context) {
        ModelFailed event = (ModelFailed) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();

        HookOutcome hook = out.hooks.run(HookPoint.ON_FAILURE, out.state,
                out.hookContext(run.ref()).withFailure(event));
        out.report(hook.facts());
        if (hook.result() instanceof Stop stop) {
            RunEnd.finish(out, run, EndReason.FAILED, stop.reason());
        } else if (hook.result() instanceof NeedWork need) {
            WorkSteps.start(out, run, need, HookPoint.ON_FAILURE);
        } else if (event.reason() == FailureReason.CONTEXT_OVERFLOW && !run.overflowRecovered()) {
            // 超长恢复（规则 2、3.2）：只留最新单元压缩后重试一次；无可压缩或摘要失败都以原始错误结束。
            RunState recovering = run.withOverflowRecovered(true);
            out.put(recovering);
            if (CompactSteps.tryStart(out, recovering, PromptPatch.NONE, Compacting.Trigger.OVERFLOW, null, event.detail()) != null) {
                RunEnd.finish(out, recovering, EndReason.FAILED, event.detail());
            }
        } else if (event.retryable() && run.retries() < MAX_RETRIES) {
            CommandId id = out.nextCommandId();
            out.emit(new StartTimer(id, run.ref(), backoff(run.retries(), event.retryAfter())));
            RunState waiting = run.withRetries(run.retries() + 1);
            out.put(waiting);
            out.await(waiting, AWAITING_RETRY, Set.of(id));
        } else {
            RunEnd.finish(out, run, EndReason.FAILED, event.detail());
        }
        return out.decision();
    }

    /** 失败钩子申请的工作完成后再调模型，和定时重试一样占用重试次数，失败持续时不会无限循环。 */
    static void retryAfterWork(Outcome out, RunState run, WorkDone work) {
        if (run.retries() >= MAX_RETRIES) {
            RunEnd.finish(out, run, EndReason.FAILED, "model call still failing after " + MAX_RETRIES + " retries");
            return;
        }
        RunState retrying = run.withRetries(run.retries() + 1);
        out.put(retrying);
        if (work.continueText() != null) {
            out.append(retrying.ref(), Message.user(work.continueText()));
            ModelSteps.callModel(out, retrying, null);
        } else {
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
