package tantalum.shell.access;

import java.util.List;
import tantalum.shell.plugin.Tool;
import tantalum.value.id.Principal;

/**
 * 本机工作区的工具现在能不能用（local-workspace L9、L17），由 {@code tantalum.shell.local.LocalWorkspace} 实现。
 * 接口定义在这里，{@link PluginGate} 就不依赖 {@code local} 包（那边要用这里的开关，反过来会成环）。
 */
public interface LocalAccess {

    /** 总开关，也是转发工具的服务端名。 */
    String MASTER = "local-workspace";
    /** 插件页上的开关：总开关和读文件、Git 查询、改文件、命令行四个子开关，默认都开。 */
    List<String> SWITCHES = List.of(MASTER, "fs-read", "git", "file-edit", "shell");

    /** 什么都不管：没有本机工作区时（嵌入使用和测试）。 */
    LocalAccess NONE = new LocalAccess() {
        @Override public boolean owns(Tool tool) { return false; }
        @Override public boolean enabled(Principal user, Tool tool) { return false; }
    };

    /** 这个工具是不是本机工作区的。 */
    boolean owns(Tool tool);

    /** 总开关和子开关都开着、电脑连着、连接程序也允许这一类。只对 {@link #owns} 为真的工具有意义。 */
    boolean enabled(Principal user, Tool tool);
}
