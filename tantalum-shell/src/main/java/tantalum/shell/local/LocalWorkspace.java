package tantalum.shell.local;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.local.tool.LocalTool.Kind;
import tantalum.local.wire.Hello;
import tantalum.shell.access.LocalAccess;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.plugin.Tool;
import tantalum.value.id.Principal;

/**
 * 本机工作区（local-workspace 设计“LocalWorkspace”）：哪些工具现在能用、给模型的“本机工作区”一段、电脑上的 {@code AGENTS.md}。
 * 一个工具能用要四样都满足：总开关开、它那一类的子开关开、电脑连着、连接程序允许这一类（L4、L17）。
 */
public final class LocalWorkspace implements LocalAccess {

    /** 每一类工具对应的子开关。 */
    private static final Map<Kind, String> SWITCH = Map.of(
            Kind.READ, "fs-read", Kind.GIT, "git", Kind.WRITE, "file-edit", Kind.SHELL, "shell");

    private final PluginSwitches switches;
    private final LocalLinks links;

    public LocalWorkspace(PluginSwitches switches, LocalLinks links) {
        this.switches = switches;
        this.links = links;
    }

    public static String switchName(Kind kind) {
        return SWITCH.get(kind);
    }

    @Override
    public boolean owns(Tool tool) {
        return tool instanceof LocalTool;
    }

    @Override
    public boolean enabled(Principal user, Tool tool) {
        return tool instanceof LocalTool local && enabled(user, local.kind());
    }

    public boolean enabled(Principal user, Kind kind) {
        return switches.enabled(user, PluginSwitches.Kind.LOCAL, MASTER)
                && switches.enabled(user, PluginSwitches.Kind.LOCAL, SWITCH.get(kind))
                && links.status(user).map(status -> allows(status.hello(), kind)).orElse(false);
    }

    /** 连接程序允许不允许这一类：改文件要 {@code --allow-write}，命令行要沙箱可用，Git 要主目录是仓库。 */
    public static boolean allows(Hello hello, Kind kind) {
        return switch (kind) {
            case READ -> true;
            case WRITE -> hello.write();
            case SHELL -> hello.shell().available();
            case GIT -> !hello.roots().isEmpty() && hello.roots().getFirst().git();
        };
    }

    /** 插件页上的开关状态：总开关和四个子开关，按 {@link LocalAccess#SWITCHES} 的顺序。 */
    public Map<String, Boolean> switches(Principal user) {
        Map<String, Boolean> states = new LinkedHashMap<>();
        for (String name : SWITCHES) {
            states.put(name, switches.enabled(user, PluginSwitches.Kind.LOCAL, name));
        }
        return states;
    }

    /**
     * 给模型的“本机工作区”一段（英文，会变的 system 段）：电脑、主目录、其他允许的目录、能不能改文件和跑命令。
     * 目录和权限不写进工具说明，工具定义才不随电脑变（L9）。没连上、总开关关着时没有这一段。
     */
    public Optional<String> section(Principal user) {
        if (!switches.enabled(user, PluginSwitches.Kind.LOCAL, MASTER)) {
            return Optional.empty();
        }
        return links.status(user).map(status -> describe(status.hello(), enabled(user, Kind.WRITE), enabled(user, Kind.SHELL)));
    }

    static String describe(Hello hello, boolean write, boolean shell) {
        Hello.Device device = hello.device();
        StringBuilder text = new StringBuilder("Local workspace: ").append(osName(device.os())).append(' ')
                .append(device.osVersion()).append(' ').append(device.arch()).append(" \"").append(device.name()).append("\".");
        List<Hello.Root> roots = hello.roots();
        if (!roots.isEmpty()) {
            Hello.Root main = roots.getFirst();
            text.append(" Main directory: ").append(main.path()).append(main.git() ? " (git)." : ".");
        }
        if (roots.size() > 1) {
            text.append(" Other allowed directories: ")
                    .append(String.join(", ", roots.subList(1, roots.size()).stream().map(Hello.Root::path).toList())).append('.');
        }
        text.append(" File changes: ").append(write ? "allowed." : "not allowed.");
        text.append(" Shell: ").append(shell ? "bash, network " + (hello.network() ? "on." : "off.") : "not available.");
        return text.toString();
    }

    private static String osName(String os) {
        return "macos".equalsIgnoreCase(os) ? "macOS" : os;
    }

    /** 电脑主目录里的 {@code AGENTS.md}；没连上、总开关关着、2 秒没回都是空。 */
    public Optional<String> agents(Principal user) throws InterruptedException {
        if (!switches.enabled(user, PluginSwitches.Kind.LOCAL, MASTER)) {
            return Optional.empty();
        }
        return links.agents(user);
    }

    public LocalLinks links() {
        return links;
    }
}
