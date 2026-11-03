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

`tantalum-shell` 里的包，依赖只朝一个方向，没有循环（ArchUnit 检查）：

| 包 | 内容 |
|---|---|
| `boot` | 组装、启动、关闭：`Engine`、`Storage`（打开和关闭数据目录）、配置。只有它和 `web` 读全局配置 |
| `loop` | 管线：输入环 → 用户通道（落盘、载入冷会话）→ cleanroom（`Kernel.step`）→ 输出环 → Handler；`Intake` 接收提交，`Evictor` 回收空闲会话 |
| `journal` | 原始记录：每会话一个只追加的文件、事件编解码、格式版本、锁、序号 |
| `store` | 能从原始记录重建的东西：索引、回放查询，以及读会话的 `SessionQueries` |
| `conversation` | 控制台看到的对话条目，是事件的展示投影 |
| `handler` | 执行命令：组装上下文、调模型、跑工具和插件工作 |
| `model` | Claude 和 OpenAI 兼容两个模型客户端，失败分类共用 `ModelFailures` |
| `tool`、`mcp`、`memory` | 内置工具、MCP 客户端、每用户记忆 |
| `sse`、`web`、`auth` | 推送、HTTP 接口、登录 |
| `plugin` | 插件 SDK |
| `json`、`net` | JSON 文本、主机名判断 |

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

## 数据目录

默认 `~/.local/share/tantalum`（`tantalum.data-dir`）。目录下的文件只有进程所有者能读写（600/700），会话文件含完整的 prompt 和工具结果。

| 路径 | 内容 |
|---|---|
| `sessions/<用户>/<会话>.jsonl` | journal：每个会话一个文件，第一行是文件头，之后每行一个事件，只追加 |
| `index.db` | 会话列表、提交去重、用量的索引（SQLite）。可以由会话文件重建 |
| `format.json` | 存储格式和内核语义的版本 |
| `users.db` | 账号 |
| `store.lock` | 进程独占锁：一份数据目录同时只能被一个进程使用 |
| `memory/<用户>/` | 只在 `workspace-mode=shared` 时使用的记忆目录；默认每个用户的记忆在自己工作目录的 `memory/` 下 |

用户名和会话 ID 里不能直接做文件名的字符会被编码，太长的名字截短后加哈希。只有会话文件是事实来源，索引落后或丢失时，启动时从会话文件补齐。旧版本留下的 `snapshots/` 目录不再使用，可以删除。

## 用 journal 查看一个运行

**控制台：** 选中运行，再选一次模型调用，就能看到回放得出的完整 prompt 和回复。

**接口：** 先登录拿到 Cookie，再带着它调用。

```sh
curl -c cookie -H 'content-type: application/json' \
  -d '{"username":"yi","password":"..."}' http://127.0.0.1:8080/api/auth/login
curl -b cookie http://127.0.0.1:8080/api/sessions/<session>/runs
curl -b cookie http://127.0.0.1:8080/api/runs/<run>/turns
curl -b cookie http://127.0.0.1:8080/api/runs/<run>/turns/1
curl -b cookie http://127.0.0.1:8080/api/runs/<run>/trace      # 事件、处理方和时间段（旧接口，轨迹页换新后删除）
curl -b cookie 'http://127.0.0.1:8080/api/sessions/<session>/trace'              # 轨迹记录：最新一页
curl -b cookie 'http://127.0.0.1:8080/api/sessions/<session>/trace?before=<seq>' # 更早的一页
curl -b cookie 'http://127.0.0.1:8080/api/sessions/<session>/trace?run=<run>'    # 从最新一直到这个运行
curl -b cookie http://127.0.0.1:8080/api/sessions/<session>/trace/calls/<command> # 一次模型调用的完整输入
```

**轨迹记录**（`/api/sessions/<session>/trace`）按运行从新到旧分页，一页至少 20 个运行或 400 条记录，不拆开一个运行。记录有 9 种：`run`、`system`、`user`、`context`、`compacted`、`request`、`tool`、`work`、`hook`，每条带 `id`、`pos`（创建它的事件“序号.下标”）、所属的轮和步、开始和结束时刻。记录里不放 prompt 的消息、system 原文和工具 Schema，这些按需从 `/trace/calls/<command>` 取。实时的变化通过 SSE 的 `trace` 消息推送，客户端按 `id` 覆盖；页面和推送来自同一个投影，所以刷新前后看到的一样。

**journal 里和轨迹有关的字段**，旧版本写的行没有这些字段，读出来是空，显示“未记录”：

