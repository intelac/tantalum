# Requirements Document

## Introduction

现在所有文件工具（`read_file`、`write_file`、`edit_file`、`list_dir`、`find_files`、`grep`）都在服务器上执行，读写的是服务器上每个用户的一个目录。这个目录只靠路径校验隔离，没有沙箱（`plugin/Workspace.java:9-12`），所以也一直没有 `bash`。用户真正想让 agent 动的，是**自己电脑上**的代码：没提交的改动、本机的工具链、公司内网。

本 spec 做“反向沙箱”（Yi 2026-11-03 提出，2026-11-06 定了插件页的样子，并说“非常重要”）：

- **agent 留在服务器，工具在用户自己的电脑上执行。** 用户在电脑上运行一个小的连接程序，由它主动连到服务器（电脑上不用开端口）。
- **只能动用户允许的东西。** 连接程序用 `--allow` 列出的目录才能进；加 `--allow-write` 才能改文件；加 `--allow-shell` 才能跑命令。这几项由电脑这边决定，服务器改不了。
- **所有读写文件、git、命令行都归“本机工作区”。** 插件页上一个总开关“本机工作区 `local-workspace`”，下面是读文件、Git 查询、改文件、命令行这几个子开关；电脑没连上或总开关关着，这些工具一个也没有。待办、知识库、个人记忆不归它管，照常在服务器上。
- **授权有期限。** 用户在网页上生成一个连接令牌，选有效期：半小时、1 小时、4 小时或 1 天（Yi 2026-11-06 定）。期限内，连接程序在 `--allow-*` 允许的范围里执行，不再每次询问；到期或用户在网页上撤销，连接立即断开，工具随之消失。
- **先只支持 macOS，命令隔离用微软 MXC**（Yi 2026-11-06 定）。Windows 下一步做，MXC 在 Windows 上也能用，到时换后端、用同一套规则。

例子：

| 时间 | 发生了什么 | 结果 |
|---|---|---|
| 10:00 | Yi 在插件页点“连接这台电脑”，选 1 小时 | 页面给出一行命令和令牌 |
| 10:01 | Yi 在终端运行 `tantalum-connect --allow ~/work/shop --allow-write` | 插件页显示“已连接：Yi 的 MacBook，~/work/shop，可改文件，11:01 到期” |
| 10:02 | 在会话里问“看看 OrderService 为什么报空指针，修一下” | 模型调 `read_file`、`grep`、`git_diff`，读的是 Mac 上的文件；`edit_file` 直接改；对话上方显示“工具变化：新增 read_file、grep……” |
| 10:30 | 模型想跑 `mvn test` | 没有 `--allow-shell`，模型手上没有 `bash`，改为告诉 Yi 自己跑 |
| 11:01 | 令牌到期 | 连接断开，连接程序退出并说明原因；下一轮对话上方显示“这些工具不能用了”，模型也被告知 |

### 调研

参照 pi-mono `main`（7f47a2e，2026-11-05）、DSH `main`（fcc0ad4621，2026-11-04）、DeepSeek 文章 *Exploring Super-agent Design*（2026-10-31，下称“DS 文章”），以及三个开源实现：OpenClaw（f3eb90dd）、OpenAI Codex（09358089）、Anthropic `sandbox-runtime`（98a4016d，下称 srt）。

- **DS 文章** 推荐的正是这个结构：云上跑 agent，“每个终端只需要一个轻量的守护程序，它不保存会话状态，只执行命令、返回结果”（§5.2）。它也承认问题没解决：这个设计“默认云端和终端互相信任；服务器被入侵或被提示词注入攻破，所有连着的设备都可能被接管”（§8.4）。
- **pi** 每个工具的执行都能整体换掉（`BashOperations`、`ReadOperations` 等，`CA/src/core/tools/bash.ts:70-93`），例子 `examples/extensions/ssh.ts` 用它把工具搬到 SSH 主机上。pi 自己没有沙箱、没有审批，审批只是例子扩展（`examples/extensions/permission-gate.ts:11-33`）。
- **DSH** 有沙箱（`sandbox/sandbox-local`：macOS 用 Seatbelt，Windows 用受限令牌，Linux 用 bwrap），不能保证隔离就报 `SANDBOX_UNAVAILABLE`，不会不隔离直接跑（`sandbox/sandbox/src/index.ts:132-172`）。它的远程执行是服务器主动 SSH 进去，策略由服务器发、远端照做（`ssh/ssh/src/helper.ts:89-99`），没有“电脑主动连上来”的做法，也没有每台设备自己的允许名单。
- **OpenClaw** 的 node 主动用 WebSocket 连到网关，用一次性配对码配对（`docs/nodes/node-host.md:59-75`）。但它明说配对的设备是服务器能力的延伸，目录限制“不是对被攻破网关的防线”（`node-host.md:354-357`）。
- **Codex** 的远程执行器也主动连出去，但沙箱策略由调用方在每个请求里给（`codex-rs/exec-server/README.md:483`）。它的 macOS 沙箱用 `/usr/bin/sandbox-exec` 和以 `(deny default)` 开头的规则文件（`sandboxing/src/seatbelt.rs:57-61`）。Windows 推荐用微软 MXC，不需要管理员，只支持较新的 Windows 11（`windows-sandbox-rs/src/mxc-sandbox/README.md:1-50`）。
- **srt** 在 macOS 用 Seatbelt 限制读、写和网络；Windows 版还是 alpha，需要管理员安装（`README.md:740-809`）。

