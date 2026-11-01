# Technology Stack

## Project Type

生产级、通用的 Agent 运行时底座（服务端 + 参考控制台），供他人开发插件和垂直 Agent。架构约束见 `QAGENT_DESIGN_BRIEF.md`。

## Core Technologies

### Primary Language(s)
- **后端**：Java 25 LTS（record、sealed interface、虚拟线程）
- **前端**：TypeScript 7.0
- **工具**：Maven 3.9、pnpm 11、Node 25（仅构建前端时需要）

### Key Dependencies/Libraries

| 层 | 依赖 | 版本 | 用途 |
|---|---|---|---|
| value | 无 | — | 只用 JDK |
| kernel | 无（只依赖 value） | — | 纯函数内核 |
| shell | Spring Boot（MVC） | 4.1.2 | HTTP、SSE、配置、装配 |
| shell | LMAX Disruptor | 4.0.0 | Input / Output Disruptor |
| shell | `sqlite-jdbc` | 3.54.1.0 | journal 存储 |
| shell | Jackson | 3.1.7（Spring Boot 4.1.2 管理） | 事件与 API 序列化；`anthropic-java` 自带 Jackson 2.21，包名不同，两者并存 |
| shell | JDK `HttpClient` | JDK 自带 | 调用 OpenAI 兼容模型 |
| shell | `anthropic-java` | 3.3.0（实施时确认与 Spring Boot 的依赖无冲突） | 调用 Claude |
| shell | Spring Security | Spring Boot 4.1.2 管理 | 登录、Cookie 会话、Origin 校验 |
| shell | Bouncy Castle `bcprov-jdk18on` | 1.86 | Argon2id 口令哈希 |
| 测试 | Playwright | 1.64 | 端到端（脚本化模型，不访问外网） |
| 测试 | JUnit | 6.1.3（以 Spring Boot 管理的版本为准） | 单元与集成测试 |
| 测试 | ArchUnit | 1.5.1 | 分层守护 |
| 测试 | JaCoCo | 实施时取最新稳定版 | 覆盖率 |
| 前端 | React | 19.3 | 控制台 |
| 前端 | Vite | 8.3 | 构建 |
| 前端 | TanStack Query | 5 | 查询与重放数据 |
| 前端 | Tailwind CSS | 4.3 | 样式 |

### Application Architecture

事件溯源 + 显式状态机 + 单线程 reactor，分三层 `value ← kernel ← shell`。

Spring 只用于 `shell` 的 Web 和装配部分。调用内核的循环、Handler、journal 都是普通 Java 类，通过构造函数注入，不使用 Spring 注解，这样插件 SDK 不绑定 Spring。

### Data Storage
- **Primary storage**：单个 SQLite 文件（WAL 模式），原生 JDBC，不用 ORM。
- **Caching**：无。内存状态树由 journal 投影得出，不算缓存。
- **Data formats**：事件以 JSON 存储，带类型名和 schema 版本。

### External Integrations
- **模型协议**：OpenAI 兼容的 `/v1/chat/completions`，使用流式请求（`stream: true`）。
- **可选模型：本地 strata**，配置如下。

| 配置 | 默认值 |
|---|---|
| `baseUrl` | `http://127.0.0.1:8090/v1` |
| `model` | `qwen3.8-flash-next-unsloth-ud-q4_k_xl` |
| `apiKey` | 可选，从环境变量读取；strata 不需要 |
| 首块超时（含排队） | 300 秒，可配置 |
| 块间超时 | 60 秒，可配置；`: keep-alive` 算作活动 |
| 模型并发上限 | 1，可配置 |

- **默认模型：Claude（Yi 决定）**，配置如下。

| 配置 | 默认值 |
|---|---|
| `model` | `claude-sonnet-5-5` |
| `apiKey` | 只从环境变量 `ANTHROPIC_API_KEY` 读取 |
| `effort` | `medium`（该模型不设时默认 `high`；官方建议智能体编程和多步工具调用从 `medium` 起步） |
| `max_tokens` | 64000，使用流式 |
| 拒答回退 | 开启 `fallbacks: "default"`（测试版功能），实际模型记录在 `ModelReplied` |
| SDK 自动重试 | 关闭，由内核负责 |
| 首块超时 | 300 秒，可配置 |
| 块间超时 | 300 秒，可配置。SDK 的类型化事件不含 `ping`，长时间思考时看不到活动信号，所以比 strata 的 60 秒长（初始值，无实测依据） |
| 思考显示 | `summarized`，控制台能看到进度；不设时默认隐藏，看起来像卡住 |
| 并发上限 | 4，可配置（初始值，无实测依据） |