| 事件 | 字段 | 含义 |
|---|---|---|
| `ModelReplied`、`ModelFailed`、`ModelInterrupted` | `startedAt` | 拿到模型并发名额、开始调用的时刻 |
| | `firstTokenAt` | 第一个内容分片（文字、思考或工具参数）到达的时刻 |
| | `options` | 实际发出的请求选项：提供方、模型、最大输出、是否允许工具，以及 thinking、effort 等；不含任何请求头、地址和密钥 |
| `ToolFinished`、`WorkDone` | `startedAt` | 拿到名额、真正开始执行的时刻；没有真正执行时为空 |

**钩子的决定**不写 journal：内核在钩子拦截、要求工作或带补丁放行时报一条 `HookDecided` 事实（钩子名、位置、结果、理由、补丁的长度和工具名），回放时重新算出，推送和轨迹里都看得到。只放行、没加内容的钩子不报。

**直接看事件：**

```sh
jq -c 'select(.seq) | [.seq, .type, .run, .at]' ~/.local/share/tantalum/sessions/yi/<session>.jsonl
```

**回放能保证什么。** 同一版本的程序回放一个会话，得到的 prompt 和当时发给模型的逐字节相同，会话在不在内存都一样。内核语义变了（升级后 `format.json` 的 `semantics` 变化，启动日志会警告 `kernel semantics changed`），旧会话的 prompt 回放可能不再和当时逐字节相同；journal 原文不受影响，旧会话照常打开、继续。

## 运维

**启动。** 启动时先比较会话文件和索引，补齐落后的部分；补不上就拒绝启动，提示删掉 `index.db` 重建。然后给索引里没结束的运行收尾，这些会话和平时一样按需从文件载入。日志：

```
index caught up with 3 session files
store ready at /srv/tantalum in 1910 ms: 2 runs left unfinished by the previous process closed as interrupted
```

第一行只在索引落后（比如上次被 `kill -9`）时出现。50 万个事件的数据目录启动约 2 秒，实测数字见 `.spec-workflow/steering/tech.md`。

**崩溃或重启时没结束的运行。** 启动时给它们追加结束事件：已经发出但没有结果的工具调用补一条“结果未知”，运行以“已中断”结束，控制台显示“服务重启，这次运行已中断”。不会自动重新调用模型或重跑工具，因为写操作可能已经生效；用户在同一会话继续提问即可。

**旧数据目录。** 早期版本用单个 `journal.db`。数据目录里有 `journal.db` 而没有 `format.json` 时拒绝启动，不改动任何文件：

```
/srv/tantalum holds an old journal.db and no format.json; this version cannot read it. Point tantalum at a new data directory.
```

旧数据不迁移。用 `--tantalum.data-dir` 指向一个新目录；旧目录不再需要时自行删除。`format.json` 的存储版本比程序新时同样拒绝启动，要升级程序。

**会话常驻内存。** 没有运行、没有排队提交的会话，空闲超过 `idle` 或常驻数超过 `max-resident`（最久没用的先走）时移出内存，下次打开时重放它的会话文件载入（1890 个事件的长会话约 0.4 秒）。回收在处理事件时顺带检查，所以完全没有请求的服务器不会主动回收，常驻数仍受上限约束。

| 配置 | 默认 | 含义 |
|---|---|---|
| `tantalum.residency.idle` | `30m` | 空闲多久移出内存 |
| `tantalum.residency.max-resident` | `1000` | 常驻会话上限 |
| `tantalum.residency.sweep-interval` | `1s` | 两次回收检查的最小间隔 |

**关闭。** 收到 `SIGTERM` 后先拒绝新提交、关掉推送连接，再等在途的模型和工具调用最多 `tantalum.shutdown-wait`（默认 30 秒），到时中断它们；之后关索引和文件。被中断的运行下次启动按上面的规则收尾。

## 记忆

模型可以替每个用户记住跨会话仍然有用的事实：偏好、纠正过的做法、项目事实、外部链接。四层模型里实现了第二层（会话摘要，见下面的“上下文压缩”）、第三层（个人长期记忆）和第四层（共享知识，只读）。

