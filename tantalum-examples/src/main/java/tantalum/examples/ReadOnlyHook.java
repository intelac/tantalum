package tantalum.examples;

import java.util.Set;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.state.KernelState;
import tantalum.value.text.LocalText;

/** 只读策略：只放行列出的工具，其余拦截，模型会收到错误结果。 */
public final class ReadOnlyHook implements KernelHook {

    private final Set<String> readOnlyTools;

    public ReadOnlyHook(Set<String> readOnlyTools) {
        this.readOnlyTools = Set.copyOf(readOnlyTools);
    }

    @Override
    public String name() {
        return "readOnly";
    }

    @Override
    public LocalText description() {
        // 例子插件示范带三种语言的写法：控制台按看的人的语言选，没有就用英文（console-i18n I10）
        return LocalText.en("Only tools on the list may run; every other call is blocked.")
                .zh("只放行名单里的工具，其余调用一律拦下。")
                .ja("リストにあるツールだけを通し、それ以外の呼び出しはすべてブロックします。");
    }

    /** 策略插件：默认关，用户在插件页打开（管理员要对所有人强制开启时写进 {@code tantalum.hooks.required}）；开着时出错按拦截处理。 */
    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public boolean failClosed() {
        return true;
    }

    @Override
    public HookResult beforeToolCall(KernelState state, HookContext context) {
        String tool = context.call().name();
        return readOnlyTools.contains(tool) ? Allow.PLAIN : new Stop("read-only policy blocks " + tool);
    }
}
