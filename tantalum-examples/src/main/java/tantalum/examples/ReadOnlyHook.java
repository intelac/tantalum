package tantalum.examples;

import java.util.List;
import java.util.Set;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.plugin.Configurable;
import tantalum.shell.plugin.Param;
import tantalum.shell.plugin.PluginInfo;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.state.KernelState;
import tantalum.value.text.LocalText;

/**
 * 只读策略：只放行列出的工具，其余拦截，模型会收到错误结果。
 *
 * <p>名单是用户自己的参数 {@code tools}：个人可以自己加严或放松；要全公司一致，管理员锁定这个插件，锁定后用管理员定的默认。
 * 构造时给的名单（服务器配置）是插件自带的默认；没记参数的旧会话也用它。
 */
public final class ReadOnlyHook implements KernelHook, PluginInfo, Configurable {

    /** 排过序：它是参数的默认值，会记进会话，顺序要固定。 */
    private final List<String> readOnlyTools;

    public ReadOnlyHook(Set<String> readOnlyTools) {
        this.readOnlyTools = readOnlyTools.stream().sorted().toList();
    }

    @Override
    public String name() {
        return "readOnly";
    }

    @Override
    public List<Param> params() {
        return List.of(Param.list("tools", readOnlyTools)
                .label(LocalText.en("Tools that may run").zh("放行的工具").ja("実行を許可するツール"))
                .hint(LocalText.en("One tool name per line.").zh("每行一个工具名。").ja("1 行に 1 つのツール名。")));
    }

    @Override
    public LocalText description() {
        // 例子插件示范带三种语言的写法：控制台按看的人的语言选，没有就用英文
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
        return context.params().list("tools", readOnlyTools).contains(tool) ? Allow.PLAIN : new Stop("read-only policy blocks " + tool);
    }
}
