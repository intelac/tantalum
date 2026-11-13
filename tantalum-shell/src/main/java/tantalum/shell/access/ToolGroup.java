package tantalum.shell.access;

import java.util.List;
import java.util.Map;
import tantalum.shell.plugin.Kind;
import tantalum.shell.plugin.Tool;
import tantalum.value.id.Principal;

/**
 * 一组不看单个工具开关、只看自己的总开关和子开关的工具：本机工作区（{@code local} 包的
 * {@code LocalWorkspace}）和联网（{@link WebAccess}）。接口定义在这里，{@link PluginGate} 就不依赖实现它的包（那边要用这里的开关，
 * 反过来会成环）。
 */
public interface ToolGroup {

    /** 这一组的开关存在 {@link PluginSwitches} 的哪一种里。 */
    Kind kind();

    /** 插件页上的开关名，第一个是总开关。 */
    List<String> switches();

    /**
     * 哪些子开关下面的工具可以逐个勾（qagent-align 需求 2）：子开关名 → 工具名，按展示顺序。勾存在同一种开关里，
     * 名字就是工具名，所以不能和 {@link #switches} 里的名字重。默认没有。
     */
    default Map<String, List<String>> choices() {
        return Map.of();
    }

    /** 这个工具是不是这一组的。 */
    boolean owns(Tool tool);

    /** 这个用户现在能不能用这个工具。只对 {@link #owns} 为真的工具有意义。 */
    boolean enabled(Principal user, Tool tool);

    /** 会话文件 {@code ContextAssembled.toolSources} 里记的来源，如 {@code local}、{@code web}。 */
    String source();

    /** 除了 {@link #switches()} 和逐个勾的工具，还认的开关名（本机工作区每个本机 MCP 服务器一个，名字随电脑变）。 */
    default boolean acceptsSwitch(String name) {
        return false;
    }
}