- **Authentication**（`console-ui` 起）：用户名加口令登录，Argon2id 哈希存在数据目录的 SQLite 里；会话是内存中的 `HttpOnly`、`SameSite=Strict` Cookie，写操作校验 `Origin`，登录失败限速。第一个用户由 `TANTALUM_ADMIN_USER` / `TANTALUM_ADMIN_PASSWORD` 创建，之后用 `users add|reset|disable` 命令行管理。每个用户的工作目录、会话、运行、回放和 SSE 彼此隔离。默认仍只绑定 `127.0.0.1`。

### Monitoring & Dashboard Technologies
- **Dashboard Framework**：React 控制台，构建产物由后端静态托管。
- **Real-time Communication**：SSE。前端用 `fetch` 流式读取，不用 `EventSource`，因为后者不能带自定义请求头。
- **Visualization**：时间轴用自写 SVG 或 CSS，不引入图表库。
- **State Management**：服务端（journal）是事实来源，前端不做持久化状态。

## Development Environment

### Build & Development Tools
- **Build System**：Maven 多模块（`tantalum-value`、`tantalum-kernel`、`tantalum-shell`，以及示例插件 `tantalum-examples`）；前端位于 `console/`，用 Vite 构建。
- **Package Management**：Maven、pnpm。
- **Development workflow**：后端用 Spring Boot 启动，前端用 Vite 开发服务器代理 API。

### Code Quality Tools
- **Static Analysis**：ArchUnit（分层）、编译器 `-Xlint:all`。
- **Testing Framework**：JUnit；`value` 和 `kernel` 零 mock、零线程；`shell` 用集成测试，模型用脚本化替身。前端用 vitest + Testing Library；端到端用 Playwright，后端是 `tantalum-examples` 测试目录里的 `E2eServer`（Maven profile `e2e`，按关键词回放剧本的模型），`pnpm e2e` 自动启动。
- **覆盖率**：JaCoCo，不低于 80%。

### Version Control & Collaboration
- **VCS**：Git（目录尚未初始化）。
- **Commit 格式**：`<type>: <description>`。

## Deployment & Distribution
- **Target Platform**：单进程部署，开发环境为 macOS arm64。
- **Distribution**：Spring Boot 可执行 jar，内含控制台静态文件。
- **Installation Requirements**：JDK 25；模型服务可用。

## Technical Requirements & Constraints

### Performance Requirements
- `Kernel.step` P99 不超过 5 ms（不含 journal 写入）。
- 模型和工具的耗时不影响其他运行的事件处理。

### Compatibility Requirements
- **Dependency Versions**：Spring Boot 使用正式版；`4.2.0-RC1` 等预发布版本不用。
- **模型服务**：任何兼容 OpenAI Chat Completions 且支持 `tools` 的服务。

### Security & Compliance
- 默认模型是云端的 Claude（Yi 决定），完整 prompt 可能含客户代码，会发送到 Anthropic，启动日志会提示。不能外发代码的部署要切换到 strata。
- journal 文件权限限制为进程所有者。

### Scalability & Reliability
- 明确不做跨进程和水平扩展：一个进程拥有一份存储，启动时取得独占锁。

## Technical Decisions & Rationale

