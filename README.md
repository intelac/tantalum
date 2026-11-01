# Tantalum

生产级、通用的 Agent 运行时底座：事件先落盘、内核是纯函数、可以从 journal 精确重放，插件不用改内核。

```
输入环 → 写 journal → Kernel.step → 输出环 → Handler → 结果事件回到输入环
```

代码分三层，依赖方向 `value ← kernel ← shell`，由 ArchUnit 守护：

| 模块 | 内容 |
|---|---|
| `tantalum-value` | 不可变类型：事件、命令、状态 |
| `tantalum-kernel` | 纯逻辑：转移表、钩子、回放，不做 I/O |
| `tantalum-shell` | 一切 I/O：环、journal、Handler、模型客户端、Web |
| `tantalum-examples` | 示例插件，不打进可执行 jar |
| `console/` | 参考控制台 |

设计文档在 `.spec-workflow/`。

## 运行

需要 JDK 25、Maven 3.9、Node 25 和 pnpm。

```sh
(cd console && pnpm install && pnpm build)   # 先构建控制台，它会被打进 jar
mvn clean package -DskipTests
```

默认模型是 Claude，prompt 会发送到 Anthropic，其中可能含客户代码：

```sh
export ANTHROPIC_API_KEY=...
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar --tantalum.workspace=/path/to/code
```

不能外发代码时，用本地的 OpenAI 兼容模型（默认指向 `127.0.0.1:8090` 的 strata）：

```sh
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar \
  --tantalum.provider=openai --tantalum.workspace=/path/to/code
```

然后打开 <http://127.0.0.1:8080>。配置项和默认值见 `tantalum-shell/src/main/resources/application.properties`。

服务默认只绑定 `127.0.0.1`。

## 登录和用户

第一次启动时数据目录里没有用户，要用环境变量创建第一个账号（没有内置默认密码）：

```sh
export TANTALUM_ADMIN_USER=yi TANTALUM_ADMIN_PASSWORD='...'
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar ...
```

之后用命令行管理用户，密码从标准输入读取；`TANTALUM_DATA_DIR` 指向数据目录（默认 `~/.local/share/tantalum`）：

```sh
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar users add alice
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar users reset alice
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar users disable alice
```

口令用 Argon2id 哈希存储。登录后身份放在 `HttpOnly`、`SameSite=Strict` 的会话 Cookie 里，会话在内存中，重启后要重新登录；写操作还会校验 `Origin`。每个用户的工作目录隔离在 `<workspace>/<用户名>`（`tantalum.workspace-mode=per-user`），会话、运行、回放和 SSE 都只能看到自己的。登录连续失败会被限速。

## 十圈场景

设计图上的十圈有两个测试：

```sh
mvn -pl tantalum-kernel -am test -Dtest=TheLoopTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl tantalum-examples -am verify -Dtest=TenLoopIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

`TheLoopTest` 不启线程，逐圈断言状态和命令，回边被改坏时会指出是哪一圈。`TenLoopIntegrationTest` 经过真实的环、journal、Web 和 SSE，再回放比对每次的 prompt。

## 用 journal 查看一个运行

journal 是数据目录（默认 `~/.local/share/tantalum`）下的 `journal.db`。

**控制台：** 选中运行，再选一次模型调用，就能看到回放得出的完整 prompt 和回复。

**接口：** 先登录拿到 Cookie，再带着它调用。

```sh
curl -c cookie -H 'content-type: application/json' \
  -d '{"username":"yi","password":"..."}' http://127.0.0.1:8080/api/auth/login