- **存在哪**：每人模式下在 `<workspace>/<用户名>/memory/`，索引 `MEMORY.md` 每条一行，条目 `<name>.md` 带 frontmatter（名称、描述、类型、更新时间）。共用工作目录模式下放到 `<data-dir>/memory/<用户名>/`，这样别人的 `grep` 搜不到。索引以条目文件为准，每次写删后重建。
- **怎么进 prompt**：运行开始时把记忆指引、共享知识、`AGENTS.md` 和该用户的索引依次拼进 system，写进 `ContextAssembled` 事件；回放不读文件。索引在 system 末尾、钩子追加内容之前，prompt caching 的断点整个运行里稳定。条目正文不常驻，模型用 `memory_read` 按需读。新记忆从下一个运行开始生效：会话的 system 和工具在第一个运行时固定（所有模型相同），之后可变内容有变化时追加一条用户消息说明，新工具要到新会话才可见。
- **工具**：`memory_write(name, type, description, content)` 新建或同名覆盖，`memory_read(name)`，`memory_delete(name)`。名称 `[a-z0-9-]{1,64}`，类型 `user`、`feedback`、`project`、`reference`。看起来像密钥、令牌、口令的内容会被拒绝写入。
- **上限**：`tantalum.memory.max-entries`（200）、`max-index-bytes`（16 KB）、`max-entry-bytes`（8 KB）。超限时写入被拒并提示模型先删或合并；不做自动整理。
- **共享知识**：`tantalum.knowledge.dir` 指向管理员维护的目录，其中的 `*.md` 按文件名顺序进所有用户的 system（总字节上限 `tantalum.knowledge.max-bytes`，默认 64 KB）。模型改不了它。
- **边界和删除**：知识、`AGENTS.md`、索引用标签包裹并转义闭合标签，这只降低注入风险，不是保证。记忆变化时只追加变化的部分。删除记忆不会清除 journal 和历史消息里的内容，同一会话的 system 也要到压缩或新会话才更新。
- **界面**：设置 › 记忆：列表、正文、删除（需确认）、手工新增。接口 `GET/POST /api/memory`、`GET/DELETE /api/memory/{name}` 只操作当前登录用户的记忆。

## 上下文压缩

长会话不会因为上下文满而失败。模型调用前，内核按估算判断会话有没有越过阈值；越过了就先发一次禁用工具、限长的摘要调用，用摘要替换历史开头，再发原来的调用。所有模型走同一条路。

- **规则**：阈值 = 窗口 − max(`reserve-tokens`, 该供应商的 `max-tokens`)；保留最近约 `keep-recent-tokens`（不超过阈值的四分之一）的消息，其余交给模型总结；一次调用前最多压缩一次；摘要调用可重试，摘要帮不上忙或失败时，如果裁剪工具结果有进展就只裁剪；都不行才放弃，本运行不再自动压缩；模型报上下文超长时只留最新一段压缩并重试一次。压缩后 system 和工具重新组装。完整规则在 `.spec-workflow/specs/context-compaction/requirements.md`。
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
- **会话失效**：只有期间没有别的调用才重试一次；有重叠时报告结果未知，因为 SDK 2.1.0 用同一条错误结束所有在途调用，分不清哪个已经执行。
- **不并行**：同一轮的 MCP 工具逐个执行，这样回放不依赖服务器当时的状态。
- **新工具**：已有会话要到压缩之后或新会话才看到。新会话最多等 `tantalum.mcp.startup-wait`（默认 10 秒）让第一次连接结束。
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

七个钩子（前 6 个是 `KernelHook` 的方法，最后一个是处理器钩子）。`Allow` 带的补丁只在 `beforeModelCall` 生效，其余位置忽略。

| 钩子 | 能返回 | 结果怎么生效 | 补丁 | 示例插件 |
|---|---|---|---|---|
| `beforeModelCall` | `Allow`、`Stop`、`NeedWork` | `Stop` 结束运行；工作做完后再问一次 | 生效 | `DailyQuotaHook` |
| `beforeToolCall` | `Allow`、`Stop`、`NeedWork` | `Stop` 只拦这个调用，模型收到“被拦下”；工作做完后由 `onWorkResult` 决定，不再问这里 | 忽略 | `ReadOnlyHook`、`ToolApprovalHook` |
| `onFailure` | `Allow`、`Stop`、`NeedWork` | 放行交给内核（超长恢复、退避重试）；`Stop` 运行失败；工作做完后重试一次，占用重试次数 | 忽略 | `ProviderBackoffHook` |
| `beforeRunEnd` | `Allow`、`Stop`、`NeedWork` | `Stop` 结束为 STOPPED；工作带 `continueText` 时回到模型，否则再问一次 | 忽略 | `CitationCheckHook` |
| `onWorkResult` | `Allow`、`Stop`、`NeedWork` | 放行回到原处；`Stop` 在工具调用前只拦那个调用，其余位置结束运行；`NeedWork` 再要一轮，记在原等待点 | 忽略 | `ToolApprovalHook` |
| `beforeCompact` | `Allow`、`Stop`（`NeedWork` 当作拒绝） | 拒绝自动压缩：本运行不再自动压缩；拒绝手动压缩：以原因失败；拒绝超长恢复：运行失败 | 忽略 | `PinnedContextHook` |
| `afterToolExecution`（`AfterToolHook`） | 一个 `ToolOutput` | 返回新的就替换结果，原样返回就不变；抛异常时记日志，结果保持上一个 | — | `TruncateOutputHook` |