### Decision Log
1. **Spring Boot，放在 shell 边缘**：SIer 客户熟悉，认证和监控以后可以直接接入。备选 Javalin 更轻，但这些能力要自己搭。
2. **不用 Spring AI**：其工具循环、Advisor、ChatMemory 分别与等待状态显式（I3）、内核钩子、journal 作为事实来源（I2）重叠，统一抽象也不利于精确还原 prompt。需要时可以在 shell 中加 `ModelClient` 适配器。
3. **自写 OpenAI 兼容客户端**：实际只需发送 prompt、取回回复和工具调用。
4. **SQLite + 原生 JDBC**：满足单进程，journal 是追加写入，不需要 ORM。
5. **使用 Disruptor（Yi 决定）**：Input Disruptor → cleanroom → Output Disruptor，结构与架构图一致，框架成熟。Input Disruptor 上 journal 消费者在前，cleanroom 消费者依赖其进度，直接表达“先落盘再处理”，并用 `endOfBatch` 批量提交。Disruptor 只存在于 shell；`Kernel.step` 仍是纯函数，不感知它。一律用 `tryPublishEvent`；等待策略用不空转的 `BlockingWaitStrategy`，避免占满 CPU。
6. **流式调用模型，分片不落盘**：pi 和 DeepSeek Harness 都用流式。分片只作瞬态进度，每次调用结束时写一个结算事件，内核只看到结果（与 DeepSeek Harness 的 `assistant/message` / `assistant/attempt` 同构）。两级持久化仍未排期，因此崩溃会丢失进行中那次调用的全部分片。
7. **React + `fetch` 流式读取 SSE**：生态大，且以后接认证不用换 SSE 客户端。
8. **工具默认独占，声明后才并行**：沿用 DeepSeek Harness 的 `isConcurrencySafe` 和默认 10 的并发上限，比 pi 默认并行更保守，未声明的工具不会意外并发写入。
9. **`reasoning_content` 逐字回传**：沿用 DeepSeek Harness 的决定。它曾只在工具轮次回传，导致经网关转发时对话重建出错；开关设错又会静默失败，所以不提供开关。代价是多占输入 token。
10. **预留对照 pi 和 DeepSeek Harness（2026-10-31）**：压缩都以追加事件替换投影视图，溢出都规范化后压缩并重试一次，我们预留相同的边界。子 agent 都以工具形式接入；DeepSeek Harness 限每棵树 8 个活跃子 agent、深度 1、超限拒绝不排队，以此为后续默认值的参考。memory 在 DeepSeek Harness 中是默认关闭的第三方 MCP 工具，没有内置服务，我们同样优先走工具接入，但多用户隔离是我们额外的要求。

11. **默认用 Claude Sonnet 5.5（Yi 决定），用官方 Java SDK，与 strata 并存**：两者都实现 `ModelClient`，协议差异（内容块、`thinking` 签名、SSE 事件、停止原因）不进内核。journal 存规范化字段和原始内容块，回放能精确还原 prompt。一次只选一个模型，多模型路由不在本 spec。Claude 要求已发送历史只追加；以后的压缩不能改写含 `thinking` 块的历史，`context-compaction` 必须按此设计。

12. **项目定名 Tantalum（Yi 决定）**：Maven 模块以 `tantalum-` 为前缀，Java 顶层包 `tantalum.*`。`groupId` 暂用 `dev.tantalum`，只在本地构建；对外发布前要确认已取得域名，否则按简报改用 `io.github.<handle>`。

### Claude 实测（2026-10-31）

`claude-opus-5-5`（默认模型改为 Sonnet 5.5 之前测的）流式 + 一个工具，`anthropic-version: 2026-10-21`：HTTP 200，返回 `tool_use`，`stop_reason: tool_use`，用量在 `message_start` 和 `message_delta` 中，事件流含 `ping`。

`claude-sonnet-5-5` + `fallbacks: "default"` + `effort: medium`，同样的请求：HTTP 200，`stop_reason: tool_use`，请求被接受，但这次没有发生拒答。

冒烟测试（task 90、91，走真实客户端和完整的环）：
- Claude：带工具的运行跑通，上下文超长返回 HTTP 400，消息 `prompt is too long`，被识别为 `context_overflow`。
- 带签名的 `thinking` 块回传后，下一次请求被接受。`adaptive` 模式下，简单问题常常没有 `thinking` 块，所以这个测试用需要推理的问题。
- 拒答时实际的回退行为没有测，无法可靠触发拒答。

### Claude 场景测试（2026-11-01，`ClaudeScenarioTest`、`ClaudeCrashRecoveryTest`）

走完整的环（`TestServer` + 真实 `ClaudeClient`），每个场景对应一条需求或一个只有真实模型才暴露的风险，`TANTALUM_SMOKE_CLAUDE=1` 门控。结论：

