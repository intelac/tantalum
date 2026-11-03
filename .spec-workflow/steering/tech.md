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
- **Primary storage**（`ops-hardening` 起）：每个会话一个只追加的 JSONL 文件 `sessions/<用户>/<会话>.jsonl`，是唯一的事实来源。
- **Caching**：`index.db`（SQLite，WAL，原生 JDBC）存会话列表、提交去重和用量。它可由会话文件重建，启动时补齐落后的部分。内存里只常驻有活动的会话，其余按需载入（README“运维”）。
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
- 数据目录下的文件和目录限制为进程所有者（600/700），含 `store.lock` 和 SQLite 的附属文件；数据目录本身的权限不改（BACKLOG）。

### Scalability & Reliability
- 明确不做跨进程和水平扩展：一个进程拥有一份存储，启动时取得独占锁。

## Technical Decisions & Rationale

### Decision Log
1. **Spring Boot，放在 shell 边缘**：SIer 客户熟悉，认证和监控以后可以直接接入。备选 Javalin 更轻，但这些能力要自己搭。
2. **不用 Spring AI**：其工具循环、Advisor、ChatMemory 分别与等待状态显式（I3）、内核钩子、journal 作为事实来源（I2）重叠，统一抽象也不利于精确还原 prompt。需要时可以在 shell 中加 `ModelClient` 适配器。
3. **自写 OpenAI 兼容客户端**：实际只需发送 prompt、取回回复和工具调用。
4. **SQLite + 原生 JDBC**：满足单进程，不需要 ORM。`ops-hardening` 起 journal 改为每会话一个 JSONL 文件（Yi 决定“journal 按 session 分开”），SQLite 只做可重建的索引。
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

### ops-hardening 改造前基线（2026-11-02，Mac Studio，20 核，Java 25，`-Xmx4g`）

合成数据（`Corpus`），每个样本一个新进程，首次调用，3 个样本的中位数。

| 指标 | 1.1 万事件（113 会话，27 MB） | 10 万事件（1113 会话，239 MB） |
|---|---|---|
| 启动 | 3.7 s | 125.7 s |
| 启动后留存堆 | 50 MB | 229 MB |
| 堆峰值 | 796 MB | 1265 MB |
| 会话列表 | 8 ms | 8 ms |
| 打开长会话（常驻，全部条目） | 76 ms | 70 ms |
| `/turns` | 1.8 s | 122.8 s |
| `/trace` | 1.6 s | 125.5 s |
| 安静用户的 `/turns`、`/trace` | 1.6 s | 124.6 s |

事件数增加约 9 倍，启动耗时增加约 34 倍，接近平方增长。原因是 `KernelState.with` 每个事件复制整个 Map。50 万事件没有实测：按平方外推，启动约 50 分钟，超过单个样本 30 分钟的上限，这是推断。

写路径（`WriteBench`，1 个样本）：`FileChannel.force` 约 5 ms，和 SQLite 开 `fullfsync` 相同；`FileDescriptor.sync` 约 0.04 ms，和 SQLite 默认相同，所以现有 journal 在 macOS 上用的是较弱的落盘。100 会话、每会话每秒 1 事件：SQLite（`fullfsync`）P50 7.2 ms、P99 18.9 ms，JSONL（`force`）P50 9.5 ms、P99 27.5 ms。满负荷 100 会话：SQLite 6394 事件/秒，JSONL 416 事件/秒。没有在 Linux 上测。

### ops-hardening 改造后实测（2026-11-02，同一台机器，任务 40、49）

`ReadBench`，3 个样本的中位数，每个样本一个新进程、首次调用。合成数据没有索引库，所以每个规模先单独启动一次补齐索引（一次性成本，单独列出），之后的样本才是日常启动。

| 指标 | 1.1 万事件 | 10 万事件 | 50 万事件（5557 会话，911 MB） | 预算（50 万） | 改造前（10 万） |
|---|---|---|---|---|---|
| 首次启动补齐索引（一次性） | 2.8 s | 7.2 s | 28.5 s | — | — |
| 启动 | 1.83 s | 1.86 s | 1.91 s | 5 s | 125.7 s |
| 启动后留存堆 | 26 MB | 26 MB | 26 MB | 100 MB，增长 ≤ 20 MB | 229 MB |
| 堆峰值 | 441 MB | 455 MB | 457 MB | — | 1265 MB |
| 会话列表 | 8 ms | 7 ms | 8 ms | — | 8 ms |
| 打开长会话首屏，冷载入 | 204 ms | 197 ms | 194 ms | 1 s | 无此路径 |
| 打开长会话首屏，常驻 | 23 ms | 15 ms | 25 ms | 100 ms | 70 ms（全部条目） |
| 长会话的 `/turns` / `/trace` | 237 / 139 ms | 229 / 141 ms | 229 / 136 ms | — | 122.8 / 125.5 s |
| 安静用户的 `/turns` / `/trace` | 7 / 3 ms | 7 / 3 ms | 7 / 3 ms | 200 ms | 约 125 s |