写钩子时要注意五点：
1. **I/O 只能经 `NeedWork`。** 钩子是纯函数，不读文件、不联网、不取时钟（时间用 `context.at()`）；要做 I/O 就返回 `NeedWork`，由有超时的 `WorkType` 去做。
2. **`onWorkResult` 对所有工作都会调用。** 包括别的插件要的工作，钩子要先按 `work.workType()` 过滤（示例 `ToolApprovalHook`）。
3. **失败的工作结果里没有原来的输入。** 超时、抛异常、连不上时，结果只有错误说明；下一轮要用的信息要由工作在成功结果里带回（示例 `ApprovalWork` 带回工具、参数和次数）。
4. **`onFailure` 要求工作会跳过内置的超长恢复。** 所以上下文超长（`CONTEXT_OVERFLOW`）要放行（示例 `ProviderBackoffHook`）。
5. **工作轮数有上限。** 一个等待点连续要求工作最多 `tantalum.hooks.max-work-rounds` 轮（默认 8，小于 1 拒绝启动），超过时运行以失败结束，原因写明钩子、位置和轮数。`onWorkResult` 接着要的工作记在原来的等待点上；`beforeRunEnd` 的轮数整个运行累计，不清零。插件自己的次数（如审批查询次数）要小于这个上限。

**钩子只能看当前用户的当前会话。** `KernelHook` 拿到的是整个 `KernelState`，但只能读当前运行、当前会话和当前用户的用量（`state.principals()` 里这个用户的那一项）。别的会话可能不在内存里，回放一个会话时也不会有，读它们会让钩子在线上和回放时给出不同的结果。接口拦不住，靠这条约定；示例钩子都符合。

线程模型：`KernelHook` 在内核的单线程里运行，所有用户的状态只在这一个线程上相遇，钩子不需要考虑并发，但必须是纯函数（回放接口会让内核重跑 journal，钩子会被再次调用）。`Tool`、`AfterToolHook`、`WorkType` 每个是一个单例，会被多个虚拟线程同时调用（不同用户、同一运行里的并行工具），所以它们不能保存可变状态；每次调用的身份和工作目录都在 `ToolContext` 里。

超时与失败：`Tool.run` 和 `WorkType.run` 都受 `tantalum.tool-timeout` 约束，到时线程被中断、结果按失败交回；撤销运行也会中断它们，所以阻塞操作要能响应中断。钩子返回 null 按 `Stop` 处理。按名单放行的策略钩子（如示例 `ReadOnlyHook`）要把 `memory_write`、`memory_read`、`memory_delete` 加进名单，否则记忆工具会被拦下；记忆目录是用户自己的，不算对工作目录的写操作。做“检查”的钩子在 `onWorkResult`/`beforeRunEnd` 里看到 `work.failed()` 时不要放行（示例 `CitationCheckHook` 会 `Stop`），否则检查没做成就等于通过。从 `onFailure` 申请的工作完成后再调模型会占用重试次数，失败持续时最多重试 3 次。

## 测试

```sh
mvn clean verify          # 不访问外网，不花钱
(cd console && pnpm test)
(cd console && pnpm e2e)  # Playwright 端到端：自动起脚本化模型的后端（8097）和 vite（5174）
```

`mvn verify` 在集成测试阶段还会用打好的 jar 跑 `JarCrashIT`：模型调用和工具调用进行中 `kill -9`，重启后检查已提交的事件不丢、索引补齐、运行收尾、模型和工具没有被再次调用。性能预算用 `mvn -P bench -pl tantalum-examples -am -DskipTests test-compile` 手动跑，超预算时失败。

浏览器和磁盘看到的 JSON（`/messages` 的条目、SSE 消息）由 `WireFormatTest` 逐字节钉住，期望文件在 `tantalum-shell/src/test/resources/wire/`。有意改格式时加 `-Dwire.record=true` 重新生成，并在提交里写明改了什么。

跳号回归：让事件序号每次加 7 再跑一遍全部测试，确认没有代码假设“下一个序号是加一”（运行 ID、命令 ID 和断点续传都从序号来）：

```sh
JAVA_TOOL_OPTIONS=-Dtantalum.test.sequence-step=7 mvn test -Djacoco.skip=true
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
| 会话持久化、崩溃后恢复 | 有（文件） | 有 | 有，会话文件可逐字节回放；重启后未结束的运行自动收尾（未配对的工具调用补“结果未知”），可在同一会话继续，不自动重新执行 | 不做自动续跑：写操作可能已生效 |
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
