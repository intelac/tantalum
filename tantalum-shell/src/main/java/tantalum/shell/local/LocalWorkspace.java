package tantalum.shell.local;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.local.tool.LocalTool.Kind;
import tantalum.local.wire.Hello;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.access.ToolGroup;
import tantalum.shell.plugin.Tool;
import tantalum.shell.project.ProjectPaths;
import tantalum.value.id.Principal;

/**
 * 本机工作区：哪些工具现在能用、给模型的“本机工作区”一段、电脑上的 {@code AGENTS.md}。
 * 一个工具能用要四样都满足：总开关开、它那一类的子开关开、电脑连着、连接程序允许这一类。
 */
public final class LocalWorkspace implements ToolGroup {

    /** 总开关，也是转发工具的服务端名。 */
    public static final String MASTER = "local-workspace";
    /** “技能”和“知识库”两个子开关。知识库没有自己的工具，只管 system 里那一段。 */
    public static final String SKILLS = "skills";
    public static final String WIKI = "wiki";
    /** 插件页上的开关：总开关和读文件、Git 查询、改文件、命令行、技能、知识库六个子开关，默认都开。 */
    public static final List<String> SWITCHES = List.of(MASTER, "fs-read", "git", "file-edit", "shell", SKILLS, WIKI);
    /** {@code ContextAssembled.toolSources} 里的来源。 */
    public static final String SOURCE = "local";
    /** 从这个协议版本起，连接程序按会话的工作目录解析相对路径。 */
    static final int SESSION_DIRS = 3;
    /** 从这个协议版本起，连接程序会列技能和 wiki 页、有 {@code skill} 工具。 */
    public static final int SKILL_LISTS = 4;

    /** 每一类工具对应的子开关。 */
    /** 开关的种类；这个文件里的 {@code Kind} 是本机工具的种类（读、写、git、shell）。 */
    private static final tantalum.shell.plugin.Kind LOCAL = tantalum.shell.plugin.Kind.LOCAL;
    private static final Map<Kind, String> SWITCH = Map.of(
            Kind.READ, "fs-read", Kind.GIT, "git", Kind.WRITE, "file-edit", Kind.SHELL, "shell", Kind.SKILL, SKILLS);

    private final PluginSwitches switches;
    private final LocalLinks links;
    private final LocalDirs dirs;

    /** 没有地方填目录时（测试）：技能和知识库都当没填。 */
    public LocalWorkspace(PluginSwitches switches, LocalLinks links) {
        this(switches, links, LocalDirs.NONE);
    }

    public LocalWorkspace(PluginSwitches switches, LocalLinks links, LocalDirs dirs) {
        this.switches = switches;
        this.links = links;
        this.dirs = dirs;
    }

    public static String switchName(Kind kind) {
        return SWITCH.get(kind);
    }

    @Override
    public tantalum.shell.plugin.Kind kind() {
        return LOCAL;
    }

