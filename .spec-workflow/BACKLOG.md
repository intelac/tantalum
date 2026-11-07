# 待办总表

所有未完成事项只记在这里。具体任务仍在各 spec 的 `tasks.md`，这里只记位置和状态。最后核对：2026-11-07（`windows-server` 合并之后，按另一位审阅者的 23 条反馈逐条对过代码）。

## 0. 离 0.1 还差什么

第一个对外版本叫 0.1（Yi 2026-11-07 定，1.0 要等在企业里真用过、Super-Agent 方向做出来）。下面做完就发 0.1 Release（带 macOS、Linux、Windows 可直接下载的 jar，README 顶部加截图）。按顺序：

| spec | 要做什么 | 性质 |
|---|---|---|
| `waiting-state` | 可持久的等人状态：问题写进 journal，用户的回答作为事件回来，不占工作轮数，服务器重启也不丢；工具审批插件改用它。现在审批是“等 `wait` → 待定 → 再问”，受 `max-work-rounds`（8）约束，人离开几小时任务就失败 | 设计缺一块。Super-agent 调研候选 ① |
| `ops-hardening-2` | ① 登录会话不随重启丢（现在是 Tomcat 内存会话，`HttpSessionSecurityContextRepository`）；② 网页上管用户（增删、重置密码，现在只有 `users add` 命令行）；③ 放行名额按用户限（`LaneDispatcher` 是一个全局信号量）；④ 载入失败的会话过一会自动重试（现在 `UserLane` 记成 `Failed`，“refusing it until restart”） | ①③④ 是 bug，② 缺功能。合并了第 4 节 `ops-hardening` 阶段 2、阶段 5 两条 |
| `hook-patch` | `beforeModelCall` 的 `Allow` 补丁改成追加 `role: system` 消息和 `tool_addition`，不再拼进 system 和工具列表（`HookChain`、`PromptProjection`、`ClaudeRequest`）。现在补丁一变，Claude 路线整个会话的思考签名失效。示例插件都没用会变的补丁，所以还没踩到 | bug，1.0 前锁插件 SDK 要先改 |
| 内网默认拦（待 Yi） | `tantalum.web.block-private-network` 默认改 `true`。2026-11-03 定“同 DSH 默认放行”，但 DSH 是单用户本机程序；我们对外定位是多用户企业底座，一个用户就能让 agent 扫内网、拿云元数据凭据 | 改默认值 |

0.1 之后按 Super-Agent 方向（见 README“方向”）：后台任务（慢工具立刻返回“已开始”，完成是事件）→ 外部事件来源（先接 GitHub：issue、评论、CI 结果；代码先筛，允许名单、团队规则、观察模式）→ 用它盯 Tantalum 自己的仓库接活、提 PR（这就是垂直验证，代替简报里的 COBOL 场景，Yi 2026-11-07 同意不把 COBOL 垂直 agent 排第一）→ 拆 `RunState`、`SessionState`（全是手写 `withX`），再做子 agent 和 Router/Worker → 定时事件和看门狗。

## 1. 正在做