| 场景 | 结果 | 结论 |
|---|---|---|
| 一次运行八步顺序工具链（长任务） | 9 次模型调用，输入 7.5K、输出 0.48K | 多轮工具和长任务正常。加 prompt caching 前 `cacheRead` 为 0；加上后（断点在最后一个工具、system 块、最后一条消息）同一场景 7.5K 输入里 6.4K 命中缓存、1.1K 写入缓存、只有 20 个按全价，输入费用约为原来的 27%。单次调用的运行（如额度场景）只有写入没有命中，输入费用多付四分之一 |
| 跨会话记住（memory 需求 8.2） | 2 个会话、3 次模型调用，输入 10、缓存读 2.0K、写 2.0K | 会话 A 说“以后 Java 代码一律用 Java 25 语法并记住”，模型调用 `memory_write` 写下 `java-25-syntax-preference.md`；会话 B 的 `ContextAssembled.system` 含索引，问最小 Hello 程序，回答是 `void main() { IO.println("Hello"); }`，没有 `String[] args`。判定标准全部机械断言 |
| 让它忘记（memory 需求 8.3） | 2 个会话、3 次模型调用，缓存读 3.0K | 接口先种一条记忆，用户说“忘掉我偏好的 Java 版本”，模型调用 `memory_delete`，文件消失，下一运行的 system 不再含索引 |
| 递推题链（high effort） | 6 次调用，2 轮带 thinking | 带签名的 thinking 块经 journal 存取、跨多轮工具回传被接受。adaptive 模式下简单任务不出 thinking，测试在没有 thinking 时按跳过处理 |
| 每个钩子位置（只读策略、截断、引用核对、工作结果、每日额度、真实 API 错误） | 全部触发 | 钩子链在第一个 `Stop` / `NeedWork` 处短路，**注册顺序决定后面的钩子看不看得到**；`/api/runs/{run}/turns` 回放时内核把同一段 journal 又跑一遍，钩子被再次调用（实测 4 次变 24 次），这正是钩子必须纯函数、不能有副作用的原因 |
| 模型名不存在（HTTP 404 `not_found_error`） | `INVALID_REQUEST`，不重试，`onFailure` 看到原因 | 失败分类正确 |
| 流式中取消后继续 | 中断时保留已发出的 15 个字符；追问成功 | Anthropic 接受历史里带 `interrupted` 的部分 assistant 消息 |
| 三个用户同时提交 | 三个运行的模型调用在时间上重叠 | 并发上限 4 真的并行；工作目录互不可见 |
| 模型调用进行中杀进程 | journal 止于 `ContextAssembled`；重启后待恢复、不自动重放；撤销后在同一会话继续成功 | Anthropic 接受连续两条用户消息（上一条没有回复） |

### 并发模型与压测（2026-11-01，`ConcurrencyStressTest`）

线程模型：`Kernel.step` 和全部 `KernelHook` 只在 cleanroom 的单个消费者线程上运行，所有用户、所有会话的状态只在这一个线程上相遇，钩子和转移逻辑没有并发问题可言。Handler 每条命令一个虚拟线程；跨线程共享的对象只有 `ToolCatalog`（启动后只读）、`Workspaces`（`ConcurrentHashMap`）、`CallLimiter`（信号量）、`InFlightCommands`、`SubmissionLedger`、`StateView`（volatile 快照）、`SseHub`（`recent` 加锁）、`Transcript`（按会话加锁）、输入环（`ProducerType.MULTI`）。插件对象（`Tool`、`AfterToolHook`、`WorkType`）是单例，被多线程同时调用，必须无状态（README 已写明）。

压测（不用真实模型，进 `mvn verify`）：16 个用户 × 每人 2 个会话同时提交，每个运行里 4 个并行工具 + 被只读策略拦下的 write_file + 截断钩子 + 引用核对工作，其中 4 个运行在模型调用进行中被取消；每轮 92 次模型调用、316 个事件，重复 2 轮。检查的不变量全部成立：每个 prompt 只含自己的文件；SSE 只收到自己的事件；没有 `CommandDropped`；回放得到的 prompt 与实时发给模型的逐个相同；重启后回放重建的对话视图与实时视图逐字节相同，且不会再调用模型。同一用户的多个会话之间只共享工作目录、每用户在途上限和按 principal 投影的状态（如每日额度）；`requestId` 去重按“用户 + requestId”，客户端要保证它全局唯一。