结论：没有一家做到“服务器被攻破，不等于控制了所有连上的电脑”。我们的做法是把**允许什么**放在电脑这边决定，服务器只能在这个范围里提要求；再用有期限的令牌限定时间。

路径写法：pi 的 `CA` 指 `packages/coding-agent`；DSH 路径相对于 `packages/`；其余路径相对于各自仓库根目录；都没有对应做法的标“自定”。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| 连接程序（Java）：主动连服务器、`--allow` 目录、`--allow-write`、`--allow-shell`，断线重连 | 代码索引 `code-index`（`symbol_search`、`outline`、`read_symbol`）：要在电脑上建索引，选型（tree-sitter、ctags、LSP）还要单独调研。下一个 spec，挂在同一个总开关下 |
| 网页上生成有期限的令牌、撤销、看连接状态 | 每次执行前询问（Yi 2026-11-06 定：先用有期限的令牌代替） |
| 现有文件工具改到电脑上执行；服务器上不再有文件工具 | 一个用户同时连多台电脑（L10） |
| Git 查询工具（只读） | 后台长时间运行的命令（BACKLOG“异步工具”，单独 spec） |
| 命令行 `bash`，在 MXC 沙箱里执行 | Windows 上的连接程序和 `powershell`（Yi 2026-11-06 定先只做 macOS；下一个 spec）、Linux 上的连接程序。消息格式和规则不绑定 macOS |
| 插件页“本机工作区”总开关和子开关 | 桌面截图、操作鼠标（BACKLOG 截图一项） |
| 电脑连上、断开时，工具出现、消失，告诉模型和用户 | 自动更新连接程序、应用商店上架 |
| 附件照常能读（改由服务器上的 `read_attachment`） | 服务器装在 Windows 上（单独 spec） |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 |
|---|---|---|
| L1 | 谁连谁 | 连接程序主动连服务器（WebSocket，走服务器现有的端口和 HTTPS），电脑上不开端口。服务器只在 127.0.0.1 上监听时，只有同一台电脑能连；要从别的电脑连，管理员照现在的做法在前面放 HTTPS 反向代理。照 OpenClaw（`docs/gateway/protocol/transport.md:37-38`）和 Codex exec-server（`README.md:62-130`）；DSH 是服务器主动 SSH 进去（`ssh/ssh/src/index.ts:299-302`），不用 |
| L2 | 怎么认证 | 用户在插件页点“连接这台电脑”，选有效期（半小时、1 小时、4 小时、1 天），页面显示一个令牌和一行可复制的命令。令牌只显示这一次；服务器只存它的哈希，绑定这个用户和到期时间。连接程序从环境变量 `TANTALUM_TOKEN` 或终端输入读令牌，不放在命令行参数里（参数会被同一台电脑上的其他程序看到），不写进文件。有效期内断线可以用同一个令牌重连。有效期内用户可以随时在插件页撤销。Yi 2026-11-06 定有效期；一次性配对码的样子照 OpenClaw（`docs/gateway/pairing.md:80-92`），OpenClaw 另有每台设备的密钥对，我们有了期限之后先不做 |
| L3 | 到期和撤销 | 到期或撤销时，服务器立即断开这条连接，正在执行的调用按 L8 处理；连接程序退出，并在终端写明“令牌已到期”或“已在网页上撤销”。同一个令牌不能再用。自定 |
| L4 | 电脑这边决定什么 | 允许的目录（`--allow`，可以写多个，第一个是主目录）、能不能改文件（`--allow-write`）、能不能跑命令（`--allow-shell`）只由连接程序的启动参数决定。连接时连接程序把这些告诉服务器，服务器据此决定给模型哪些工具；但每个调用到了电脑上，连接程序**自己再检查一遍**，越界就拒绝，不信任服务器的判断。服务器没有任何办法改这几项。自定：OpenClaw 允许网关远程修改设备的执行审批（`docs/tools/exec-approvals.md:709-720`），Codex 由调用方给沙箱策略，我们都不这样 |
| L5 | 哪些工具，叫什么 | 沿用现有名字和参数，旧会话里的调用对得上：`read_file`、`list_dir`、`find_files`、`grep`（读文件）；`write_file`、`edit_file`（改文件）。新增 Git 查询：`git_log`、`git_show`、`git_diff`、`git_blame`、`git_branches`，只读，主目录是 Git 仓库时才有。命令行叫 `bash`（以后 Windows 上叫 `powershell`）。pi 叫 `bash` 和 `powershell`（`CA/src/utils/shell.ts:86-169`），DSH 叫 `bash` 和 `pwsh`（`shell/tool-pwsh/src/index.ts:385-405`）；两家都没有 Git 工具（DSH 只在内部用 git，`deliverables/workspace-changes/src/index.ts:75-80`），Git 工具是 Yi 的插件页定的 |
| L6 | 服务器上原来的文件工具 | 去掉。服务器上不再有任何读写文件、跑命令的工具（Yi 2026-11-06：“所有的读写文件还有git操作还有bash或者powershell都是在enable反向sandbox之后才能工作”）。旧会话照文件还原不受影响；接着问时，电脑没连就显示这些工具不能用。服务器上每个用户的目录还在，记忆等数据照常存在那里。`AGENTS.md` 改为读电脑上主目录里的那份；电脑没连就没有 |
| L7 | 附件 | 现在 `read_file` 也能读本人的附件（`/attachments/sha256-…`，`tool/ReadFileTool.java:40-41`）。附件存在服务器上，和电脑无关，改由服务器上的新工具 `read_attachment` 读，归“个人记忆、待办”这一类，不受总开关影响。自定 |
| L8 | 调用中途断线 | 服务器把这次调用记成错误“电脑断开了连接，这次操作是否已经完成不确定”，告诉模型，不自动重发（改文件、跑命令重发可能做两次）。电脑那边：连接断开就结束这条连接上还在跑的命令（连同它启动的子进程）。电脑没连上时模型调这些工具，因为工具已经不在了，按现有规则处理（one-line-journal：模型调了不存在的工具，返回错误）。照 DSH（“SSH connection lost; remote operation outcome and cleanup are unknown”，`ssh/ssh/src/protocol.ts:122-125`）和 Codex（连接关闭就结束它的进程，`exec-server/README.md:213`） |
| L9 | 工具出现和消失 | 本机工作区的工具定义是固定的（名字、说明、参数不随电脑变），会话开始时就在工具全集里；电脑没连、总开关或子开关关着、`--allow-*` 没给，就算“没开”。所以连上、断开都是“开关变了”，走现有的工具变化提示：下一轮请求告诉模型新增或不能用了哪些工具，对话上方显示一行（core-tools C14、one-line-journal J7）。允许的目录、能否改写这些会变的信息不写进工具说明，而是作为 system 里会变的一段（“本机工作区”），变了照现有规则追加一条更新消息。这样工具定义稳定，缓存前缀不失效。pi 的做法类似：工具中途增减时插一条 system 消息（`agent/src/agent-loop.ts:332-372`） |
| L10 | 一个用户连几台电脑 | 一次一台。新的连接（用新的或同一个令牌）顶替旧的：旧连接程序收到“已被另一台电脑的连接替换”后退出。连多台电脑、让模型选在哪台执行，以后再做（DS 文章 §5.2 的“切换执行设备”）。自定 |
| L11 | 多个会话同时用 | 同一个用户的所有会话共用这台电脑。每个调用带会话 ID，连接程序的终端日志里能看出是哪个会话。同一时间最多执行 4 个调用，多的排队（现有 `Tool.serverConcurrency`）。自定 |
| L12 | 文件工具的边界 | 连接程序检查路径：解析符号链接之后必须在某个允许的目录里，否则和“文件不存在”用同一句话拒绝，免得借不同的报错探测目录外的文件（现有 `Workspace.java:14-15`，搬到电脑上）。路径可以写绝对路径，也可以写相对主目录的路径（以后 Windows 上 `/` 和 `\` 都认）。先读后写的规则照旧（core-tools，DSH `fs/fs-observation-policy/src/index.ts:65-97`） |
| L13 | 命令行的隔离 | 命令在系统沙箱里执行：能读系统目录和允许的目录，读不到用户目录里的其他东西（比如 `~/.ssh`、浏览器资料）；`--allow-write` 时只能写允许的目录和临时目录，没有就什么都不能写；网络默认放开（下载依赖、跑测试要用），`--no-network` 关掉；MXC 在 macOS 上只能整体开关网络，不能按网址放行（`docs/backends/seatbelt/seatbelt-backend.md:68-83`）。用微软 MXC（`microsoft/mxc` v1.0.1，MIT，Yi 2026-11-06 定）：连接程序调用它的 macOS 执行程序 `mxc-exec-mac`，交给它一段 JSON 规则（只读目录、读写目录、禁止目录、网络），它在 macOS 自带的 Seatbelt 里启动命令，规则从“全部拒绝”开始，只放开系统目录（`seatbelt-backend.md:92-94、167-172`）。Codex 在 Windows 上也用 MXC（`windows-sandbox-rs/src/mxc-sandbox/README.md:1-50`），以后做 Windows 时换成 MXC 的 Windows 后端，规则格式不变。**保证不了隔离就不让跑**：找不到 MXC、MXC 报错不能启动沙箱时，`bash` 不出现，连接程序启动时说明原因，读文件和改文件照常能用（Yi 2026-11-06 定；照 DSH `SANDBOX_UNAVAILABLE`，`sandbox/sandbox/src/index.ts:132-152`）。DSH 的 Seatbelt 规则允许读任何地方（`sandbox/sandbox-local/src/profiles.ts:53-60`），我们更严，因为要防的是服务器被攻破 |
| L14 | 命令行的参数和限制 | 参数：`command`（必填）、`timeout`（秒，默认 120，最多 600）、`workdir`（默认主目录，必须在允许的目录里）。输出保留最后 2000 行或 50 KB，先到哪个算哪个，截掉时写明。退出码不是 0 算错误结果并写明退出码。超时或取消时结束整个进程树。环境变量：去掉名字里含 `KEY`、`TOKEN`、`SECRET`、`PASSWORD` 的变量和 `TANTALUM_TOKEN`，免得被模型读出来。超时照 DSH（120 s、600 s，`shell/bash-local/src/index.ts:102-106`），截断照 pi（`CA/src/core/tools/bash.ts:283`、`truncate.ts:11-12`），去掉敏感变量照 DS 原型（`docs/006-deployment.md:218`），结束进程树照 pi（`CA/src/utils/shell.ts:220-264`） |
| L15 | 电脑这边留记录 | 连接程序在终端里每个调用打一行：时间、会话、工具、路径或命令、结果（成功、拒绝、错误）。用户随时能看到服务器让电脑做了什么。不写令牌、不写文件内容。自定 |
| L16 | 连接程序是什么 | Java 程序（Yi 2026-11-06 定），和服务器共用工具代码和消息格式。给 macOS（Apple 芯片）打一个自带 Java 运行环境和 MXC 执行程序的安装包（`jpackage`），用户不用另装 Java。签名、公证、上架不在本 spec（只在说明里写清楚首次打开时系统会怎么提示） |
| L17 | 插件页 | “本机工作区”一行：总开关，下面子开关读文件、Git 查询、改文件、命令行；显示连接状态（电脑名、系统、允许的目录、能否改文件和跑命令、什么时候到期），“连接这台电脑”和“撤销”按钮。子开关打开但电脑没给 `--allow-write`/`--allow-shell` 时，写明“连接程序没有允许”。照 Yi 2026-11-06 的截图；开关按用户分别记（core-tools C16） |
| L18 | 安全底线 | 令牌、文件内容不进服务器日志；令牌不进会话文件和轨迹。工具的输入和输出照现在一样进会话文件（回放和轨迹要用）。服务器给连接程序的每个请求都要带这个用户的身份，连接程序只接受发给自己这个用户的请求。DS 文章 §8.4 提到的“用凭据但不让模型看到”不在本 spec，只做 L14 的去掉敏感变量 |

## Requirements

### Requirement 1：连接和令牌

**User Story:** 作为用户，我想在网页上生成一个有期限的令牌，在自己电脑上运行连接程序连上服务器，这样 agent 就能动我电脑上我允许的目录，而且过了期限自动失效。

#### Acceptance Criteria

1. WHEN 用户在插件页点“连接这台电脑”并选定有效期 THEN 页面 SHALL 只显示一次令牌和一行可复制的启动命令，服务器 SHALL 只保存令牌的哈希、所属用户和到期时间（L2）。
2. WHEN 连接程序用有效令牌连接 THEN 服务器 SHALL 把这条连接绑定到令牌所属的用户，插件页 SHALL 在 2 秒内显示已连接、电脑名、系统、允许的目录、能否改文件和跑命令、到期时间。
3. WHEN 令牌无效、已过期、已撤销 THEN 服务器 SHALL 拒绝连接，连接程序 SHALL 写明原因并退出，退出码不为 0。
4. WHEN 令牌到期或被撤销 THEN 服务器 SHALL 在 5 秒内断开连接，连接程序 SHALL 写明原因并退出（L3）。
5. WHEN 网络断开而令牌仍有效 THEN 连接程序 SHALL 自动重连，等待时间逐次加长、最多 30 秒一次；重连成功后工具恢复。
6. WHEN 同一用户有第二条连接 THEN 服务器 SHALL 断开旧连接并告诉它被替换（L10）。
7. 令牌 SHALL NOT 出现在连接程序的命令行参数、任何文件、服务器日志、会话文件、轨迹里（L2、L18）。

### Requirement 2：电脑这边说了算

**User Story:** 作为用户，我想只靠电脑上的启动参数决定 agent 能动什么，这样就算服务器出了问题，它也越不过我给的范围。

#### Acceptance Criteria

1. 连接程序 SHALL 只接受 `--allow` 列出的已存在目录；一个都没给时 SHALL 拒绝启动。
2. WHEN 某个调用的路径（解析符号链接之后）不在允许的目录里 THEN 连接程序 SHALL 拒绝，报错和“文件不存在”相同（L12）。
3. WHEN 没有 `--allow-write` 而收到写文件请求，或没有 `--allow-shell` 而收到命令请求 THEN 连接程序 SHALL 拒绝，即使服务器认为可以（L4）。
4. 服务器 SHALL NOT 有任何修改允许目录、`--allow-write`、`--allow-shell` 的手段；协议里 SHALL NOT 有这样的消息。
5. 连接程序 SHALL 在终端为每个调用打一行记录（L15），不含令牌和文件内容。

### Requirement 3：文件和 Git 工具搬到电脑上

**User Story:** 作为用户，我想让 agent 读、搜、改我电脑上的代码，查 Git 历史，用法和现在一样。

#### Acceptance Criteria

1. `read_file`、`list_dir`、`find_files`、`grep`、`write_file`、`edit_file` SHALL 在电脑上执行，参数、截断、续读、先读后写、CRLF 和 BOM 的处理 SHALL 和 core-tools 的规定相同（L5、L12）。
2. WHEN 主目录是 Git 仓库 THEN SHALL 提供 `git_log`、`git_show`、`git_diff`、`git_blame`、`git_branches`；这些工具 SHALL 只读，SHALL NOT 改变仓库，输出 SHALL 有大小上限并写明截断。
3. 服务器 SHALL NOT 再提供任何读写文件或跑命令的工具（L6）。
4. 读附件 SHALL 用服务器上的 `read_attachment`，不依赖电脑是否连着（L7）。
5. WHEN 电脑连着 THEN system 里 SHALL 有“本机工作区”一段，写明系统、主目录、其他允许的目录、能否改文件和跑命令；主目录有 `AGENTS.md` 时 SHALL 照现有规则读进 system（L6、L9）。

### Requirement 4：命令行

**User Story:** 作为用户，我想在加了 `--allow-shell` 之后让 agent 在我电脑上跑命令（编译、测试），但命令只能碰我允许的目录。

#### Acceptance Criteria

1. WHEN 连接程序有 `--allow-shell` 且系统沙箱可用 THEN SHALL 提供 `bash`，参数和限制按 L14。
2. 命令 SHALL 在系统沙箱里执行：读不到允许目录以外的用户文件；没有 `--allow-write` 时什么都写不了，有时只能写允许的目录和临时目录；`--no-network` 时不能联网（L13）。
3. WHEN 系统沙箱不可用 THEN 命令行工具 SHALL NOT 出现，连接程序 SHALL 在启动时写明原因，插件页 SHALL 显示“这台电脑不支持隔离执行命令”；其他工具照常（L13）。
4. WHEN 命令超时、被取消、连接断开 THEN SHALL 结束整个进程树（L8、L14）。
5. 命令的环境变量 SHALL NOT 含名字里有 `KEY`、`TOKEN`、`SECRET`、`PASSWORD` 的变量（L14）。

### Requirement 5：工具出现、消失

**User Story:** 作为用户，我想在电脑连上、断开、开关变化时，模型和我都知道现在能用哪些工具。

#### Acceptance Criteria

1. 本机工作区的工具定义 SHALL 固定，SHALL 在会话开始时就在工具全集里；没连上或没开 SHALL 算“没开”（L9）。
2. WHEN 电脑连上、断开、到期、开关或 `--allow-*` 变化 THEN 下一次请求 SHALL 告诉模型新增和不能用的工具，对话里 SHALL 在那一轮上方显示工具变化（core-tools C14、one-line-journal 需求 5）。
3. WHEN 允许的目录、能否改写变化 THEN SHALL 照会变的 system 段的规则追加一条更新消息，SHALL NOT 改工具定义（L9）。
4. WHEN 调用执行到一半连接断开 THEN 这次调用 SHALL 记为错误“电脑断开了连接，这次操作是否已经完成不确定”，SHALL NOT 自动重发（L8）。
5. 旧会话（服务器上执行过 `read_file` 等）SHALL 照文件原样还原。

### Requirement 6：插件页

**User Story:** 作为用户，我想在插件页一眼看到电脑有没有连上、给了什么权限、什么时候到期，并能开关、撤销。

#### Acceptance Criteria

1. 插件页 SHALL 有“本机工作区”总开关和读文件、Git 查询、改文件、命令行四个子开关，按用户分别记（L17）。
2. 总开关关着时，本机工作区的工具 SHALL 一个也没有；待办、知识库、个人记忆、`read_attachment` SHALL 不受影响。
3. WHEN 子开关开着但连接程序没给对应的 `--allow-*` THEN SHALL 写明“连接程序没有允许”。
4. 插件页 SHALL 显示连接状态和到期时间，并能撤销；撤销后 SHALL 按需求 1.4 断开。

### Requirement 7：安装

**User Story:** 作为用户，我想下载一个安装包就能用，不用另装 Java。

#### Acceptance Criteria

1. SHALL 为 macOS（Apple 芯片）提供一个自带 Java 运行环境和 MXC 的安装包（L16）。
2. README SHALL 写明下载、首次打开时系统的提示、启动命令、各参数的含义和风险。
3. `tantalum-connect --help` SHALL 列出所有参数。

### Requirement 8：验证

**User Story:** 作为开发者，我想用真实程序证明上面这些在 macOS 上成立。

#### Acceptance Criteria

1. 测试 SHALL 覆盖：越界路径、符号链接逃逸、没有 `--allow-write`/`--allow-shell` 时服务器硬发请求被拒、令牌过期和撤销、中途断线、重连、被顶替。
2. SHALL 有用真实 MXC 的测试：命令读不到 `~/.ssh` 这类目录、写不出允许的目录、`--no-network` 时连不上网。
3. 浏览器端到端测试 SHALL 走一遍：生成令牌、启动连接程序、对话里读和改电脑上的文件、断开后显示工具变化。
4. 在 8091 上 SHALL 用 Yi 的 Mac 演示一遍真实的修改代码、跑测试。
## Non-Functional Requirements

### Code Architecture and Modularity
- 新模块放连接程序；工具代码和消息格式由服务器和连接程序共用，不复制两份。
- 服务器端：连接管理、令牌、把工具调用转给电脑，各自一个类，包名清楚。

### Performance
- 一次 `read_file` 在同一台电脑上（服务器和连接程序都在本机）往返增加不超过 20 ms（P95）。
- 连接程序空闲时内存不超过 150 MB。

### Security
- 允许的范围只由电脑决定，每个调用在电脑上再查一遍（L4）。
- 令牌只存哈希，有期限，可撤销，不落盘、不进日志（L2、L18）。
- 命令行保证不了隔离就不提供（L13）。

### Reliability
- 断线不重发，结果不确定时如实告诉模型（L8）。
- 服务器重启后，连接程序用仍有效的令牌自动重连。

### Usability
- 插件页、连接程序的提示用简单中文；连接程序的报错写明原因和怎么办。
