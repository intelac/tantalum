package tantalum.shell.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import tantalum.shell.access.PluginGate;
import tantalum.shell.knowledge.KnowledgeBase;
import tantalum.shell.local.LocalWorkspace;
import tantalum.shell.plugin.ContextContributor;
import tantalum.shell.plugin.Tool;
import tantalum.shell.route.ChoiceCheck;
import tantalum.shell.route.ModelInfo;
import tantalum.shell.route.Route;
import tantalum.shell.route.Routes;
import tantalum.shell.settings.CompactionPolicy;
import tantalum.shell.settings.UserSettings;
import tantalum.shell.skill.SkillCatalog;
import tantalum.shell.skill.SkillSection;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.wiki.WikiSection;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.Command;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.ToolUnlock;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.ContextSection;

/**
 * 组装 system prompt 和工具列表，完整写进事件，回放时不再读文件。
 * system 的顺序固定：基础提示 → 插件的固定说明（如记忆指引）→ 共享知识 → 本机工作区 → AGENTS.md → 技能清单 → wiki →
 * 插件的可变段（如该用户的记忆索引）；每段只在有内容时加入。插件的两样来自 {@link ContextContributor}，只取这个用户开着的插件，按注册顺序。
 * 本机工作区一段、{@code AGENTS.md}、技能清单和 wiki 都来自用户连着的电脑，没连上就没有；服务器目录里的不再读。
 * 索引放在最后，钩子追加的内容在它之后。会话第二个运行起内核只比较这几段，把变化追加给模型，不再换 system。
 */
