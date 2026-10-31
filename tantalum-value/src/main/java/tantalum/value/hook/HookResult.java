package tantalum.value.hook;

/** 内核钩子的三种结果。 */
public sealed interface HookResult permits Allow, Stop, NeedWork {}
