package tantalum.kernel.table;

import tantalum.value.decision.Decision;

/** 转移表一行的处理函数，必须是纯函数。 */
@FunctionalInterface
public interface StepFunction {

    Decision apply(StepContext context);
}