public final class ContextHandler implements Handler {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ContextHandler.class);

    static final String KNOWLEDGE_HEADING = "## Shared knowledge";
    /** 今天的日期那一段的名字。 */
    public static final String TODAY_TAG = "today";
    static final String KNOWLEDGE_TAG = "shared-knowledge";
    static final String AGENTS_TAG = "project-instructions";
    static final String LOCAL_TAG = "local-workspace";
    /** 开着的插件给模型的说明（{@code PluginInfo.modelNote}，QAgent 问题 10）。 */
    static final String PLUGIN_NOTES_TAG = "plugin-rules";
    static final String PLUGIN_NOTES_HEADING = "## Rules set by the user's plugins";
    /** 电脑主目录里的 AGENTS.md 最多这么多字节，超出的截掉并注明，免得一个文件挤占整个上下文。连接程序先截过，这里再查一遍。 */
    static final int AGENTS_MAX_BYTES = 64 * 1024;

    private final LocalWorkspace local;
    private final String basePrompt;
    private final ToolCatalog catalog;
    private final PluginGate gate;
    private final List<ContextContributor> contributors;
    private final KnowledgeBase knowledge;
    private final String fixedNote;
    private final CompactionPolicy compaction;
    private final Routes routes;
    private final ChoiceCheck check;
    private final UserSettings users;
    private final Function<SessionId, String> dirs;
    private volatile java.util.function.Predicate<SessionId> offRecord = session -> false;
    private volatile java.util.function.Function<SessionId, tantalum.value.task.Tasks> tasks = session -> tantalum.value.task.Tasks.NONE;
    private final SkillCatalog skills;

    /**
     * {@code fixedNote} 是部署决定的固定说明（如外部工具结果不可信），放在基础提示之后，不随运行变化。
     * {@code compaction} 每次按这个用户当前选的压缩点和这次用的模型的窗口算出压缩设置，随事件写进 journal。{@code routes}、{@code users} 用来定这次用哪个模型。
     * {@code dirs} 查会话的工作目录（没有为 null），写进给模型的“本机工作区”一段；写出的文字随事件进 journal，回放不再查。
     */
    public ContextHandler(LocalWorkspace local, String basePrompt, ToolCatalog catalog, PluginGate gate, List<ContextContributor> contributors,
                          KnowledgeBase knowledge, String fixedNote, CompactionPolicy compaction, Routes routes, UserSettings users,
                          Function<SessionId, String> dirs) {
        this(local, basePrompt, catalog, gate, contributors, knowledge, fixedNote, compaction, routes, users, dirs, new SkillCatalog(local));
    }

    /** {@code skills} 向电脑要 {@code AGENTS.md}、技能清单和 wiki 页；和插件页、点名共用一个，它留着每个用户最近一次的清单。 */
    public ContextHandler(LocalWorkspace local, String basePrompt, ToolCatalog catalog, PluginGate gate, List<ContextContributor> contributors,
                          KnowledgeBase knowledge, String fixedNote, CompactionPolicy compaction, Routes routes, UserSettings users,
                          Function<SessionId, String> dirs, SkillCatalog skills) {
        this.skills = skills;
        this.dirs = dirs;
        this.gate = gate;
        this.fixedNote = fixedNote;
        this.compaction = compaction;
        this.routes = routes;
        this.check = new ChoiceCheck(routes);
        this.users = users;
        this.local = local;
        this.basePrompt = basePrompt;
        this.catalog = catalog;
        this.contributors = List.copyOf(contributors);
        this.knowledge = knowledge;
    }

    private volatile long mainWindowTokens = MainWindow.DEFAULT_TOKENS;
    private volatile java.time.Clock clock;
    private volatile Workspaces workspaces;

    /** 给“工作区”一段用的数据：这个用户的工作区，和他的会话（不含“不进记忆”的、归档的）各自的概况。 */
    public interface Workspaces {
        List<tantalum.shell.project.Project> list(Principal user);

        List<WorkspaceSection.Row> sessions(Principal user);
    }

    /** 接上以后，上下文里多一段“工作区”（{@link WorkspaceSection}）。不设（单元测试）就没有这一段。 */
    public ContextHandler workspaces(Workspaces source) {
        this.workspaces = source;
        return this;
    }

    /** 引擎的钟：固定部分后面、各段前面写一行今天的日期（按这个人的时区）。不设（单元测试）就不写。 */
    public ContextHandler clock(java.time.Clock clock) {
        this.clock = clock;
        return this;
    }

    /** 主窗口保留多少最近的原话（token，memory-final-plan 第 11 节）。 */
    public ContextHandler mainWindowTokens(long tokens) {
        this.mainWindowTokens = Math.max(500, tokens);
        return this;
    }

    /** 查会话是不是“不进记忆”的（给插件的 {@link ContextContributor.Situation}）；不设时都不是。 */
    public ContextHandler offRecord(java.util.function.Predicate<SessionId> lookup) {
        this.offRecord = lookup;
        return this;
    }

    /** 查会话上任务的那一块（放出去还没回来的、是不是为任务开的）；不设时都没有。 */
    public ContextHandler tasks(java.util.function.Function<SessionId, tantalum.value.task.Tasks> lookup) {
        this.tasks = lookup;
        return this;
    }

    /** 给为任务开的会话的固定说明：它只做这一件事，交回去的只有最后一条回答。 */
    static final String TASK_NOTE = """
            ## This is a task
            Another agent handed you this piece of work and went on with something else. You start with nothing but the             message below: you do not see the conversation it came from, and you cannot ask the agent that sent it.             Do the work with the tools you have. Only your last message goes back, so end with the result itself:             what you found or did, where (paths, names), and anything you could not do or are unsure of.             Do not address the user: your reader is the agent that sent this.""";

    /** 段名：放出去还没回来的任务。 */
    static final String TASKS_TAG = "tasks";
    static final String TASKS_HEADING = "Tasks you started that have not come back yet. Each result arrives in this conversation on its own, "
            + "marked as the result of that task; there is nothing to check or wait for.";

    static String tasksText(List<tantalum.value.task.OpenTask> open) {
        StringBuilder text = new StringBuilder(TASKS_HEADING);
        for (tantalum.value.task.OpenTask task : open) {
            text.append("\n- ").append(task.id()).append(task.agent() == null ? "" : " (" + task.agent() + ")").append(": ")
                    .append(task.brief()).append(" — started ").append(task.started());
        }
        return text.toString();
    }

    @Override
    public Event handle(Command command) throws IOException, InterruptedException {
        Principal principal = command.run().principal();
        tantalum.value.task.Tasks taskState = tasks.apply(command.run().session());
        tantalum.value.task.TaskOrigin origin = taskState == null ? null : taskState.origin();
        List<ContextSection> sections = new ArrayList<>();
        String shared = knowledge.text();
        if (!shared.isEmpty()) {
            sections.add(new ContextSection(KNOWLEDGE_TAG, escape(KNOWLEDGE_HEADING + "\n\n" + shared), false));
        }
        String dir = dirs.apply(command.run().session());
        Optional<String> workspace = local.section(principal, dir);
        workspace.ifPresent(text -> sections.add(new ContextSection(LOCAL_TAG, escape(text), false)));
        Workspaces source = workspaces;
        if (source != null) {
            // 列出来的不含“不进记忆”的会话：它们的标题不该出现在别的会话的上下文里
            // 按行比较：之后变了只把变的那几行告诉模型
            // 别的会话的标题是用户以前的原话：插件说不给的（记忆插件：忘掉的事）不写标题
            // 主窗口不列：它的标题是随口说的话，列出来只会把旧话带进每个会话
            SessionId here = command.run().session();
            List<WorkspaceSection.Row> rows = source.sessions(principal).stream()
                    .filter(row -> row.summary().id().equals(here) || !MainWindow.is(row.summary().id()))
                    .map(row -> hidden(principal, row.summary())
                    ? new WorkspaceSection.Row(new tantalum.shell.conversation.SessionSummary(row.summary().id(), "", row.summary().lastActivity(),
                            row.summary().runStatus(), row.summary().dir(), row.summary().offRecord()), row.tokens()) : row).toList();
            // 这个会话自己那一行从第一句话起就在：第一次组装时它可能还没进会话列表，不补上的话第二句话时这一段会平白变一次
            SessionId self = command.run().session();
            if (rows.stream().noneMatch(row -> row.summary().id().equals(self))) {
                rows = new java.util.ArrayList<>(rows);
                rows.add(new WorkspaceSection.Row(new tantalum.shell.conversation.SessionSummary(self, "", clock == null
                        ? java.time.Instant.EPOCH : clock.instant(), tantalum.shell.conversation.SessionSummary.IDLE, dirs.apply(self)),
                        java.util.OptionalLong.empty()));
            }
            sections.add(new ContextSection(WorkspaceSection.NAME, escape(WorkspaceSection.text(source.list(principal), rows,
                    command.run().session(), clock == null ? null : clock.instant())), true));
        }
        List<String> notes = gate.modelNotes(principal);
        if (!notes.isEmpty()) {
            sections.add(new ContextSection(PLUGIN_NOTES_TAG, escape(PLUGIN_NOTES_HEADING + "\n"
                    + String.join("\n", notes.stream().map(note -> "- " + note).toList())), true));
        }
        // 一次往返，最多等 2 秒：没回的话 AGENTS.md 这次不带，技能和 wiki 用上一次的（LocalLinks 记一行日志）
        SkillCatalog.Listing listing = workspace.isEmpty() ? new SkillCatalog.Listing(null, null, null) : skills.fetch(principal, dir);
        String agents = listing.agents() == null ? "" : capped(listing.agents(), AGENTS_MAX_BYTES);
        if (!agents.isEmpty()) {
            sections.add(new ContextSection(AGENTS_TAG, escape(agents), false));
        }
        // 这两段自己转义过（描述、路径、标题里没有尖括号，index.md 的闭合标签改过），模板里的标签原样留着
        String skillList = SkillSection.text(listing.skills(), local.dirs().off(principal));
        if (!skillList.isEmpty()) {
            sections.add(new ContextSection(SkillSection.NAME, skillList, false));
        }
        String wiki = WikiSection.text(listing.wiki());
        if (!wiki.isEmpty()) {
            sections.add(new ContextSection(WikiSection.NAME, wiki, false));
        }
        List<ContextContributor.Contribution> contributions = contributions(principal,
                new ContextContributor.Situation(command.run().session(), dir, offRecord.test(command.run().session())));
        for (ContextContributor.Contribution contribution : contributions) {
            String body = contribution.sectionBody() == null ? "" : contribution.sectionBody().strip();
            if (contribution.sectionName() != null && !body.isEmpty()) {
                sections.add(new ContextSection(contribution.sectionName(), escape(body), contribution.byLine()));
            }
        }
        StringBuilder system = new StringBuilder(basePrompt);
        contributions.forEach(contribution -> append(system, contribution.fixedText() == null ? "" : contribution.fixedText().strip()));
        append(system, fixedNote);
        // 为任务开的会话不是主窗口，哪怕放出它的是主窗口（它的编号以主窗口的编号开头）
        boolean main = origin == null && MainWindow.is(command.run().session());
        if (main) {
            append(system, MainWindow.NOTE);
        }
        if (origin != null) {
            append(system, TASK_NOTE);
            if (origin.instructions() != null && !origin.instructions().isBlank()) {
                append(system, origin.instructions().strip());
            }
        } else if (taskState != null && !taskState.open().isEmpty()) {
            // 按行比较：放出一个、回来一个，只把变的那一行告诉模型；压缩之后模型仍然知道外面有什么
            sections.add(new ContextSection(TASKS_TAG, escape(tasksText(taskState.open())), true));
        }
        // 今天的日期（2026-11-16 记忆评测：模型不知道今天几号，“三天前”只能猜，反复搜到超时）：只写到日期，一天才变一次。
        // 它是一段（排在各段最前面），不写进固定部分：内核把固定部分定在第一次组装时，写在那里的话跨了天的会话日期不会变；
        // 作为一段，变了的时候内核照别的段一样告诉模型（2026-11-17 review）
        java.time.Clock now = clock;
        if (now != null) {
            sections.add(0, new ContextSection(TODAY_TAG, today(now.instant(), users == null ? java.time.ZoneId.systemDefault()
                    : users.timezone(principal).orElse(java.time.ZoneId.systemDefault())), false));
        }
        sections.forEach(section -> append(system, section.tagged()));
        // system 里不写模型名：换模型前后 system 逐字节相同，思考签名才继续有效，换模型的事只在内核追加的说明里
        AssembleContext assemble = (AssembleContext) command;
        ModelChoice model = choose(assemble, principal);
        // 全部工具加上这个用户现在开着的名单：会话的工具列表从头到尾不变，开关的变化由内核追加成消息
        List<String> enabled = gate.enabledTools(principal);
        // 按需出现的工具（browser-on-demand）：模型支持会话中途的工具变化时，规则里要加的先不开，调过入口后内核再开
        List<ToolUnlock> unlocks = routes.describe(model).map(ModelInfo::toolChanges).orElse(false) ? gate.unlocks(enabled) : List.of();
        if (!unlocks.isEmpty()) {
            java.util.Set<String> later = new java.util.HashSet<>();
            unlocks.forEach(rule -> later.addAll(rule.add()));
            enabled = enabled.stream().filter(name -> !later.contains(name)).toList();
        }
        List<tantalum.value.model.ToolSpec> specs = catalog.specs();
        // 模型不支持会话中途的工具变化（Haiku 等）时也一样：这个人现在没开着的工具不放进工具表。否则它们算“一开始就关着”，
        // 这个会话的第二句话就选不了这个模型了。接着连接程序的 Mac 上 powershell 永远关着，所以每个普通会话都中招
        // （2026-11-17 真模型场景发现）。会话的工具表在第一次组装时定下，这样的会话之后新开的工具要等压缩或新会话。
        if (!routes.describe(model).map(ModelInfo::toolChanges).orElse(true)) {
            java.util.Set<String> open = new java.util.HashSet<>(enabled);
            specs = specs.stream().filter(spec -> open.contains(spec.name())).toList();
        }
        if (origin != null) {
            // 任务里没有的工具（再放任务、写记忆、加工作区）和 agent 定义没列的工具，都不进这个会话的工具表
            java.util.Set<String> allowed = new java.util.HashSet<>();
            for (tantalum.shell.plugin.Tool tool : catalog.tools()) {
                if (tool.inTasks() && (origin.tools() == null || origin.tools().contains(tool.name()))) {
                    allowed.add(tool.name());
                }
            }
            enabled = enabled.stream().filter(allowed::contains).toList();
            specs = specs.stream().filter(spec -> allowed.contains(spec.name())).toList();
        }
        CompactionSettings settings = compaction(principal, model);
        if (main && settings != null) {
            settings = MainWindow.settings(settings, system.toString(), specs, mainWindowTokens);
        }
        return new ContextAssembled(command.id(), system.toString(), specs, settings, sections,
                enabled, gate.enabledPlugins(principal), sources(principal), model, gate.pluginParams(principal), unlocks);
    }

    private boolean hidden(Principal principal, tantalum.shell.conversation.SessionSummary summary) {
        String title = summary.title();
        if (title == null || title.isBlank()) {
            return false;
        }
        for (ContextContributor contributor : contributors) {
            try {
                if (contributor.hides(principal, title, summary.lastActivity())) {
                    return true;
                }
            } catch (RuntimeException failure) {
                // 插件答不上来时宁可不给标题
                return true;
            }
        }
        return false;
    }

    /** system 的最后一行：{@code Today is 2026-11-16 (Monday, time zone Asia/Tokyo).} */
    static String today(java.time.Instant now, java.time.ZoneId zone) {
        java.time.LocalDate day = java.time.LocalDate.ofInstant(now, zone);
        return "Today is " + day + " (" + day.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
                + ", time zone " + zone.getId() + ").";
    }

    /** 这个用户开着的插件各自的一份；插件出错只记日志，这一份不带。 */
    private List<ContextContributor.Contribution> contributions(Principal principal, ContextContributor.Situation situation) {
        List<ContextContributor.Contribution> all = new ArrayList<>();
        for (ContextContributor contributor : contributors) {
            if (!gate.pluginEnabled(principal, contributor.plugin())) {
                continue;
            }
            try {
                contributor.contribute(principal, situation).ifPresent(all::add);
            } catch (RuntimeException failure) {
                LOG.warn("plugin {} failed to contribute context: {}", contributor.plugin(), failure.toString());
            }
        }
        return all;
    }

    /**
     * 这次用哪个模型：提交里带了就用它（提交时已经查过；这之间路线掉线的话照用，
     * 调用失败走现有的模型失败流程）；没带用会话现在的；那个不可用或为空，用这个用户上次的选择；也不可用就用管理员的默认。
     */
    private ModelChoice choose(AssembleContext assemble, Principal principal) {
        if (assemble.requested() != null) {
            return assemble.requested();
        }
        return check.fallback(assemble.current(), users.modelChoice(principal));
    }

    /** 按这个模型的窗口和输出上限、这条路线的估算系数算；路线不在了（调用会失败）时为 null，内核留着原来的。 */
    private CompactionSettings compaction(Principal principal, ModelChoice model) {
        Optional<Route> route = routes.route(model.route());
        Optional<ModelInfo> info = routes.describe(model);
        if (route.isEmpty() || info.isEmpty()) {
            return null;
        }
        return compaction.settings(principal, info.get(), route.get().settings().bytesPerToken());
    }

    /** 每个工具从哪来，由 {@link PluginGate#source} 决定：{@code builtin}、{@code local} 或 {@code web}（旧会话里还有 {@code mcp:<服务器>}）。 */
    private Map<String, String> sources(Principal principal) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (Tool tool : catalog.tools()) {
            sources.put(tool.name(), gate.source(tool));
        }
        return sources;
    }

    /** 让正文里的闭合标签跳不出包裹。这只降低注入风险，不是保证。 */
    private static String escape(String text) {
        return text.replace("</", "<\\/");
    }

    /** 超限时按 UTF-8 边界截断并告诉模型。 */
    private static String capped(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return new String(bytes, StandardCharsets.UTF_8).strip();
        }
        int cut = maxBytes;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8).strip()
                + "\n\n(This file was truncated at " + maxBytes + " bytes.)";
    }

    private static void append(StringBuilder system, String section) {
        if (section.isEmpty()) {
            return;
        }
        if (!system.isEmpty()) {
            system.append("\n\n");
        }
        system.append(section);
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return new CommandDropped(command.id(), "cannot assemble context: " + failure.getMessage());
    }
}