### 代码审查整改（2026-11-01，Open Code Review + claude-sonnet-5-5）

四轮 `ocr scan` 审完 kernel、shell、examples、console 全部源码（value 模块的 64 个 record 和构建配置未审），约 47 美元；287 条评论里核实为真并修复的 52 条（提交 3706665、1ab2cf0、0b6decb、43ec797 及 examples 一次），误报约 20 条，细节在会话的 `ocr-triage.md`。值得记住的结论：

- **内核**：`ModelInterrupted` 以前在任何状态都追加文本，现在只在该运行仍是会话末尾时追加（`SessionState.lastRun`），且中断的是正在等待的调用时运行以 CANCELLED 结束；`RunEnd.finish` 把未并入的 steer 追加为用户消息；从 `onFailure` 申请的工作完成后再调模型也计入重试次数；钩子返回 null 按 `Stop`。
- **运行时**：两个环都设了 Disruptor 异常处理器，`Cleanroom` 捕获内核之外的 `Throwable`，三者任一出错入口都返回 503（以前消费者线程静默死亡、提交永远 429）；`WorkType.run` 和工具一样受 `tantalum.tool-timeout` 约束；`OpenAiClient` 有连接和响应头超时；`Engine.close` 先排空环再关 journal，每步独立。
- **安全面**：登录限速在校验前预占账号计数（并发猜密码不能越过 5 次），登录输入限长；`ExternalGate` 释放钳到 0；`Workspace` 对越界和不存在报同一句话；工具错误信息不带服务器路径；SSE 挤出边界按（序号, 下标）记录。
- **明确不改、留给后续 spec**：路径校验与打开之间的 TOCTOU（sandbox spec）；`TurnQuery` 每次请求回放整个 journal、停用后已打开的 SSE 不断开、关闭时等待在途 Handler（ops-hardening spec）。

### memory spec（2026-11-01）

四层记忆模型：1 工作记忆（消息历史，已有）；2 情景记忆（会话摘要，属 `context-compaction`）；3 个人长期记忆（本 spec）；4 共享知识（本 spec，只读）。层与层之间不自动流动。实现在 `tantalum.shell.memory`，内核和 value 不改。

- **存储**：每用户目录 `memory/`，索引 `MEMORY.md` 由条目文件的 frontmatter 重建；写入临时文件 + 重命名，每用户一把锁。共用工作目录模式下放到数据目录（对需求 1.4 的修正：共用 root 会被所有人的 grep 搜到）。
- **进 prompt**：`ContextHandler` 的 system 顺序固定为基础提示 → 记忆指引 → 共享知识 → `AGENTS.md` → 索引；索引在最后，钩子 `PromptPatch` 追加在它之后，prompt caching 的 system 断点运行内稳定；运行开始快照进 `ContextAssembled`，回放不读文件（`MemorySnapshotTest`）。
- **安全**：名称白名单、路径不可能越出目录、`CredentialGuard` 拒收像凭据的内容且不回显、接口按登录用户、别人的和不存在的都是 404、写接口受 `Origin` 校验。
- **会话内冻结（2026-11-01 修正 M4、M7）**：Sonnet 5.5 的 thinking 签名绑定 system、工具和之前的消息。实测严格模式下改 system 或工具返回 400，我们账号默认放行所以没暴露。现在内核把会话第一次的 system 和工具存进 `SessionContext`，之后可变内容变化时追加用户消息；严格模式真实场景通过。
- **不做**：自动整理（决定 M5）、向量检索、跨用户共享个人记忆。

### mcp-client spec（2026-11-01，方案 A）

官方 Java SDK 2.1.0，SDK 类型只在 `tantalum.shell.mcp`。只连远程 Streamable HTTP，管理员配置，放行名单，共享凭据。