说明：冷载入首屏的第一个样本在 350 ms 左右，后两个约 195 ms。第一个样本时长会话还没有快照，之后的样本由上一个样本关闭时写的快照载入，所以中位数里混有快照的作用。启动时间和留存堆不再随历史总量增长。

`bench.tighten=0.01` 把预算收紧到 1% 时 bench 以状态 1 退出，列出 6 项超标，确认预算检查有效。

写路径（`LaneBench`，真实的写入分发、用户通道和每会话文件）：100 会话分属 10 个用户，每会话每秒 1 个事件，30 秒共 3000 个事件，量从进入输入环到进入 Cleanroom Ring。

| 版本 | P50 | P99 | 第一秒（100 个新文件）P50 | 之后的 P99 |
|---|---|---|---|---|
| 新文件头部单独 fsync | 7.2 ms | 98 ms / 77 ms | 66.5 ms | 12.1 ms |
| 头部和第一批事件一起 fsync（现行） | 7.3 ms | 22 ms / 36 ms | 20–29 ms | 12–14 ms |

第一版超出 50 ms 的预算。用单文件对照测得头部 fsync 约 7 ms、目录 fsync 0.4 ms、追加 fsync 约 6 ms；macOS 的 `FileChannel.force` 刷整块磁盘的缓存，第一秒每个新文件 3 次 fsync，磁盘排队。改为头部随第一批事件一起 fsync 后在预算内，统计口径不变（含第一秒）。预热对照（先让另外 100 个会话写一次）结果相同，说明慢在建文件，不在 JIT。

快照效果（`SnapshotBench`，50 万事件数据里的长会话：250 轮，1890 个事件，2099 个对话条目，3 个样本，有无快照交替、各用新进程）：

| 指标 | 中位数 |
|---|---|
| 冷载入，没有快照（重放 1890 个事件） | 402 ms |
| 冷载入，有快照（重放 0 个事件） | 254 ms |
| 写一份快照 | 99 ms |
| 快照文件 | 5631 KB |
| 会话文件 | 3531 KB |

快照省了约 37% 的冷载入时间，但快照文件比会话文件还大 60%：快照里既有完整的消息历史，也有对话条目，内容重复。载入有快照时仍要 254 ms，主要花在解析这份 5.6 MB 的 JSON 上。

**2026-11-03 Yi 决定去掉快照**：省下的约 150 ms 抵不上第二条载入路径、写快照线程和每次改状态形状都要顾及的兼容成本。冷会话只有重放会话文件一条路。会话长到重放要几秒时，再按那时的会话形状设计。

### ops-hardening 收尾验证（2026-11-02，任务 42–44）

- **`kill -9` 打包的 jar（`JarCrashIT`，`mvn verify` 的集成测试阶段）：** 两个用户，一个会话已结束、一个在等 Claude 兼容的假模型、一个在等 MCP 工具时杀掉，再把索引里已结束的会话改回落后。重启后：会话文件以被杀前内容开头；索引补齐（会话列表、提交去重、用量和会话文件推出的一致）；两个运行以“因重启中断”结束；模型和工具调用次数不变；两个会话都能继续，发给模型的历史里每个工具调用都有结果。被杀时索引本来就会落后（某次运行里 bob 的一条用量还没写），关掉启动补齐时测试失败。
- **Playwright（`ops.spec.ts`）：** 同一用户两个浏览器，一个退出另一个照常收推送；回收出内存的会话重新打开完整；崩溃中断的运行显示“服务重启，这次运行已中断”并能继续。`E2eServer` 空闲 2 秒就回收，全部 19 个端到端测试在不断回收下通过。
- **顺带修复：** 重启中断的运行原来显示“已取消”；`tantalum.residency.*` 只配一项时其余为 0、启动失败（默认值移进 `application.properties`）；启动日志补上载入的会话数、收尾的运行数和耗时。
- **真实 Claude（`claude-sonnet-5-5`，改造完成后重跑，只跑改造可能影响的三项）：**
  - 工具执行中崩溃、严格校验下继续（需求 9.5，合成的“结果未知”跟着 thinking 签名发出）：被接受，模型回答 `UNKNOWN` 且没有重试。2 次调用，input 8、output 395、cacheRead 1280、cacheWrite 1720。
  - 模型调用中真实杀 JVM、重启后继续（`ClaudeCrashRecoveryTest`）：运行收尾为中断，继续提问完成并读到文件内容。测试不打印用量，共 1 次被杀的调用加 2 次正常调用。
  - 三个用户同时调用（写路径改成每用户通道后）：三人的模型调用时间段重叠，没有被串行化。6 次调用，input 18、output 227、cacheRead 6666、cacheWrite 491。
- **已知：** 回收只在处理事件时检查，完全没有请求的服务器不主动回收，常驻数受上限约束（BACKLOG 待 Yi）。

### code-simplification（2026-11-03，整体审查后的重构）

不加能力，按“一种做法、结构和名字对得上、没有残留”改了六个阶段，每个阶段全量回归、一次变异检查、单独提交。

