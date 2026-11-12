package tantalum.shell.local;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.local.tool.BrowserTools;
import tantalum.local.tool.LocalTool.Kind;
import tantalum.local.wire.Hello;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.access.ToolGroup;
import tantalum.shell.plugin.Tool;
import tantalum.shell.project.ProjectPaths;
import tantalum.value.id.Principal;

/**
 * 本机工作区：哪些工具现在能用、给模型的“本机工作区”一段、电脑上的 {@code AGENTS.md}。
 * 一个工具能用要四样都满足：总开关开、它那一类的子开关开、电脑连着、连接程序允许这一类（浏览器还要协议 5 起、电脑上找到了浏览器）。
 */
public final class LocalWorkspace implements ToolGroup {

    /** 总开关，也是转发工具的服务端名。 */
    public static final String MASTER = "local-workspace";
    /** “技能”和“知识库”两个子开关。知识库没有自己的工具，只管 system 里那一段。 */
    public static final String SKILLS = "skills";
    public static final String WIKI = "wiki";
    /** “浏览器”子开关：电脑上的 Chrome 或 Edge，11 个 {@code browser_*} 工具（local-browser）。 */
    public static final String BROWSER = "browser";
    /** 插件页上的开关：总开关和读文件、Git 查询、改文件、命令行、技能、知识库、浏览器七个子开关，默认都开。 */
    public static final List<String> SWITCHES = List.of(MASTER, "fs-read", "git", "file-edit", "shell", SKILLS, WIKI, BROWSER);
    /** {@code ContextAssembled.toolSources} 里的来源。 */
    public static final String SOURCE = "local";
    /** 从这个协议版本起，连接程序按会话的工作目录解析相对路径。 */
    static final int SESSION_DIRS = 3;
    /** 从这个协议版本起，连接程序会列技能和 wiki 页、有 {@code skill} 工具。 */
    public static final int SKILL_LISTS = 4;
    /** 从这个协议版本起，连接程序能驱动电脑上的浏览器。 */
    public static final int BROWSER_TOOLS = 5;

    /** “浏览器”下面可以逐个勾的工具（qagent-align 需求 2），顺序同定义。 */
    private static final Map<String, List<String>> CHOICES = Map.of(BROWSER,
            BrowserTools.all().stream().map(tantalum.local.tool.LocalTool::name).toList());
    private static final java.util.Set<String> PICKABLE = CHOICES.values().stream().flatMap(List::stream)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** 每一类工具对应的子开关。 */
    /** 开关的种类；这个文件里的 {@code Kind} 是本机工具的种类（读、写、git、shell）。 */
    private static final tantalum.shell.plugin.Kind LOCAL = tantalum.shell.plugin.Kind.LOCAL;
    private static final Map<Kind, String> SWITCH = Map.of(
            Kind.READ, "fs-read", Kind.GIT, "git", Kind.WRITE, "file-edit", Kind.SHELL, "shell", Kind.SKILL, SKILLS,
            Kind.BROWSER, BROWSER);

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
    public Map<String, List<String>> choices() {
        return CHOICES;
    }

    /** 可以逐个勾的工具还要自己的勾在；别的工具只看它那一类。 */
    @Override
    public boolean enabled(Principal user, Tool tool) {
        return tool instanceof LocalTool local && enabled(user, local.kind()) && picked(user, local.name());
    }

    private boolean picked(Principal user, String tool) {
        return !PICKABLE.contains(tool) || switches.enabled(user, LOCAL, tool);
    }

    /** 插件页上逐个勾的状态：子开关名 → 工具名 → 勾没勾。和电脑连没连无关，是用户自己的设置。 */
    public Map<String, Map<String, Boolean>> choices(Principal user) {
        Map<String, Map<String, Boolean>> states = new LinkedHashMap<>();
        CHOICES.forEach((child, tools) -> {
            Map<String, Boolean> each = new LinkedHashMap<>();
            tools.forEach(tool -> each.put(tool, switches.enabled(user, LOCAL, tool)));
            states.put(child, each);
        });
        return states;
    }

    /**
     * {@code skill} 工具还要用户填了技能目录：没填的人工具列表和以前一样。子开关下面的工具可以逐个勾时，
     * 一个都没勾就等于这一类关着（给模型的那一段里也不提它）。
     */
    public boolean enabled(Principal user, Kind kind) {
        String child = SWITCH.get(kind);
        return switches.enabled(user, LOCAL, MASTER)
                && switches.enabled(user, LOCAL, child)
                && links.status(user).map(status -> allows(status.hello(), kind)).orElse(false)
                && (kind != Kind.SKILL || dirs.skillsDir(user).isPresent())
                && (!CHOICES.containsKey(child) || CHOICES.get(child).stream().anyMatch(tool -> switches.enabled(user, LOCAL, tool)));
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
     * 连接程序允许不允许这一类：改文件要 {@code --allow-write}，命令行要沙箱可用，Git 要电脑上装了 git，浏览器要
     * {@code --allow-browser} 而且找到了 Chrome 或 Edge。Git 不看目录是不是仓库：
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
            // 要 --allow-browser 而且电脑上找到了 Chrome 或 Edge；旧的连接程序不送这一项
            case BROWSER -> hello.protocol() >= BROWSER_TOOLS && hello.browser() != null && hello.browser().available();
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
        return links.status(user).map(status -> describe(status.hello(), enabled(user, Kind.WRITE), enabled(user, Kind.SHELL),
                enabled(user, Kind.BROWSER), dir));
    }

    static String describe(Hello hello, boolean write, boolean shell) {
        return describe(hello, write, shell, false, null);
    }

    static String describe(Hello hello, boolean write, boolean shell, String dir) {
        return describe(hello, write, shell, false, dir);
    }

    /**
     * {@code browser}：这个用户现在能不能用浏览器工具（开关、连接程序都允许）。
     * 会话有工作目录、连接程序是协议 3 起时写 {@code Working directory}，再列全部允许的目录；目录不在允许的范围里也如实写，
     * 不退回主目录。没有目录或协议 2（连接程序还不认会话目录）时照旧写主目录。
     */
    static String describe(Hello hello, boolean write, boolean shell, boolean browser, String dir) {
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
        // 能用时才写这一句（不能用时模型的工具列表里本来就没有 browser_*）：没有浏览器的电脑上这一段和以前逐字相同
        if (browser && hello.browser() != null) {
            text.append(" Browser: ").append(hello.browser().name())
                    .append(hello.browser().headless() ? ", no visible window." : ", window visible to the user.");
        }
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