curl -b cookie http://127.0.0.1:8080/api/sessions/<session>/runs
curl -b cookie http://127.0.0.1:8080/api/runs/<run>/turns
curl -b cookie http://127.0.0.1:8080/api/runs/<run>/turns/1
curl -b cookie http://127.0.0.1:8080/api/runs/<run>/trace      # 事件、处理方和时间段
```

**直接看事件：**

```sh
sqlite3 ~/.local/share/tantalum/journal.db "SELECT seq, type, run_id, at FROM events ORDER BY seq"
```

journal 含完整的 prompt 和工具结果，文件权限只限所有者。

## 记忆

模型可以替每个用户记住跨会话仍然有用的事实：偏好、纠正过的做法、项目事实、外部链接。四层模型里实现了第二层（会话摘要，见下面的“上下文压缩”）、第三层（个人长期记忆）和第四层（共享知识，只读）。

- **存在哪**：每人模式下在 `<workspace>/<用户名>/memory/`，索引 `MEMORY.md` 每条一行，条目 `<name>.md` 带 frontmatter（名称、描述、类型、更新时间）。共用工作目录模式下放到 `<data-dir>/memory/<用户名>/`，这样别人的 `grep` 搜不到。索引以条目文件为准，每次写删后重建。
- **怎么进 prompt**：运行开始时把记忆指引、共享知识、`AGENTS.md` 和该用户的索引依次拼进 system，写进 `ContextAssembled` 事件；回放不读文件。索引在 system 末尾、钩子追加内容之前，prompt caching 的断点整个运行里稳定。条目正文不常驻，模型用 `memory_read` 按需读。新记忆从下一个运行开始生效：会话的 system 和工具在第一个运行时固定（所有模型相同），之后可变内容有变化时追加一条用户消息说明，新工具要到新会话才可见。
- **工具**：`memory_write(name, type, description, content)` 新建或同名覆盖，`memory_read(name)`，`memory_delete(name)`。名称 `[a-z0-9-]{1,64}`，类型 `user`、`feedback`、`project`、`reference`。看起来像密钥、令牌、口令的内容会被拒绝写入。
- **上限**：`tantalum.memory.max-entries`（200）、`max-index-bytes`（16 KB）、`max-entry-bytes`（8 KB）。超限时写入被拒并提示模型先删或合并；不做自动整理。
- **共享知识**：`tantalum.knowledge.dir` 指向管理员维护的目录，其中的 `*.md` 按文件名顺序进所有用户的 system（总字节上限 `tantalum.knowledge.max-bytes`，默认 64 KB）。模型改不了它。
- **界面**：设置 › 记忆：列表、正文、删除（需确认）、手工新增。接口 `GET/POST /api/memory`、`GET/DELETE /api/memory/{name}` 只操作当前登录用户的记忆。

## 上下文压缩

长会话不会因为上下文满而失败。模型调用前，内核按估算判断会话有没有越过阈值；越过了就先发一次禁用工具、限长的摘要调用，用摘要替换历史开头，再发原来的调用。所有模型走同一条路。

- **规则**：阈值 = 窗口 − max(`reserve-tokens`, 该供应商的 `max-tokens`)；保留最近约 `keep-recent-tokens`（不超过阈值的四分之一）的消息，其余交给模型总结；一次调用前最多压缩一次，压缩失败后本运行不再自动压缩；模型报上下文超长时只留最新一段压缩并重试一次。完整规则在 `.spec-workflow/specs/context-compaction/requirements.md`。
- **历史只追加**：journal 和对话页始终是全部原文，压缩只换模型看到的视图；对话页在压缩处有分隔线，点开能看摘要。保留段里的推理块会去掉，过大的工具结果首尾各留一半。
- **配置**：`tantalum.<provider>.context-window`（为 0 时不压缩）；`tantalum.compaction.reserve-tokens`（16384）、`keep-recent-tokens`（20000）、`max-kept-tool-result-bytes`（32 KB）、`max-summarized-tool-result-bytes`（4 KB）、`summary-max-tokens`（8192）。设置在会话第一次运行时冻结，改配置只影响新会话。
- **手动**：状态栏的压缩按钮，或 `POST /api/sessions/{id}/compact`（可带 `instructions`）；会话运行中或没有可压缩的内容时返回 409。
- **回复被截断**：回复因输出上限结束时运行照常完成，对话页提示“回复被截断”；不自动续写。

实测（细节在 `.spec-workflow/steering/tech.md`）：Claude Sonnet 5.5 严格校验和本地 qwen 用同一个十篇笔记的用例，压缩后暗号和规则全部保留；同一任务开压缩后总 prompt token 从 15.3 万降到 10.3 万。

## MCP

可以连接远程 MCP 服务器，把它们的工具交给模型。只由管理员配置：

```properties
tantalum.mcp.servers.jira.url=https://mcp.example.com/mcp
tantalum.mcp.servers.jira.auth=service         # none 或 service
tantalum.mcp.servers.jira.token-env=JIRA_TOKEN # 令牌只从这个环境变量读
tantalum.mcp.servers.jira.allow=create_issue   # 除只读工具外额外放行
tantalum.mcp.servers.jira.deny=delete_issue    # 优先于一切
```

可选项：`concurrency`（默认 4）、`timeout`（默认且不能超过 `tantalum.tool-timeout`）、`max-result-bytes`（默认 64 KB）、`max-tools`（默认 64）。工具名是 `mcp__<服务器>__<工具>`，调用经过和内置工具相同的钩子、超时、journal 和轨迹。

默认只放行声明了 `readOnlyHint` 的工具。这个声明是服务器自己写的，所以只连可信的服务器。

第一版的限制，部署前要知道：

- **共享身份**：所有用户用同一个凭据，外部系统分不清是谁操作的，只有 journal 能回答。没有每用户令牌。
- **提示注入**：工具结果可能诱导模型。system 里有提醒，但这不是保证；未放行的工具才真正调不到。放行写工具后，没有人工确认这一步。
- **取消和超时**：服务器不一定知道调用被取消，写工具的结果可能未知，系统不自动重试。
- **不并行**：同一轮的 MCP 工具逐个执行，这样回放不依赖服务器当时的状态。
- **新工具**：已有会话看不到，要开新会话。
- **不支持**：stdio 本地服务器、OAuth、资源、提示词模板、采样。状态只能看日志，没有界面。

## 写插件

只需要看 `tantalum.shell.plugin`、`tantalum.kernel.hook` 和 `tantalum.value`，示例在 `tantalum-examples`。

| 想做什么 | 实现 |
|---|---|
| 在固定位置判断或拦截 | `KernelHook`，纯函数，返回 `Allow`、`Stop` 或 `NeedWork` |
| 判断时需要 I/O | `KernelHook` 返回 `NeedWork`，再实现 `WorkType` |
| 增加工具 | `Tool`，声明服务端和并发上限；未声明时上限为 1 |
| 修改工具输出 | `AfterToolHook` |

注册成 Spring bean 即可。

线程模型：`KernelHook` 在内核的单线程里运行，所有用户的状态只在这一个线程上相遇，钩子不需要考虑并发，但必须是纯函数（回放接口会让内核重跑 journal，钩子会被再次调用）。`Tool`、`AfterToolHook`、`WorkType` 每个是一个单例，会被多个虚拟线程同时调用（不同用户、同一运行里的并行工具），所以它们不能保存可变状态；每次调用的身份和工作目录都在 `ToolContext` 里。

超时与失败：`Tool.run` 和 `WorkType.run` 都受 `tantalum.tool-timeout` 约束，到时线程被中断、结果按失败交回；撤销运行也会中断它们，所以阻塞操作要能响应中断。钩子返回 null 按 `Stop` 处理。按名单放行的策略钩子（如示例 `ReadOnlyHook`）要把 `memory_write`、`memory_read`、`memory_delete` 加进名单，否则记忆工具会被拦下；记忆目录是用户自己的，不算对工作目录的写操作。做“检查”的钩子在 `onWorkResult`/`beforeRunEnd` 里看到 `work.failed()` 时不要放行（示例 `CitationCheckHook` 会 `Stop`），否则检查没做成就等于通过。从 `onFailure` 申请的工作完成后再调模型会占用重试次数，失败持续时最多重试 3 次。

## 测试

```sh
mvn clean verify          # 不访问外网，不花钱
(cd console && pnpm test)
(cd console && pnpm e2e)  # Playwright 端到端：自动起脚本化模型的后端（8097）和 vite（5174）
```

端到端的后端是 `tantalum-examples` 测试目录里的 `E2eServer`（Maven profile `e2e`，模型按关键词回放剧本，不访问外网），账号 `e2e` / `e2e-password`。

真实模型的冒烟测试要显式打开：

```sh
TANTALUM_SMOKE_STRATA=1 mvn -pl tantalum-examples -am verify -Dtest=StrataSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
TANTALUM_SMOKE_CLAUDE=1 mvn -pl tantalum-examples -am verify -Dtest=ClaudeSmokeTest -Dsurefire.failIfNoSpecifiedTests=false   # 需要 key，会产生费用
TANTALUM_SMOKE_CLAUDE=1 mvn -pl tantalum-examples -am verify -Dtest='ClaudeScenarioTest,ClaudeCrashRecoveryTest' -Dsurefire.failIfNoSpecifiedTests=false
```

`ClaudeScenarioTest` 和 `ClaudeCrashRecoveryTest` 用真实的 Claude 走完整的环：八步顺序工具链、thinking 签名跨多轮回传、每一个钩子位置、流式中取消后继续、三个用户同时调用、模型调用进行中杀进程再恢复。一轮约 0.1 美元，结论记在 `.spec-workflow/steering/tech.md`。

第三方依赖和界面参考（DeepSeek Harness）的许可证见 `THIRD_PARTY_NOTICES.md`。

## 能力对照：和 pi、DeepSeek Harness 比

“做完了”一律对着这张表说。每个 spec 完成后更新；`core-skeleton` 和 `console-ui` 两个 spec 的任务已全部完成，但相对目标还缺下面标“没有”的部分。

| 能力 | pi | DeepSeek Harness | Tantalum | 计划 |
|---|---|---|---|---|
| agent 循环、流式、工具调用、并行工具 | 有 | 有 | 有 | |
| 会话持久化、崩溃后恢复 | 有（文件） | 有 | 有，journal 可逐字节回放；重启后未结束的运行标“待恢复”，只能撤销，不自动续跑 | 自动续跑待定 |
| 钩子 / 扩展点 | 有 | 有 | 有，纯函数钩子 + `NeedWork` | |
| 多用户、登录、每用户目录、入口限额 | 无 | 无 | 有 | |
| 轨迹回放、每次模型调用的完整 prompt | 部分 | 有 | 有 | |
| prompt caching（Claude） | 有 | 经 pi | 有 | |
| 上下文压缩 | 有 | 有 | 有：自动、超长恢复、手动，所有模型同一套规则，`context-compaction` spec | 自定义摘要钩子待定 |
| 启动和内存不随历史增长 | 有（只开一个会话） | 有（会话按需载入 + 每会话投影检查点） | **没有** | `ops-hardening` |
| 长期记忆 | 无 | 没有自带，只有通用 MCP 桥和 3 个可选示例配置 | 有：每用户记忆文件 + 三个工具 + 共享知识层 + 设置面板，`memory` spec | 自动整理未做（决定 M5） |
| MCP 接入 | 有（`builtin:mcp`） | 有 | 有：远程 HTTP 服务器、放行名单、共享凭据；没有 stdio、OAuth、每用户令牌 | 每用户令牌待定 |
| 操作系统级沙箱 | 无 | 无 | **没有**，隔离靠路径校验，工具都在同一进程 | 沙箱 spec |
| 子 agent | 有 | 有 | **没有** | 子 agent spec |
| 技能、计划、定时任务 | 部分 | 有 | **没有** | 未规划 |
| 用户管理界面 | 不适用 | 不适用 | **没有**，只有命令行；会话在内存里，重启后要重新登录 | 未规划 |
| 终端、文件树、浏览器面板 | 无 | 有 | **没有**，`console-ui` 时明确排除 | 未规划 |

顺序（Yi 决定）：`memory`、`mcp-client`、`context-compaction` 已完成；其余顺序待定。