- **结构：** shell 按“原始记录（`journal`）/ 可重建缓存（`store`）”重新分包，包之间无循环，内核子包也无循环，都由 ArchUnit 检查；只有 `boot`、`web` 读全局配置。`Engine` 608 → 276 行，拆出 `Storage`、`Intake`、`SessionQueries`；`ApiController` 392 → 235 行，拆出 SSE 和回放视图两个 Controller，三个 Controller 共用 `WebJson`；`ModelSteps` 拆成三个；`Transcript` 396 → 109 行，投影拆到 `TranscriptPass`（271 行），全量回放只有测试用，挪到测试代码 `TranscriptReplay`。
- **一种做法：** 会话进内存只有用户通道一条路（启动收尾也走它，索引补不上拒绝启动）；上下文变化只发变化的段（内核语义版本 2）；规则 8 一张表；两个模型客户端共用 `ModelFailures`；一个 `Ring`、一个回放循环、一份会话切片 `SessionSlice`。
- **修掉的问题：** 自动压缩同步放弃时 `beforeModelCall` 跑两次（钩子会多要一轮工作）；运行中输入框提示把“排队”写成“插话”；上下文窗口有两个来源；阶段 2 改名让 `isEnded()` 漏进 JSON（由新加的 `WireFormatTest` 发现，根因修复）。
- **测试：** 新增 `WireFormatTest`（接口条目、SSE 逐字节；快照那一份随快照一起删了）、三个规则 8 的缺失用例、启动拒绝和坏会话隔离、`AGENTS.md` 上限等；两个真实 Claude 测试和并发压测里“异步提交后马上看最新运行”“运行结束就比较视图”的竞争改掉。
- **行数：** 主代码 value 1478 → 1551、kernel 2121 → 2154、shell 11204 → 11057，控制台 4685 → 4687。删掉的重复和残留大致被新拆出的小类、头部和注释抵掉；目标是清楚，不是变短。
- **没达到的：** `SessionIndex`（343 行，一个连接一把锁的 SQLite 表）、`McpConnection`（354 行，一个服务器连接的状态机）仍超过 300 行，拆开会让两个类共用锁或内部状态，反而难读，保留并说明；模型客户端按协议解析例外（`ClaudeClient` 404、`OpenAiClient` 306）。
- **真实 Claude：** 阶段 4 冒烟 5 项和思考签名多轮一项；阶段 5 记忆中途变化、压缩保留全部暗号和规则、两次压缩后约束仍在三项，修掉测试竞争后记忆变化和工具中途崩溃两项重跑通过。

### hook-coverage（2026-11-03）

- **每个钩子都有实现**：新增 `ProviderBackoffHook`（失败后）、`ToolApprovalHook` + `ApprovalWork`（调工具前 + 工作结果后）、`PinnedContextHook`（压缩前），加上原有的 4 个，7 个钩子各有示例插件。`kernel.hook.HookMessages.isUserText` 让插件和压缩用同一条“用户亲笔”规则。
- **工作轮数上限**：`Limits(maxParallelTools, maxWorkRounds)`，`tantalum.hooks.max-work-rounds` 默认 8。同一等待点连续最多 8 轮（模型回复后、换下一个工具时清零），结束前按整个运行累计；超过时运行 FAILED 并报 `HookReported`。`beforeCompact` 的上下文带上触发方式。
- **真实管线测试矩阵**（`tantalum-examples/.../hooks/`，86 个测试，约 21 秒）：6 个位置 × 每种结果 55 格；5 个会要求工作的位置各测工作中途取消和重启；轮数上限；三个新插件。ArchUnit 规则保证钩子只依赖 value、`kernel.hook` 和不做 I/O 的 JDK，不取时间、不开线程。
- **矩阵找出并修复的两个缺陷**：
  - 同一步里开始又结束的运行（例如被拒绝的手动压缩）在对话条目里没有结束原因，控制台一直显示运行中。`TranscriptPass.phaseChanged` 修复。
  - 关服务时被中断的处理器，结果被当成正常结果落盘，钩子据此当作真的失败处理（如引用核对以“核对失败”结束），违反 ops-hardening H8“到时中断，重启后按 H9 收尾”。现在关服务中断的结果不写回（`InFlightCommands.abortForShutdown`），用户取消的照旧写回并被内核忽略。等审批时被重启的工具因此补“没有执行”而不是“结果未知”，和事实一致。
- **注释改正**：`application.properties` 说上下文窗口“不参与截断”，实际决定压缩阈值，已改正。

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

## 崩溃收尾的真实验证（`ops-hardening` 任务 24，2026-11-02）

风险：工具调用途中崩溃后，历史里是带 thinking 签名的回复加一条合成的工具结果，只有真实服务能判断是否合法。

做法：`claude-sonnet-5-5`，effort `high`，开严格校验。工具执行中复制数据目录，用副本启动，再在同一会话提问。

结论：请求被接受，运行完成，崩溃时的工具没有重新执行。有 1 条带 thinking 的回复，所以签名和合成结果确实一起发过。模型回答结果未知（`UNKNOWN`）且没有重试。用量：2 次模型调用，input 8、output 407、cacheRead 2560、cacheWrite 465。

另外 `ClaudeCrashRecoveryTest` 在模型调用途中真实杀掉 JVM，重启后继续提问也通过，只覆盖模型调用途中，没有工具结果。