| 事项 | 状态 | 位置 |
|---|---|---|
| `code-simplification` | 39/39，2026-11-03 完成（结果见 tech.md）。`SessionIndex`、`McpConnection` 仍超过 300 行，理由见 tech.md | `specs/code-simplification/tasks.md` |
| `trace-view` | 23/23，2026-11-03 完成（结果见 tech.md）。轨迹页（时间概览、记录表、检查器）和统计页签，和 DSH 对等，另加钩子决定和插件工作；嵌套子工具和 `run_code` 源码视图等 sandbox 之后再做；DSH 统计页的“事件行”（请求头、系统提示词、工具集变更单独成行）没有做，轨迹页已经能看到这些 | `specs/trace-view/tasks.md` |
| `hook-coverage` | 19/19，2026-11-03 完成（结果见 tech.md）。每个钩子都有示例插件；工作轮数上限；真实管线上的钩子矩阵 86 个测试；钩子不做 I/O 的 ArchUnit 规则。顺带修了两个缺陷：同一步开始又结束的运行在对话里没有结束原因；关服务时被打断的处理器结果被当成正常结果落盘（违反 ops-hardening H8） | `specs/hook-coverage/tasks.md` |
| `attachments` | 完成（2026-11-03，23 个任务，4 个阶段）。提问时附图片和文件，每用户独立存储，发给模型，对话页和轨迹页显示。没有引用的附件怎么清理，等以后做会话删除时一起做（A9） | `specs/attachments/requirements.md` |
| `record-results` | 21/21，2026-11-04 完成（结果见 tech.md）。内核每一步写一行结果，事件和结果一起落盘后才放行命令；恢复和查询只读结果行、不调内核和钩子；`java -jar tantalum.jar check` 检查新版本会不会改变旧会话。旧格式的会话不迁移（Yi 定）。两项待定已由 `one-line-journal` 解决：大小按真实会话衡量（Yi 2026-11-06 定），冷载入 320 ms | `specs/record-results/tasks.md` |
| `one-line-journal` | 23/23，2026-11-06 完成（结果见 tech.md）。一步一行（事件和它的结果），存好才回“已接受”；配上的插件、工具、MCP 只在变化时记；工具没了告诉模型（压缩后也说）和用户（对话页一行）；启动预热。真实会话 103.8%，冷载入 320 ms（record-results 之前 415 ms） | `specs/one-line-journal/tasks.md` |
| `core-tools` | 28/28，2026-11-04 完成（结果见 tech.md）。窗口读取和五个文件工具、先读后写；插件页（每人自己的开关，统一过滤入口）；会话中途换工具从下一次提问生效且不破坏思考签名 | `specs/core-tools/tasks.md` |

`ops-hardening` 50/50 已完成（2026-11-02）。

## 2. 已定要做，还没开 spec

按计划顺序。