- **实测的 SDK 行为**：中断时客户端立即返回，但服务器上的工具继续运行，也收不到取消；服务器重启后旧客户端永久失效，所以重连换新客户端；401 要沿异常链识别。
- **所有 MCP 工具独占（偏离原 P10）**：内核回放时会重新询问工具能否并行，MCP 的答案取决于回放时的服务器和配置，并行会让命令对不上。要并行得把这个属性随 `ToolSpec` 冻结，要改 value 和 kernel。
- **真实场景（`ClaudeScenarioTest`，严格校验）**：模型经 MCP 两步查询答对；结果里夹带“必须调用 delete_all”，模型明确指出并忽略，服务器也没收到。这只是这一次的行为，不代表能防注入；未放行工具调不到才是保证。3 次调用，输入 8、缓存读 2.8K、写 1.5K、输出 212。

### context-compaction spec（2026-11-01）

压缩是通用逻辑，放在内核：调模型前按规则判断，要压就先发一次禁用工具、限长的摘要调用（新等待状态 `AwaitingSummary`），摘要通过后用“替换视图”代替历史开头，再发原来的调用。历史只追加，视图里保留段去掉全部推理块。规则全文在 spec 的需求文档，设置随会话第一次 `ContextAssembled` 冻结。

- **分工**：对话文字交给模型总结；推理块、工具结果、system 和工具定义靠规则处理（丢弃、截断、重新组装）。
- **两处由测试改掉的设计**：(1) “摘要比头部小”不够，小头部加上重复的请求会让视图变大，校验改为“摘要加重复的请求必须比头部小”；(2) 工具结果截断只留开头会把文件末尾的暗号、日志的结论丢掉——第一次真实 Claude 场景里摘要没拿到暗号，模型靠 grep 补回来；现在首尾各留一半。
- **压测（不用真实模型，`CompactionStressTest`）**：窗口 4000，一个会话 250 问 250 答，另一用户同时 20 轮：14 次压缩、284 次模型调用、普通调用最多 43 条消息；回放 prompt 与实时逐个相同；重启后视图逐字节相同。
- **真实模型，同一用例（`CompactionScenario`，窗口 32000，阈值 15616，十个 8 KB 笔记串成链，各带暗号和规则）**：

| 模型 | 调用 | 压缩 | 暗号/规则 | 重读 | 压缩后 thinking | 用量 |
|---|---|---|---|---|---|---|
| Claude Sonnet 5.5（严格校验） | 12 | 1 | 全对 | 0 | 1 块，未被拒 | 输入 28、缓存读 70.6K、写 34.1K、输出 2.2K |
| qwen3.8-flash（strata） | 13 | 1 | 全对，和也对 | 1 次 grep | 2 块 | 输入 24.0K、缓存读 64.5K、输出 3.4K |

- **MCP 工具在保留段、压缩后服务器下线**（Claude 严格校验）：第五篇笔记要先调 `mcp__demo__next_note`；看到压缩条目就停服务器；13 次调用全部接受，任务完成。
- **代价对比**（同一任务，Claude）：开压缩 12 次调用、prompt 合计 103K token；不开 11 次、153K。多出的一次是摘要调用，省下的是之后每次都不再带的头部。任务结果两边都完整。
- **手动压缩**：`POST /api/sessions/{id}/compact`，空闲会话上开一个特殊运行；运行中、无设置、无可压缩头部返回 409。
- **真实手动压缩暴露的错误（已修）**：代码把“头部里最新的用户消息”当作当前请求，违背规则 5。保留段已有更新的用户消息时，旧的大消息会被原样留下，摘要校验必然失败。修正后只有保留段没有用户亲笔消息时才保留请求原文。修正后 Claude 手动压缩通过：摘要含指定暗号，压缩后不调工具答对；4 次调用，输入 16、缓存读 36.5K、写 648、输出 504。
- **对已批准文档的其他实现差异**：手动压缩无可压缩头部时接口直接 409，不开运行（设计里此情况是运行以 FAILED 结束，内核的竞争路径仍是）。

### 对照补查和修复（2026-11-01，spec-audit-fixes）

对照 pi 和 DSH 源码补查 `context-compaction`、`mcp-client`、`memory`，修复发现的缺陷。

