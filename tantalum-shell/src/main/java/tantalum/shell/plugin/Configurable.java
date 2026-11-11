package tantalum.shell.plugin;

import java.util.List;

/**
 * 有参数的插件成员。钩子、工具后处理、插件工作和工具都可以实现；同一个插件的成员声明合在一起，在插件页上是一张表单。
 * 两个成员声明了同一个键，内容必须完全一样，否则启动失败。
 *
 * <p>怎么读：工具、工具后处理、插件工作从 {@link ToolContext#params()} 读当前的值，密码从 {@link ToolContext#secrets()} 取；
 * 内核钩子从 {@code HookContext.params()} 读这次提问开始时记下的值（没有密码）。
 */
public interface Configurable {

    List<Param> params();
}