| 事项 | 已知内容 | 还缺什么 |
|---|---|---|
| 示例钩子插件上 8091（2026-11-06 Yi 发现插件页一个钩子插件都没有） | hook-coverage 做了 7 个钩子的示例插件（`tantalum-examples`），但“示例插件不打进可执行 jar”，8091 一个都没加载。Yi 2026-11-06 定：全部装上 8091，示例插件打成单独的 jar 启动时加载；只读、工具审批、每日额度这类策略插件改成默认关、在插件页由用户打开；引用核对不再读服务器上的文件（服务器上只有 `read_attachment` 读文件）；工具审批加一个“这个会话里一直允许”（Yi 2026-11-06）。同一轮顺带修本机工作区的两处（Yi 2026-11-06 实际用时发现）：① Git 工具不再要求主目录是仓库，“Git 查询”开着、装了 git 就一直提供，调用时路径不在仓库里就返回“不是 Git 仓库”，子文件夹和任何 `--allow` 目录里的仓库都能查（仓库顶层必须在允许目录里这条保留）；去掉插件页“主目录不是 Git 仓库”。② 插件页的启动命令说明要先装好 `tantalum-connect`、先 `cd` 到项目目录；连接程序拒绝把家目录或 `/` 当允许目录 | 完成（2026-11-06，分支上，待合并后改 8091 的启动命令）：示例插件单独的 jar 经 `loader.path` 加载、`tantalum.examples.*` 配置；策略插件默认关（SDK `defaultEnabled()`、`failClosed()`，管理员 `tantalum.hooks.required` 强制开启）；引用核对只看会话里读到的行；工具审批在对话页上问，“这个会话里一直允许”记进 journal；Git 工具按路径找仓库（连接协议 2）；连接程序拒绝家目录和 `/`。部署命令和配置见 README“写插件 › 部署示例插件” |
| 画面上选模型和思考强度 `model-picker`（2026-11-06 Yi：“像 dsh 一样在画面上可以选择模型和 thinking”） | 现在模型和思考强度是整个服务器一份配置（`TantalumProperties.Claude(model, …, effort, …)`、`OpenAi(baseUrl, model, …)`），所有用户、所有会话都一样；输入框旁只显示模型名。要定：可选的模型从哪来（Claude 读 Models 接口，本地服务读它自己的模型列表，管理员可限定）；按会话还是按用户记；会话中途换模型时历史里的思考签名失效、上下文长度变了（`compaction-threshold` 已按模型取窗口）；思考强度按模型给可选档位（Claude 的 `effort`、关闭思考），不支持的模型不显示。开 spec 时先查 DSH 的选模型和思考档位（`.agents/notes/implemented/architecture/2026-07-20-routed-model-context-and-compaction-policy.md` 等）和 pi 的 `/model`、思考档位 | 完成：`model-picker` spec（2026-11-07），8091 演示见任务 21 |
| 控制台国际化 `console-i18n`（2026-11-06 Yi：“菜单一会儿英语一会儿中文，像 dsh 一样做个国际化”） | 全部完成（2026-11-07，结果见 tech.md“console-i18n”；8091 三种语言看过，顺手修了切换语言后焦点掉出设置窗口、日文时间轴标签换行）。中文、英文、日文；控制台默认跟浏览器，设置页可改，按用户记在服务器上；服务器的错误改成代码加参数，插件作者的文字是 `LocalText`；连接程序和命令行跟系统语言或 `--lang`；给模型的文字不翻译 | 8091 上三种语言各看一遍（任务 21） |
| 沙箱 → 反向沙箱 `local-workspace` | 2026-11-03 Yi 提出“反向沙箱”：agent 在服务器，工具在用户本机执行，本机装连接程序主动连服务器；2026-11-06 Yi 强调“非常重要”，定了插件页的样子（总开关“本机工作区”，读文件、Git 查询、改文件、命令行子开关）。Yi 定：有期限令牌代替每次询问；连接程序用 Java；命令隔离用微软 MXC；先只支持 macOS | 2026-11-06 开 spec `local-workspace`，24 个任务全部完成（MXC 沙箱里的 `bash`、macOS 安装包、说明，测量见 tech.md；8091 上用真实 Claude 修好一个失败的 Maven 测试）。Yi 定的顺序：接着做 `web-tools`，再做服务器装在 Windows 上；下面的 Windows 连接程序和代码索引之后再排 |
| Windows 连接程序（`local-workspace` 的下一个 spec） | `local-workspace` 只做了 macOS。消息格式、令牌、`AllowedDirs`（以后 `/` 和 `\` 都认）、`Calls` 的检查都不绑定 macOS；命令换成 `powershell`（pi 叫 `powershell`，DSH 叫 `pwsh`），沙箱换 MXC 的 Windows 后端、用同一套规则（Codex 在 Windows 上也用 MXC，只支持较新的 Windows 11，`windows-sandbox-rs/src/mxc-sandbox/README.md:1-50`）；安装包、首次运行的提示另做。`windows-server` 留下的：`TextFiles` 替换用户的文件后，原文件单独设的 ACL 会丢（新文件继承目录的 ACL），DSH 用 `ReplaceFileW` 保留（`fs/fs-local/src/win32.ts:103-122`）；连接程序的路径显示（`\` 分隔）、`Git.java` 的 `/dev/null`、`Observations` 在 Windows 上 `fileKey()` 为空，这些测试在 Windows CI 上跳过并写明了原因 | 开 spec；要在一台 Windows 机器上跑一遍 `MxcIT` 一类的真实测试才算完成 |
| 代码索引 `code-index`（`local-workspace` 的下一个 spec） | 插件页“本机工作区”下的一项：`symbol_search`、`outline`、`read_symbol`，在用户电脑上建索引，挂在同一个总开关下 | 选型（tree-sitter、ctags、LSP）要单独调研，查 pi、DSH 的做法后开 spec |
| 子 agent | 已确认要做，排在沙箱附近 | 开 spec；和沙箱的先后未定 |
| DeepSeek Super-agent 调研的候选（2026-11-03） | 按优先级：① 内核加可持久等人的等待状态（问题进 journal，用户回答作为事件回来，不占工作轮数）；② 异步工具 / 后台任务（立即返回“已开始”，完成是事件，回放不重跑），排在反向沙箱之前；③ 反向沙箱按文章 §5.2、§8.4 设计，服务器被攻破不能等于控制所有设备；④ 子 agent 拆成独立会话、固定的汇报状态、给人的消息预留名额；⑤ 大结果存到外面留续读入口，对象之间用 ID 互相引用；⑥ 运维状态接口和确定性的卡住检测。不适合多用户的：全局 Router、能改规则的 LLM 看门狗、对没人点名的消息主动出手、共用 agent 身份 | 等 Yi 决定做哪些、先后顺序 |
| 多实例 | Yi 决定这次不做。每个用户同一时刻归一个实例，网关按用户路由，实例挂了由别的实例接管。需要共享存储（网络盘的 fsync 和锁要实测）、索引库换 Postgres、共享工作区和登录会话、租约防脑裂、SSE 路由。序号和 ID 已留好（`ops-hardening` H14） | 开 spec；和沙箱、子 agent 的先后未定 |
| MCP 后续 | 每用户令牌、OAuth、stdio、人工审批 | Yi 决定要不要做、做哪几项 |
| 联网：`web_search`、`web_fetch`（2026-11-03 Yi 发现 agent 不能访问网页） | 现在内置工具只有 `grep`、`read_file` 和三个记忆工具，没有任何联网能力，也没有 `bash`。DSH 的做法（`.agents/notes/implemented/architecture/2026-06-24-web-capability-seam.md`）：模型看到的两个工具名和参数固定，由 `dsh-tool-web` 定义；搜索后端可换（Exa、Perplexity、DeepSeek），只注册能力不注册工具；抓取是单独的 HTTP 后端，管跳转、解码、大小上限，并有出站代理策略（`2026-08-27-outbound-proxy-policy.md`）。pi 没有联网工具，靠 `bash` 跑 curl。我们这边要定：搜索用哪家（要密钥，按管理员配置），抓取防内网访问（SSRF：拒绝回环、私网、云元数据地址，跳转后重新检查），结果大小上限和截断，结果进 journal 以便回放，用户能否关掉。Claude 自带的服务端搜索工具只对 Claude 有效，换本地模型就没了，不作为唯一方案。2026-11-03 临时方案：本机用 `@playwright/mcp@0.0.83`（HTTP，`localhost:8931`，无头、内存里的浏览器配置）经现有 MCP 客户端接上，放行导航、点击、输入、`evaluate`，禁止上传文件和 `run_code_unsafe`；实测能打开维基百科并读出内容，`--blocked-origins` 拦住了本机服务。临时方案的缺口：所有用户共用一个浏览器；只拦列出的地址，不是完整的内网防护；没有搜索接口。2026-11-03 实测“DeepSeek super agent 论文深度调查”两次都答“没找到”：Google 对无头浏览器返回限流页，模型没换 Bing（Bing 实测可用），只查了 arXiv 和 Hugging Face；实际是 DeepSeek 2026-10-31 发表的文章 *Exploring Super-agent Design* 和 GitHub 仓库 `deepseek-ai/dsh-superagent-prototype`，不在 arXiv。说明只有浏览器不够，搜索工具是必需的 | 2026-11-07 在 `web-tools` 完成（任务 1–25；任务 26 的 8091 真实演示由主会话做）：`web_fetch` 和每用户一个的无头浏览器（Playwright for Java，11 个工具），不做 `web_search`，搜索用浏览器打开 Bing 结果页（Yi 2026-11-06 定，不接收费接口）；内网默认不拦（2026-11-03 Yi 定，同 DSH），管理员开关 `tantalum.web.block-private-network` 打开后经同一个出口检查每个连接；8091 上临时的 `@playwright/mcp` 已去掉。说明见 README“联网”，测量见 tech.md。`web_search` 接口和读 PDF 以后另开 |
| 服务器装在 Windows 上（2026-11-03 Yi 问） | 现在跑不起来：数据目录的文件和目录创建时就设 POSIX 权限（600/700），Windows 的 NTFS 不支持，Java 直接报错（pi 在 Windows 上能用，是因为 Node 静默忽略 `mode`，`core/auth-storage.ts:37,71`）。要改：不支持 POSIX 权限时照常创建并靠用户目录的 ACL；检查路径拼接、文件锁、换行 | Yi 定：单独开 spec，排在 `core-tools` 之后；要在一台 Windows 机器上跑一遍测试才算完成。2026-11-07 开 spec `windows-server`（17 个任务）：`PrivateFiles`（POSIX 上照旧 600/700，Windows 上继承 ACL）、保留设备名、数据目录检查、计划任务脚本、GitHub Actions `windows-latest` 工作流（不用密钥）；17 个任务完成，`windows-latest` 全绿：<https://github.com/intelac/tantalum/actions/runs/42318846814>；工作流里另有 jar、连接程序、数据目录的冒烟和计划任务自测。真实 Windows 电脑上的手工检查没做（只有 CI，Yi 没有 Windows 电脑），没验证的几项写在 tasks.md 任务 17 的日志里 |
| MCP 工具结果里的图片（2026-11-03 Yi 问截图） | 现在 MCP 返回的图片被换成 `[image content omitted]`（`shell/mcp/McpResults.java:46`），临时接的 Playwright 浏览器也禁了 `browser_take_screenshot`，模型看不到任何截图。attachments 之后工具结果已能带图片：把 MCP 的 `ImageContent` 照附件规则规范化、存进本人附件目录、作为 `ToolOutput.images` 交给模型，再放开截图工具。Linux 服务器上无界面浏览器能截网页，但要装中文字体（如 `fonts-noto-cjk`）和浏览器依赖；桌面截图要显示器，归反向沙箱 | 2026-11-07 在 `web-tools` 完成：MCP 返回的 PNG、JPEG、WebP、GIF 照附件规则存进本人附件交给模型，其他类型换成一行说明；浏览器截图同样处理；会话文件里只有附件引用。中文字体、`--with-deps` 写在 README“联网” |
| 压缩点和 token 估算（2026-11-04 发现） | 默认 `tantalum.claude.context-window=200000`，而 `claude-sonnet-5-5` 是 1M（Models 接口返回 `max_input_tokens`），对话到约 18 万就压缩。估算“UTF-8 字节数 ÷ 3”对 Sonnet 5.5 是少算的：`count_tokens` 实测每 token 字节数 base64 1.05、id 1.48、sha256 1.52、JSON 1.80、英文 1.82、中文 2.25、Java 2.67，合计估算 ÷ 真实 = 0.76 | 2026-11-04 Yi 定：窗口不写死，是每个模型自己的变量（1M、256K 等取决于模型设定），用满它；画面上给百分比选项（50%、70%、85%、用满），压缩点 = 当前模型的上下文长度 × 百分比，每个用户自己设。上下文长度从模型那边读：Claude 读 Models 接口的 `max_input_tokens`，本地服务读它自己报的上下文长度；读不到才用配置值。Claude 的估算系数改成按实测宁可多算，本地 qwen 的另测。开 spec `compaction-threshold`，排在 `core-tools` 之后。2026-11-04 在 `compaction-threshold` 完成（本地 qwen 系数实测后定为 1.75） |
| 钩子补丁会破坏思考签名（2026-11-04 core-tools 设计时发现） | `beforeModelCall` 的 `Allow` 补丁每次调模型前往 system 追加文字、往工具列表加工具；只要补丁内容在一个会话里变化，历史里的思考签名就失效（和直接改工具列表同一个原因）。正确做法：补丁的文字改用追加的 `role: system` 消息，加工具改用 `tool_addition`。换模型的说明（`model-picker` M12）照只追加做：写成这次用户消息开头的文字，不改 system 和工具，没有引入新的签名问题 | 未排期；没有示例插件用到会变的补丁 |
| `read_file` 没有大小和行数控制（2026-11-03 发现） | 上限 1 MB、整文件返回，没有 offset/limit。上面那次调查里模型读了两个 132 KB、342 KB 的页面快照，一次塞进十几万 token，运行中途被迫压缩。pi 的 `read` 默认 2000 行或 50 KB 截断，带 offset/limit 续读（`packages/coding-agent/src/core/tools/read.ts`、`truncate.ts`）；DSH 大结果落文件留续读入口（`2026-07-08-tool-output-spill-files.md`） | 2026-11-04 在 `core-tools` 完成。2026-11-03 Yi 定：并进 `core-tools` spec（`read_file` 截断和 offset/limit 续读、`write`、`edit`、`ls`、`find`，内置工具），先做；联网另开 `web-tools`，排在它后面。`bash` 等沙箱 spec，在用户本机执行（Windows 上照 pi 找 Git Bash，另有 `pwsh`） |

## 3. 等 Yi 决定

| 事项 | 说明 |
|---|---|
| 内网访问默认拦不拦 | 见第 0 节。建议多用户默认拦，单机部署的人自己打开 |
| 第 0 节的顺序 | `waiting-state` → `ops-hardening-2` → `hook-patch` → 0.1 Release |

## 4. 已知缺口

已完成的 spec 留下的，还没有对应任务。

| 来源 | 缺口 | 处理 |
|---|---|---|
| `check`（2026-11-07 model-picker 时发现） | 8091 数据目录里 `yi/s-onelinesmoke`（2026-11-05 one-line-journal 的冒烟会话）seq 1 `ContextAssembled` 的会话结果和重放不一致；用更早的 jar 检查结果相同，不是后来的 spec 引起的。原因没查 | 未排期；查清是内核变了还是当时记错 |
| `windows-server` | 真实 Windows 电脑上没验证（Yi 没有 Windows 机器）：中文控制台输入中文密码、非管理员注册计划任务、Windows PowerShell 5.1、Windows 10/11 桌面版和中文系统、FAT32/网络盘/杀毒软件占文件、断电、arm64、`0.0.0.0` 的防火墙提示。Java 用系统代码页解码命令行参数，英文 Windows 上参数里的中文路径变成 `?`（README 让用 `TANTALUM_DATADIR`）；崩溃循环的服务每分钟重启没有上限；Windows 上每条出口连接用 2 个平台线程 | 记录，有用户在 Windows 上用时再补 |
| `spec-audit-fixes` | A8 重试范围比需求窄 | 未排期 |
| `spec-audit-fixes` | A7 “在途调用走完”只对普通失败成立；MCP 会话被服务端终止时在途调用会一起失败（SDK 2.1.0 行为） | 未排期，受 SDK 限制 |
| `spec-audit-fixes` | 需求 5.4 没有在连接层用两个账号验证 | 未排期 |
| `ops-hardening` 阶段 2 | 放行名额没有按用户限额：一个磁盘卡住的用户，如果有大量运行的工具结果在途，理论上能占满全部名额，拖住其他用户。现有测试只覆盖在途事件少于名额的情况 | 并入第 0 节 `ops-hardening-2` |
| `ops-hardening` 阶段 5 | 载入失败的会话到重启前一直拒绝（500），不在进程内重试；临时性失败（如索引库短暂不可用）也要重启才恢复。重启后那次提交的运行显示“被重启中断”，不是“失败” | 并入第 0 节 `ops-hardening-2` |
| `ops-hardening` 阶段 6 | SSE 补读对该用户末尾序号不早于位置的会话整个文件重放（不用快照，因为要逐个事件的内容）；一个用户的长会话多时补读仍会慢 | 记录。record-results 之后改为按结果行折叠，仍要读整个文件 |
| `ops-hardening` 阶段 8 | 关闭时不响应中断的 Handler 最多再等 2 秒就不管了，它之后产生的结果不会落盘，下次启动这个运行按 H9 收尾 | 记录 |
| `ops-hardening` 任务 49 | 快照只省约 37% 的冷载入时间（402 → 254 ms），快照文件比会话文件大 60%（5.6 MB 对 3.5 MB）。2026-11-03 Yi 决定去掉，冷会话只重放会话文件 | 已完成 |
| `ops-hardening` 任务 43 | 回收检查只在 cleanroom 处理事件时顺带做（设计“按批检查”）：服务器完全没有事件时，空闲会话不会被移出内存，直到下一个事件。内存仍受常驻上限（默认 1000）约束。要不要加定时检查由 Yi 决定 | 待 Yi |
| `ops-hardening` 任务 41 | 数据目录本身的权限没有收紧（可能是管理员自己建的）；它下面的文件和目录都是 600/700 | 记录 |
| `ops-hardening` 阶段 5 | 会话列表里不在内存的会话取索引的最近活动时间（最后一个事件的时间），在内存的取条目里最晚的时间，两者在“最后一个事件没有改变条目”时可能差几毫秒；重启前后对比测试里一致 | 记录 |
| `ops-hardening` 阶段 5 | 同一用户的一个大会话载入时，这个用户的其他会话要等它（同一用户内按序是设计决定）；其他用户不受影响 | 记录 |
| `ops-hardening` 阶段 3 | 内存占位被挤出、而对应事件还没写进索引时，重复提交可能漏判。要在写入间隔内有 10 万个新请求才会发生 | 不处理，记录 |

## 5. 长期限制

不是任务，记在 `steering/tech.md` 的 Known Limitations：Disruptor 未针对 Java 25 正式声明支持（全套测试天天在 Java 25 上跑，换成有界队列收益小，排最后）、SQLite 原生访问警告、模型并发默认为 1、strata 与其他任务共用、可执行 jar 约 300 MB（Playwright 浏览器驱动，可拆成可选包）、JDK 25 + Spring Boot 4.1 对保守的客户环境偏新。登录会话不持久化、没有用户管理界面已移到第 0 节。另有一条写在 README“回放能保证什么”：内核语义变化后旧会话回放可能不再逐字节相同。
