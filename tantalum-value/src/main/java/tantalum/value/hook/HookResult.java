package tantalum.value.hook;

/** 内核钩子的四种结果：放行、拦截、要求工作、问用户。 */
public sealed interface HookResult permits Allow, Stop, NeedWork, Ask {}
