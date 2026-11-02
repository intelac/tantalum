package tantalum.shell.journal;

import tantalum.value.id.Principal;

/**
 * 按用户分配序号。同一用户内递增，可以跳号，不同用户可以相同，所以任何代码都不能比较不同用户的序号。
 * 序号不超过 {@link #MAX}，因为控制台用 JavaScript 数字解析它。
 */
public interface Sequence {

    long MAX = 1L << 53;

    long next(Principal principal);
}