    @Override
    public List<String> switches() {
        return SWITCHES;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean owns(Tool tool) {
        return tool instanceof LocalTool;
    }

    @Override
    public boolean enabled(Principal user, Tool tool) {
        return tool instanceof LocalTool local && enabled(user, local.kind());
    }

    /** {@code skill} 工具还要用户填了技能目录：没填的人工具列表和以前一样。 */
    public boolean enabled(Principal user, Kind kind) {
        return switches.enabled(user, LOCAL, MASTER)
                && switches.enabled(user, LOCAL, SWITCH.get(kind))
                && links.status(user).map(status -> allows(status.hello(), kind)).orElse(false)
                && (kind != Kind.SKILL || dirs.skillsDir(user).isPresent());
    }

    /** 这次运行要列的技能目录：{@code skill} 工具可用时才有。 */
    public Optional<String> skillsDir(Principal user) {
        return enabled(user, Kind.SKILL) ? dirs.skillsDir(user) : Optional.empty();
    }

    /** 这次运行要列的 wiki 目录：总开关和“知识库”开着、电脑连着、连接程序够新、填了目录。 */
    public Optional<String> wikiDir(Principal user) {
        boolean on = switches.enabled(user, LOCAL, MASTER) && switches.enabled(user, LOCAL, WIKI)
                && links.status(user).map(status -> status.hello().protocol() >= SKILL_LISTS).orElse(false);
        return on ? dirs.wikiDir(user) : Optional.empty();
    }

    public LocalDirs dirs() {
        return dirs;
    }

    /**
     * 连接程序允许不允许这一类：改文件要 {@code --allow-write}，命令行要沙箱可用，Git 要电脑上装了 git。Git 不看目录是不是仓库：
     * 每次调用在电脑上按路径找仓库，不在仓库里就报“不是 Git 仓库”，子文件夹和任何一个允许目录里的仓库都能查。
     */
    public static boolean allows(Hello hello, Kind kind) {
        return switch (kind) {
            case READ -> true;
            case WRITE -> hello.write();
            case SHELL -> hello.shell().available();
            case GIT -> hello.git();
            // 旧的连接程序不认识 skill 工具，也不会列技能
            case SKILL -> hello.protocol() >= SKILL_LISTS;
        };
    }

    /** 插件页上的开关状态：总开关和各个子开关，按 {@link #SWITCHES} 的顺序。 */
    public Map<String, Boolean> switches(Principal user) {
        Map<String, Boolean> states = new LinkedHashMap<>();
        for (String name : SWITCHES) {
            states.put(name, switches.enabled(user, LOCAL, name));
        }
        return states;
    }

    /**
     * 给模型的“本机工作区”一段（英文，会变的 system 段）：电脑、主目录、其他允许的目录、能不能改文件和跑命令。
     * 目录和权限不写进工具说明，工具定义才不随电脑变。没连上、总开关关着时没有这一段。
     */
    public Optional<String> section(Principal user) {
        return section(user, null);
    }

    /** 同上，{@code dir} 是会话的工作目录（没有为 null）。 */
    public Optional<String> section(Principal user, String dir) {
        if (!switches.enabled(user, LOCAL, MASTER)) {
            return Optional.empty();
        }
        return links.status(user).map(status -> describe(status.hello(), enabled(user, Kind.WRITE), enabled(user, Kind.SHELL), dir));
    }

    static String describe(Hello hello, boolean write, boolean shell) {
        return describe(hello, write, shell, null);
    }

    /**
     * 会话有工作目录、连接程序是协议 3 起时写 {@code Working directory}，再列全部允许的目录；目录不在允许的范围里也如实写，
     * 不退回主目录。没有目录或协议 2（连接程序还不认会话目录）时照旧写主目录。
     */
    static String describe(Hello hello, boolean write, boolean shell, String dir) {
        Hello.Device device = hello.device();
        StringBuilder text = new StringBuilder("Local workspace: ").append(osName(device.os())).append(' ')
                .append(device.osVersion()).append(' ').append(device.arch()).append(" \"").append(device.name()).append("\".");
        List<Hello.Root> roots = hello.roots();
        boolean sessionDir = dir != null && hello.protocol() >= SESSION_DIRS;
        if (sessionDir) {
            text.append(" Working directory: ").append(dir);
            if (roots.stream().noneMatch(root -> ProjectPaths.within(dir, root.path()))) {
                text.append(" (not allowed on this computer; file tools will fail)");
            }
            text.append('.');
            if (!roots.isEmpty()) {
                text.append(" Allowed directories: ").append(String.join(", ", roots.stream().map(Hello.Root::path).toList())).append('.');
            }
        } else if (!roots.isEmpty()) {
            Hello.Root main = roots.getFirst();
            text.append(" Main directory: ").append(main.path()).append('.');
        }
        if (!sessionDir && roots.size() > 1) {
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
        return agents(user, null);
    }

    /** 同上，会话有工作目录时从那里读。 */
    public Optional<String> agents(Principal user, String dir) throws InterruptedException {
        if (!switches.enabled(user, LOCAL, MASTER)) {
            return Optional.empty();
        }
        return links.agents(user, dir);
    }

    /**
     * 一次往返问电脑要 {@code AGENTS.md}、技能清单、wiki 页；技能和 wiki 只在 {@link #skillsDir}、{@link #wikiDir} 有值时问。
     * 总开关关着算没连上。
     */
    public LocalLinks.Reply<tantalum.local.wire.ContextResult> context(Principal user, String dir) throws InterruptedException {
        if (!switches.enabled(user, LOCAL, MASTER)) {
            return LocalLinks.Reply.notConnected();
        }
        return links.context(user, dir, skillsDir(user).orElse(null), wikiDir(user).orElse(null));
    }

    public LocalLinks links() {
        return links;
    }
}