- **压缩**：超大工具结果加很小的头部以前无法恢复，现在只裁剪；规则 5 的重新组装以前没实现，现在有了；旧摘要不丢；文件列表由程序记录；摘要调用可重试。
- **MCP**：一次失败不再拆共享连接。**SDK 2.1.0 的实际行为和 pi 的注释不同**：收到 404 时用同一条无原因的 `RuntimeException` 结束所有在途调用（`McpClientSession.dismissPendingResponses`），所以只有期间没有别的调用才重试，且只能靠错误消息识别。升级 SDK 时要复查。
- **记忆**：变化只发差异，注入内容有边界和上限。
- **没有参考依据的数字**：只裁剪门槛 min(500, T/16)、分页 100 页。

真实模型（Claude 严格校验、本地 qwen，同一用例）：

| 用例 | Claude | qwen |
|---|---|---|
| 300 KB 文件，头部只有首条请求 | 只裁剪后答对首尾暗号 | 答对 |
| 多次压缩后约束还在 | 3 次压缩，遵守 | 4 次压缩，遵守 |
| 十篇笔记 | 全对，无重读 | 全对，无重读 |

只在 Claude 上跑：压缩后带 thinking、改记忆、MCP 调用在保留段后服务器下线、手动压缩，都没有 400。历史含已不在工具列表里的调用时，请求被接受，但模型会说明这个工具不在可用列表里。文件列表累积只有单元测试，真实用例没有单独断言。A7 的“让在途调用走完”只对普通失败成立：会话失效时 SDK 已经结束了所有在途调用，我们延迟关闭旧客户端也救不回来。

### strata 实测（2026-10-31）

服务地址 `127.0.0.1:8090`，`n_ctx` 为 131072。

| 请求 | 结果 |
|---|---|
| 非流式 + `tools` | HTTP 200，3.5 秒，返回 `finish_reason: tool_calls` |
| 流式 + `tools` | HTTP 200，2.8 秒，`tool_calls` 分片返回，含 `: keep-alive` 注释行 |
| 第一次非流式 + `tools` | 120 秒客户端超时，无响应 |
| 紧随其后的非流式、无工具 | HTTP 200，耗时 145 秒，但服务端统计的生成时间约 1 秒 |

后两行的慢请求发生时，另一个任务正在使用 strata，生成本身并不慢，时间主要花在等待上。这说明模型调用可能被其他使用者长时间占住，超时和并发上限必须可配置。

对客户端的要求：
- `content` 可能为 `null`，推理内容在 `reasoning_content` 字段。
- `arguments` 是 JSON 字符串，解析失败时按工具错误结果返回给模型。
- `finish_reason: length` 可能导致没有最终内容，`max_tokens` 不能设得太小。
- 流式响应要跳过 `: keep-alive` 注释行，并拼接 `tool_calls` 分片。

复测（同日，strata 空闲）：
- 流式响应的最后一个分片带 `usage`，不需要传 `stream_options`；传了结果相同。
- 上下文超长（560052 token）时 1.2 秒内返回 HTTP 400，类型 `invalid_request_error`，消息含 `request exceeds the context window`，并说明请求不会被截断。

冒烟测试（task 90）：断开后约 0.9 秒 `is_processing` 变为 false，生成确实停止；回传 `reasoning_content` 后请求正常。

## Known Limitations
- **Disruptor 版本较旧**：4.0.0 发布于 2023-09，以 Java 11 为基线，尚未在 Java 25 上验证；骨架的第一批 task 先做冒烟验证。
- **SQLite 原生访问**：`sqlite-jdbc` 在 Java 25 上正常工作，但会触发受限方法警告，且未来版本默认会阻止。可执行 jar 的清单要声明 `Enable-Native-Access: ALL-UNNAMED`，测试用 `--enable-native-access=ALL-UNNAMED`。
- **会话在内存里，没有用户管理界面**：重启后要重新登录；用户只能用命令行管理。目录隔离靠路径校验，不是操作系统沙箱，沙箱另开 spec。
- **模型并发默认为 1**：strata 的并发能力尚未测试，多用户时模型调用会排队（不阻塞内核）。
- **strata 与其他任务共用**：其他任务占用时，模型调用可能等待数分钟，甚至超时后进入重试。冒烟测试需要记录模型调用耗时，结果受当时负载影响。
