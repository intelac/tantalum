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
| shell | Playwright for Java | 1.64.0（Apache-2.0；driver-bundle 带 5 个平台的 Node） | 每用户的无头浏览器（`web-tools`）；只有 `tantalum.shell.browser` 能用（ArchUnit） |
| shell | jsoup | 1.19.1（MIT） | `web_fetch` 解析 HTML；只有 `tantalum.shell.fetch` 能用（ArchUnit） |
| local | Jackson | 同 shell | 和连接程序之间的消息、MXC 规则；`tantalum-local` 只依赖 value 和 Jackson |
| connect | JDK `java.net.http.WebSocket` | JDK 自带 | 连接程序连服务器；服务器用 Tomcat 自带的 Jakarta WebSocket |
| connect（外部程序） | 微软 MXC `mxc-exec-mac` | 1.0.1（MIT） | 用户电脑上 `bash` 的沙箱（macOS Seatbelt）；随安装包分发，构建时校验 SHA-256，不进仓库 |
| 测试 | Playwright | 1.64 | 端到端（脚本化模型，不访问外网）；和上面服务器用的 Playwright for Java 是两个包 |
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
- **模型路线（`model-picker`）**：一条 Claude 路线（`tantalum.claude.*`）加任意条 OpenAI 兼容路线（`tantalum.openai-routes.{名字}.*`），每条自己的地址、存密钥的环境变量名（`api-key-env`）、默认模型、并发和超时；`tantalum.provider` 是默认路线的名字。模型列表从各自的接口读（Claude Models 接口、`GET {base-url}/models`），用户在输入框旁按会话选模型和思考档位，详见 README“模型路线”。下面两张表是两种路线各自的默认值。
- **OpenAI 兼容路线（如本地 strata）**，配置如下。

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
- **Windows CI**（`windows-server`）：`.github/workflows/windows.yml` 每次推送在 GitHub Actions `windows-latest`（Windows Server 2025）上跑控制台构建和 vitest、`mvn verify`（含 IT 和启动可执行 jar 的测试）、浏览器 IT 不许跳过、计划任务脚本自测、Playwright 端到端。不用密钥，不跑真实模型；覆盖率门槛只在 macOS 的全量回归上卡（MXC 这类 macOS 代码在 Windows 上跳过）。

### Version Control & Collaboration
- **VCS**：Git（目录尚未初始化）。
- **Commit 格式**：`<type>: <description>`。

## Deployment & Distribution
- **Target Platform**：单进程部署，开发环境为 macOS arm64。服务器也支持 Windows 10 22H2、11、Server 2022/2025 x64（`windows-server`；arm64 没测过），后台运行用当前用户的计划任务（`docs/windows/tantalum-task.ps1`）。
- **Distribution**：Spring Boot 可执行 jar，内含控制台静态文件。
- **Installation Requirements**：JDK 25；模型服务可用。要用浏览器工具（`web-tools`）时，运行一次 `java -jar tantalum.jar browser install`（解压驱动、下载 Chromium headless shell 约 200 MB；Linux 加 `--with-deps`，要 root），中文网页截图要装中文字体（Debian/Ubuntu `fonts-noto-cjk`）。不用装 Node。
- **jar 大小**：带上 Playwright 的 `driver-bundle`（5 个平台的 Node）后，可执行 jar 从约 103 MB 变成约 312 MB（`web-tools` 定下的事 1）。
- **连接程序**（`local-workspace`）：macOS Apple 芯片的 `Tantalum Connect.app`（`jpackage` + `jlink` 裁剪的运行环境 + MXC），打成 zip，不签名、不公证；命令行启动器 `Contents/MacOS/tantalum-connect`。`mvn -o -pl tantalum-connect -am package -Pmac -DskipTests`，脚本 `tantalum-connect/src/package/build-mac.sh`。

## Technical Requirements & Constraints

### Performance Requirements
- `Kernel.step` P99 不超过 5 ms（不含 journal 写入）。
- 模型和工具的耗时不影响其他运行的事件处理。

### Compatibility Requirements
- **Dependency Versions**：Spring Boot 使用正式版；`4.2.0-RC1` 等预发布版本不用。
- **模型服务**：任何兼容 OpenAI Chat Completions 且支持 `tools` 的服务。

### Security & Compliance
- 默认模型是云端的 Claude（Yi 决定），完整 prompt 可能含客户代码，会发送到 Anthropic，启动日志会提示。不能外发代码的部署要切换到 strata。
- 数据目录下的文件和目录限制为进程所有者（600/700），含 `store.lock` 和 SQLite 的附属文件；数据目录本身的权限不改（BACKLOG）。Windows 上不写 ACL：新文件继承用户目录的 ACL（本人、SYSTEM、管理员）；数据目录不在用户目录下时启动 WARN 并给 `icacls` 命令，网络共享拒绝启动（决定 17、20）。

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

11. **默认用 Claude Sonnet 5.5（Yi 决定），用官方 Java SDK，与 strata 并存**：两者都实现 `ModelClient`，协议差异（内容块、`thinking` 签名、SSE 事件、停止原因）不进内核。journal 存规范化字段和原始内容块，回放能精确还原 prompt。按会话选模型（多条路线、换模型）见 `model-picker` spec 和下面第 14 条。Claude 要求已发送历史只追加；以后的压缩不能改写含 `thinking` 块的历史，`context-compaction` 必须按此设计。

12. **项目定名 Tantalum（Yi 决定）**：Maven 模块以 `tantalum-` 为前缀，Java 顶层包 `tantalum.*`。`groupId` 暂用 `dev.tantalum`，只在本地构建；对外发布前要确认已取得域名，否则按简报改用 `io.github.<handle>`。

13. **浏览器用 Playwright for Java，每人一个实例（`web-tools` 方案 A），不用每人一个 `@playwright/mcp`（方案 B）**（2026-11-06 实测，全文在 `specs/web-tools/design.md`“实测”“方案选择”）：A 的快照和截图都在内存里，`acceptDownloads=false` 不留下载文件；B 每次操作都把快照、截图写进输出目录，下载自动存盘，打开网页的结果只给快照文件的链接，违背“服务器上不为联网留下任何文件”。A 的 11 个工具和说明（Bing 搜索、W9 那句话、50,000 字符上限）由我们定，B 要过滤上游 25 个工具、改它的输出，而且每个版本都可能变。A 不用另装 Node（打包在 `driver-bundle` 里），B 要 Node 和每人一个监听本机端口的 MCP 服务器（本机任何进程都能连上去操作这个用户的浏览器）。内存差不多（4 人 Bing：A 1050 MB，B 换成 headless shell 1224 MB），不是理由。A 的代价：jar 大约 209 MB、要联网下载一次；每人多一个驱动进程；浏览器工具和 HTML 转 Markdown 自己写。DSH 用 B，它是单用户的本机程序，输出目录就是用户自己的工作目录，那边不算问题。

14. **换模型时 Claude 的思考块不管是哪个 Claude 模型写的都原样发回（`model-picker` M9），不照 pi、DSH 跨模型就丢掉**（2026-11-06）：Claude 文档 `shared/model-migration.md` 写明换模型时思考块原样发回，接收的模型读不了的由服务端丢掉、不计费，自己去掉反而可能 400；只换 `model` 不算改历史，换回原来的模型时还能读到原来的思考。pi（`transform-messages.ts:122-132`）和 DSH（`llm-deepseek/src/replay.ts:33-38`）跨模型就丢，是因为它们面对的多是不认对方签名的协议。OpenAI 兼容路线的 `reasoning_content` 只发回同一条路线、同一个模型写的（照 pi 的“同一模型”）；跨协议照旧不发。换模型的说明写成这次用户消息开头的文字，system 和工具定义不随模型变，思考签名继续有效；会话里有工具变化时，不支持会话中途工具变化的 Claude 模型不能选（它们看到的历史不一样）。一个运行只有一个模型（从下一次提问生效），压缩、摘要和用量按运行算清楚。

15. **控制台的多语言用带类型的 TypeScript 字典，不用 i18n 库（`console-i18n` I5，2026-11-07）**：照 DSH 自己写（`packages/client/locale`），不照 i18next 这类库。`zh.ts` 是键的来源，`en.ts`、`ja.ts` 写成 `const en: Text`，少一条、多一条在 `tsc` 就失败；带参数的文字是有名字有类型的函数，复数在函数里按数量选（同 pi chord）。另加三层检查：三份字典的对比测试（记号不能翻掉、英文里不能有中日文字符、日文不能照抄中文）、组件里写死文字的正则扫描（TypeScript 7 没有 JS 的语法树接口，所以不像 DSH 解析语法树）、用英文渲染主要页面不能有中日文字符。日期和数字用 `Intl`（照 pi chord，不照 DSH 把格式写进字典），时长写在字典里（`Intl.DurationFormat` 在 jsdom 里还没有）。三种语言都打进主包。

16. **服务器给画面的文字只给代码和参数，由控制台按看的人的语言说（`console-i18n` I9）**：错误是 `{"error": 英文诊断, "code": "<领域>/<原因>", "params": {…}}`（照 DSH 的错误词汇表），代码清单 `console/src/api/errorCodes.json` 服务器和控制台各自比对；参数只放数字和名字，字节数由控制台按语言格式化。插件作者写的文字是 `LocalText`（一个字符串，或者 `{en, zh, ja}`，照 DSH `LocalizedText`）。连接程序、命令行的终端文字放进每个模块一组 UTF-8 的 `Text*.properties`，经 `tantalum.value.text.Bundle` 读（关掉 JDK 按系统语言的回退，占位符 `{name}`，不用 `MessageFormat`）。给模型的文字一律不翻译，服务器日志一律英文。

17. **Windows 上不写 ACL，只继承（`windows-server` N2）**：文件系统支持 POSIX 权限时照旧 600/700；不支持时（NTFS）照常创建、不带属性，新文件继承所在目录的 ACL。所有权限代码都经 `tantalum.local.fs.PrivateFiles`，ArchUnit 拦住绕过它的代码。照 pi（Node 在 Windows 上忽略 `mode`，`auth-storage.ts:36-37,71`）和 DSH（Windows 上不检查、不模拟，`credentials-local/src/index.ts:121-123,137`）。主动写“只有本人”的 ACL 要经 FFM 调 Win32，没有 Windows 电脑验证不了，也和 pi、DSH 都不一样。

18. **不做 Windows 服务，用当前用户的计划任务（N13）**：服务要第三方包装程序或自己写服务代码，而且服务账号的用户目录不是管理员的，N3 “数据目录在用户目录下就安全”的前提就不成立。`docs/windows/tantalum-task.ps1` 登记登录时启动、不限时、不起第二个的任务，不要管理员、不存密码，照 DSH `windows-runner-task.ps1:61-69`。计划任务的“失败后重启”（DSH 也靠它）只管启动失败，进程运行中退出时不重启（windows-latest 实测：结束进程后 120 秒没起来），所以另加每分钟触发一次的触发器，`IgnoreNew` 保证只有一个；代价是没有“最多 10 次”的上限，要一直停着得先 `Disable-ScheduledTask`。

19. **不用 Jimfs 模拟 Windows（N14）**：Windows 上真正出问题的是 JDK 的 Windows 实现（属性视图不支持、目录打不开、共享冲突、保留设备名、`LockFileEx`），Jimfs 一样都不模拟。改为在 GitHub Actions 的 `windows-latest` 上跑全部测试，再用 ArchUnit 在 macOS 上就拦住新写的 POSIX 权限代码。

20. **数据目录在用户目录外只 WARN，不拒绝启动（N3）**：拒绝更安全，但管理员可能有意把数据放在 `D:\` 并自己设好了 ACL（例如给备份账号读权限）；我们按位置判断、不读 ACL（JDK 给的 ACL 条目只有本地化的账号名，拿不到 SID），确认不了已经改好，拒绝会逼人关掉检查。WARN 附用 SID 写的 `icacls` 命令，不受系统语言影响。网络共享（UNC）照旧拒绝：SQLite 的 WAL 不能用在网络文件系统上。

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

（2026-11-06 改正：这一行是有快照时测的，快照 11 月 3 日已去掉。不用快照、同一台机器重测 record-results 之前的版本 `766655f`，1.1 万事件时冷载入首屏是 415 ms，见下面 one-line-journal 第 4 步。）
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

### trace-view（2026-11-03）

- **数据：** 内核报 `HookDecided` 事实（拦截、要求工作、带补丁放行；只放行不报）；模型结果加 `startedAt`、`firstTokenAt`、`options`，工具和工作结果加 `startedAt`，都可为空，为空时写出的行和改动前逐字节相同。组请求的代码从 `ClaudeClient` 拆到 `ClaudeRequest`，请求体和改动前逐字节相同（期望文件 `claude-requests.txt`）。
- **投影：** `shell/trace` 的 9 种记录由 `TracePass` 每个事件算一次，Cleanroom 推 SSE `trace`，冷载入由同一次重放算出（`Projections`）。页面和推送合并后逐字节等于整页重放（`WireFormatTest`、控制台 `TraceStore.test.ts` 读同一份期望文件）。
- **性能（ReadBench，1 万事件语料）：** 长会话轨迹第一页 151 ms（预算 1 s）；冷载入 382 → 399 ms（+4.5%，预算 +20%）；启动和留存堆不变。控制台 gzip 约 219 KB（预算 300 KB），新依赖 `@tanstack/react-virtual` 3.14.21、`diff` 9.0.0。
- **前端：** 记录表虚拟滚动；检查器按记录类型分页签，请求详情按需取 `/trace/calls/{命令}`；时间概览四条道（多一道“插件”），两种画法；统计页签和轨迹共用一个 `TraceStore` 和一个去重的“加载更早”。旧的 `/api/runs/{run}/trace` 已删。
- **和设计的出入：** 纯函数文件叫 `rows.ts`、`segments.ts`，不叫设计里的 `ledger.ts`、`timeline.ts`：macOS 文件系统不分大小写，和组件 `Ledger.tsx`、`Timeline.tsx` 冲突。轨迹页按会话用 `key` 重建，不在 effect 里清状态（开发模式下挂载 effect 跑两遍，会把地址定位的选中清掉）。DSH 统计页的事件行没有做。

### attachments（2026-11-03）

- **新依赖：** TwelveMonkeys ImageIO 3.15.3（`imageio-webp`、`imageio-jpeg`、`imageio-metadata`）读 WebP 和 EXIF 方向；Apache PDFBox 3.0.8 数 PDF 页数。打包后的 jar 在临时端口上验证过能读 WebP（插件在嵌套 jar 里，`ImageNormalizer` 构造时 `ImageIO.scanForPlugins()`）。
- **规范化：** 不解码先走文件结构（`ImageScan`：方向、元数据块、多帧）、按头里的尺寸查上限；大图隔行隔列解码再逐次减半双三次缩小；结果确定，同一张图重新上传哈希相同。`UploadBench`：23.4 MB 照片上传加规范化中位数 491 ms（预算 2 s）。
- **数据：** 三种提交事件加 `attachments`，`ToolFinished` 和 `ToolResultBlock` 加 `images`，为空时不写出，旧行逐字节不变；`SessionState.followUps` 从文字改成用户消息，`omittedImages` 只增不减；`CallModel` 带这次省略的图片 ID 给轨迹用。省略是纯内核规则（`ImageBudget`），回放得到同样的 prompt。
- **客户端：** prompt 里只有引用，客户端组请求时经 `AttachmentMedia` 取内容（进程内第一次用到时核对哈希）；取不到时这次调用以 `INVALID_REQUEST` 失败、不重试、写明附件 ID，请求不发出。新期望文件 `claude-attachment-requests.txt`、`openai-attachment-requests.txt`、`wire/attachments.txt`，原有期望文件不变。
- **真实 Claude：** 图片里的随机词、PDF 里的随机词、`read_file` 读附件路径里的随机词，三项都通过，4 次调用约 0.02 美元。
- **和设计的出入：** 上传多收一个可选的 `type`（浏览器报的类型，只供文件显示），读取多收一个 `name`（下载文件名；名字记在每次出现的引用上，不在存储里）；动图 WebP、PNG 和 GIF 一样只取第一帧；配额已满时同内容的重复上传也会被先拒（收完内容之前不知道是不是重复的）；`fit()` 达不到字节目标时缩到 1×1 就报错，不再无限缩小（任务 13 发现的问题）。

### core-tools（2026-11-04）

- **文件工具：** `TextFiles` 逐行严格 UTF-8 读（去 CR、BOM，长行截断不劈代理对），前 8 KB 有空字节算二进制；原子写是同目录临时文件、`fsync`、改名。`FileToolsBench`：64 MB、71 万行的日志从中间读 100 行 316 ms（要数完全部行数，预算 1 s）；一万个文件里 `find_files` 64 ms（预算 1 s）。
- **先读后写：** `Observations` 按（用户、会话、真实路径）记大小、修改时间和 inode；读之前和读之后版本一致才记；“检查—写—记新版本”在 64 把分段锁里，两个运行同时改只有一个成功。会话移出内存时 `Evictor` 回调清掉。
- **插件开关：** `users.db` 新表 `plugin_settings`，只存关掉的项；`PluginGate`（新包 `shell.access`，放进 `shell.plugin` 会成包循环）是 `Tool.run`、`WorkType.run`、`afterToolExecution` 唯一的调用者，`HookChain` 是内核钩子唯一的调用者，两条 ArchUnit 规则钉住（变异：两处绕过都被抓到）。MCP 工具按配置里的服务器名开关（`McpTool.server()` 是并发名额的命名空间 `mcp:<名字>`，部署后线上核对时发现过它们被归到一个不存在的服务器下）。
- **会话中途换工具：** `ContextAssembled` 带 `enabledTools`、`enabledPlugins`（旧事件里没有，读成全部开着）；`SessionContext` 记全集、会话开始时和现在开着的。工具变化是 `Role.SYSTEM` 消息里的 `ToolChangeBlock`；`PromptProjection.placeToolChanges` 在 `ModelSteps.emitCall` 统一处理没有回复跟着的变化（挪到下一处合法位置、合成净变化）；压缩时和推理块一起去掉。Claude 用 SDK 的 `SystemMessageParam`，内容经 `JsonField` 写原始 JSON；OpenAI 兼容路线按用户消息发文字说明（本地模型的对话模板不一定认开头以外的 system）。
- **实测（2026-11-03，claude-sonnet-5-5，严格思考校验）：** 直接改 `tools` 的第二次请求 400（“The `tools` list differs from the one this block was created with”）；`defer_loading` 加 `tool_addition`、`tool_removal` 都是 200，历史里的思考通过校验。真实 Claude 三项（`CoreToolsClaudeTest`）：窗口读找暗号、读后改函数测试通过、`write_file` 关→开→关，全部通过，第三项连跑三次；把请求改回直接改 `tools` 时第三项 400。这一阶段真实 Claude 约 30 次调用、约 1 美元。
- **回归里抓到的：** `tool_reference` 用 `Map.of` 时键序每次启动不同，请求字节不稳定（缓存会失效），改成有序；测试辅助方法 `CompactionScenario.ask` 刚提交时可能等到上一次运行就返回，改为先记运行数。
- **token 估算：** 用 `count_tokens` 实测发现“字节数 ÷ 3”对 Sonnet 5.5 是少算的（每 token 字节数：base64 1.05、id 1.48、sha256 1.52、JSON 1.80、英文 1.82、中文 2.25、Java 2.67），默认窗口 200000 也小于 Sonnet 5.5 的 1M。Yi 2026-11-04 定开 `compaction-threshold` spec（BACKLOG）。

### compaction-threshold（2026-11-04）

- **窗口从模型读：** `shell/model/ContextWindow` 启动时读一次（最多等 5 秒）、之后每 10 分钟、模型报超长后各读一次，失败保留上次的值，从没读到时用配置值；不大于预留的值不要。Claude 用 SDK 的 beta `models.retrieve`，带 `server-side-fallback-2026-06-01` 头时多返回 `allowedFallbackModels`，各读一次取最小（实测 `claude-sonnet-5-5` 的备用是 `claude-sonnet-5`，都是 1M）；本地服务读 `/v1/models` 的 `meta.n_ctx` → `max_model_len` → `context_length`（实测 llama.cpp 报 131072）。`tantalum.compaction.window-from-model=false` 时只用配置，测试要小窗口时用。
- **每次提问重算压缩设置：** `CompactionSettings` 加 `point`、`bytesPerToken`，旧事件里没有（解码为 0，写出时也省略，旧行逐字节不变），按旧规则算。`shell/settings/CompactionPolicy` 每次组装上下文时按用户选的百分比（`users.db` 新表 `user_settings`）和当前窗口算；内核 `ContextSteps.freezeContext` 只对带 `point` 的新事件换上新设置，不追加消息，所以请求前缀、缓存和思考签名都不受影响，旧 journal 回放走当时的路。摘要调用的头部改按 `W − R` 截，不按用户选的压缩点。
- **估算系数：** `TokenEstimate` 改成带系数的记录，默认 Claude 1.5、本地 1.75。`EstimateSamplesTest` 用仓库里的 Java、中文、英文、带 id 的 JSON 四个样本钉住“不小于实测”（实测值在 `estimate/counts.txt`）：Claude 3686、4190、1627、4458；qwen 2282、2453、1033、4225。qwen 原定 2.0 在 JSON 样本上少算（每 token 1.89 字节），改成 1.75。
- **真实模型（`WindowCompactionTest`，选 50%，每个文件是一行行 sha256，两次提问各读一半）：**

| 模型 | 窗口（来源） | 压缩点 | 第一次提问 | 第二次提问 | 超长 | 花费 |
|---|---|---|---|---|---|---|
| `claude-sonnet-5-5` | 1000000（模型） | 500000 | 8 个 48 KB 文件，prompt 26.0 万，不压缩 | 读完后估算越过 50 万，压缩 1 次；摘要调用 2.5 万（头部的工具结果截到 4 KB）；压缩后 17.7 万（保留段的结果截到 32 KB，首尾各半，暗号在尾部还在）；8 个暗号全对 | 0 | 约 1.2 美元（缓存写 45.8 万、读 26.7 万、输出 1781） |
| 本地 qwen | 131072（模型） | 65536 | 3 个 16 KB 文件，不压缩 | 压缩 1 次，3 个暗号全对 | 0 | 0 |

- **回归里抓到的：** 两个测试的假模型把启动时的 `GET /v1/models` 当成了模型调用（`CrashRestartTest`、`JarCrashIT`）；窗口 4000 的两个测试在新系数下 system 和工具定义就占满，摘要放不下，固定旧系数 3；读窗口失败的日志写了异常类名，碰上 `JarCrashIT` 的“日志里不能有 Exception”，改为我们自己写的说明（如 HTTP 404）只写说明。

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
- **会话在内存里，没有用户管理界面**：重启后要重新登录；用户只能用命令行管理。
- **本机工作区的边界**（`local-workspace`）：服务器被攻破时，它能在每台连着的电脑的允许范围里读写、跑命令（DS 文章 §8.4 的问题没有根治，只靠电脑决定范围和有期限的令牌缩小）。`bash` 的网络只能整体开关；连不上本机端口，命令里起的本地服务自己也连不上。先读后写的记录在连接程序内存里只留最近 64 个会话。只有 macOS。
- **联网的边界**（`web-tools`）：内网默认拦（2026-11-07 改默认值；2026-11-03 曾定同 DSH 放行，但 DSH 是单人本机程序），管理员可用 `tantalum.web.block-private-network=false` 放开。拦着时仍有缺口：配了上游代理时 DNS 由代理解析，出口只能按本机的解析结果检查；经公网反向代理、Docker 或 K8s 的端口映射绕回内网的“发夹”地址（公网地址本身不在拦的范围里）拦不住。网页内容只标明不可信，不过滤。浏览器的登录状态只在内存里，10 分钟不用或重启就没了。读不了 PDF。
- **模型并发默认为 1**：strata 的并发能力尚未测试，多用户时模型调用会排队（不阻塞内核）。
- **strata 与其他任务共用**：其他任务占用时，模型调用可能等待数分钟，甚至超时后进入重试。冒烟测试需要记录模型调用耗时，结果受当时负载影响。

## 崩溃收尾的真实验证（`ops-hardening` 任务 24，2026-11-02）

风险：工具调用途中崩溃后，历史里是带 thinking 签名的回复加一条合成的工具结果，只有真实服务能判断是否合法。

做法：`claude-sonnet-5-5`，effort `high`，开严格校验。工具执行中复制数据目录，用副本启动，再在同一会话提问。

结论：请求被接受，运行完成，崩溃时的工具没有重新执行。有 1 条带 thinking 的回复，所以签名和合成结果确实一起发过。模型回答结果未知（`UNKNOWN`）且没有重试。用量：2 次模型调用，input 8、output 407、cacheRead 2560、cacheWrite 465。

另外 `ClaudeCrashRecoveryTest` 在模型调用途中真实杀掉 JVM，重启后继续提问也通过，只覆盖模型调用途中，没有工具结果。

### record-results 第 1 步实测（2026-11-04，同一台机器）

结果行：内核每一步的结果写成一行，事件和它的结果行一起落盘，落盘后才放行命令、推送和索引。第 1 步恢复仍用内核重放，冷载入时和结果行折叠比对。

**写路径**（`LaneBench`，100 会话、10 用户、每会话每秒 1 个事件，从进入输入环到命令放行，含结果行落盘）：

| 版本 | P50 | P99 | 第一秒之后 P99 |
|---|---|---|---|
| 加结果行之前（只到进入 cleanroom） | 7.3 ms | 22 ms | 12–14 ms |
| 事件、结果行各 fsync 一次 | 36.4 ms | 101 ms（超预算） | 79 ms |
| 事件只写，和结果行一起 fsync（现行） | 6.6 ms | 33.9 ms | 10.3 ms |

macOS 的 `FileChannel.force` 每次刷整块磁盘的缓存，每秒 200 次就排队，所以改成每步一次。

**文件大小**（结果行占事件行的比例）：

| 数据 | 增幅 |
|---|---|
| Yi 的 4 个真实会话（Claude，工具和 MCP 浏览器） | +9% |
| 合成语料 1 万 / 10 万 / 50 万事件 | +41.7% / +42.0% / +42.5% |

合成语料的事件小（平均 1.9 KB）、每几轮压缩一次，每行约 800 字节的结构（运行阶段、命令 ID、用量、判断）占比高。预算 30%，合成语料超出，待 Yi 决定。

**读路径**（`ReadBench`，50 万事件、5557 会话、1.3 GB，3 个样本中位数）：

| 指标 | 现在 | 改造前（ops-hardening） | 预算 |
|---|---|---|---|
| 启动 | 2002 ms | 1910 ms | 5000 ms |
| 首次补齐索引（一次性） | 41.6 s | 28.5 s | — |
| 冷载入首屏 | 379 ms | 194 ms | 1000 ms |
| 常驻首屏 | 8 ms | 25 ms | 100 ms |
| 长会话 `/turns` / `/trace` | 120 / 132 ms | 229 / 136 ms | 1000 ms |
| 启动后留存堆 | 27 MB | 26 MB | 100 MB |

冷载入首屏比改造前慢一倍，仍在预算内；第 2 步改用结果行折叠后再测。（2026-11-06 注：“改造前”的 194 ms 是有快照时测的，不是公平的基线；公平的基线见 one-line-journal 第 4 步。）折叠单个长会话（1889 个事件）比内核重放慢：热身后约 110 ms 对 30 ms，内核本身几乎不花时间，大头是读和解码 JSON。

### record-results 第 2 步实测（2026-11-04）

恢复和查询都改读结果行、不调内核之后，`ReadBench`（50 万事件，3 个样本中位数）：

| 指标 | 改造前 | 第 1 步 | 第 2 步 | 预算 |
|---|---|---|---|---|
| 启动 | 1910 ms | 2002 ms | 1990 ms | 5000 ms |
| 冷载入首屏 | 194 ms | 379 ms | 573 ms | 1000 ms |
| 常驻首屏 | 25 ms | 8 ms | 22 ms | 100 ms |
| 长会话 `/turns` / `/trace` | 229 / 136 ms | 120 / 132 ms | 181 / 207 ms | 1000 ms |
| 首次补齐索引（一次性） | 28.5 s | 41.6 s | 45.6 s | — |

全部在预算内，但冷载入首屏比改造前慢约 3 倍，没有达到需求 3.6“不比现在慢”。原因：内核本身几乎不花时间（长会话 1889 步约 5 ms），大头是读和解码 JSON；按结果行折叠要多解码一份和事件差不多大的结构化数据，还要为结果行里的内容块算哈希。可以继续做的方向：只从最近一次压缩开始折叠对话条目之外的部分、或者换成流式解码，留作后续。

### record-results（2026-11-04）：恢复只读结果行

- **问题**：会话文件只存输入事件，恢复、查询都用当下这版内核和钩子从头重算。代码一改，旧会话的历史和画面就可能悄悄变样（靠 `LEGACY_BYTES_PER_TOKEN` 这类兼容代码硬撑），没有任何报错。
- **做法**：cleanroom 每一步算出 `StepRecord`（会话和运行变了的部分、命令、判断、用量差值），交给这个用户的写入通道；事件只写不 fsync，和它的结果行一起落盘后才放行命令、SSE 推送和索引。恢复（冷载入、`ColdReads`、`/turns`、SSE 补读、轨迹页、请求详情、索引追赶）都用 `RecordReplay` 按结果行折叠，不调内核和钩子（ArchUnit 规则 `restoringDoesNotRunTheKernel`）。内核只在两处还用来“重放”：启动时 `Backfill` 给崩溃留下的事件第一次处理，以及检查工具 `RecordCheck`。
- **结果行的写法**（`RecordCodec`、`RecordMemo`）：没变的字段不写；文件前面已有的内容写成引用（长字符串 `\u0000#`、内容块 `\u0000@`、切片 `\u0000$`、同一行的运行 `\u0000=`，哈希取 SHA-256 前 128 位）；请求写相对上一次的变化，消息和工具写下标；这一步的模型回复写 `fromEvent`。块哈希要求读出来再写一遍逐字节相同，所以结果行的 mapper 按 `BigDecimal` 读浮点数。
- **兼容**：会话文件 `format: 2`、`storage: 2`。存储版本 1 的会话文件不迁移、拒绝启动（Yi 定：原来的会话都删掉）。`semantics` 版本删掉了：不再重放，内核语义变化不影响旧会话。
- **检查工具**：`java -jar tantalum.jar check` 用这个版本的内核重放每个会话、逐步比较结果行，部署前跑。

### one-line-journal 第 1 步（2026-11-06）：一步一行，存好才回“已接受”

- **问题**：一步写两行。事件先写、不 fsync，处理完再写结果行、fsync；崩溃留下没有结果的事件，要启动时的 `Backfill` 用内核补。提交接口在事件进入内存队列时就回 200，这时崩溃消息就丢了。
- **做法**：事件进来只取序号、不写文件；cleanroom 处理完，事件和结果写成同一行（`Line`、`LineCodec`，`result` 字段同以前的结果行、不含序号），fsync 后才放行命令、推送、索引。`Backfill`、事件和结果的配对删掉。提交和手动压缩接口等回执（`Receipts`）：那一行落盘 → 200；写不进去 → 500“保存失败，请重发”；10 s 内没写完 → 504“服务器忙，请重发”（不用 503，控制台把 503 当作服务已停止）。重复提交等原提交的回执。读文件每行只解析一次。
- **兼容**：`format: 3`、`storage: 3`；`storage: 2` 有会话文件时拒绝启动，不迁移。
- **写路径**（`LaneBench`，100 会话、10 用户、每会话每秒 1 个事件，从进入输入环到命令放行）：P50 6.9 ms，P99 27.2 ms（改前 33.9 ms，预算 50 ms），第一秒之后 P99 13.5 ms。

### one-line-journal 第 2 步（2026-11-06）：配上了什么，只在变化时记

- **做法**：`ContextAssembled` 第 2 版加 `toolSources`（工具名 → `builtin` 或 `mcp:<服务器名>`，不写地址和令牌）。`MatchedCodec` 在会话第一次组装、压缩之后、写入方不知道前文时写全文；其余写 `{"diff": true}`，只带 `command`、`compaction` 和变了的字段，工具名单变了写 `toolNames` 和新增或改了定义的 `toolSpecs`。读的时候套回上一次的全文，得到完整的事件。
- **真实会话**（`RealSizeProbe`，备份目录里 Yi 的 4 个会话，用当前内核重放后按新格式写）：748,822 → 777,035 字节，是只记事件时的 103.8%（预算 110%）。最短的会话（3 个事件）是 165%，最长的（58 万字节）是 101%。这几个会话每个只问一两次，“只记变化”省下的比按 10.7% 推算的少。
- **删插件**（`PluginRemovalTest`）：插件的工具和钩子用过之后，去掉插件重启，旧对话和轨迹逐字节相同，钩子不再运行，下一次提问告诉模型 `fetch_page` 已移除。

### one-line-journal 第 3 步（2026-11-06）：工具没了，模型和用户都知道

- **模型**：用户关掉工具、插件删了，内核照旧追加工具变化消息（Claude 用 `tool_removal`，其他模型用文字），现在多带一栏 `unavailable`：之前就不能用、现在仍不能用的工具，文字发法写成 “Still unavailable: …”（同 pi，小模型会把最新一次说明当成完整名单）。会话状态多了 `toolsSeen`（开着过的工具，压缩不清空）。压缩后重新组装时，`toolsSeen` 里现在不能用的工具写成一条上下文更新：“These tools were available earlier in this conversation and are no longer available. Do not call them: …”。它在请求里，所以进指纹、请求详情看得到。
- **画面**：对话页在这一轮上面显示一行灰色小字，“工具变化：新增 …；不再可用 …（已在插件页关闭）；不再可用 …（插件已删除或未加载）”，超过 5 个只列前 5 个。原因按这次组装的全集判断：名字还在就是用户关了，不在就是服务器上已经没有。
- **真实模型**（`ClaudeSmokeTest.toolGoneAfterCompaction`，claude-sonnet-5-5，跑 1 次）：摘要里提到用过 `fetch_url`，用户要求再抓一次，请求末尾是上面那条更新、工具列表里没有 `fetch_url`。模型没有调用任何工具，回答“`fetch_url` 已经不能用了，没法再抓”，并提醒上次的结果可能过时。用量：输入 4 + 缓存写 541 + 输出 136 token。

### one-line-journal 第 4 步（2026-11-06）：打开要快

- **做法**：读会话文件每一行只解析一次（第 1 步就做了）；服务启动完成后在后台虚拟线程里预热（`Warmup`）：jar 里带一个 208 步、41 次压缩的小会话（`warmup/session.jsonl`，由 `WarmupFileTest` 生成和检查），复制到临时目录，用 `RecordReplay` 折叠 10 遍后删掉，不碰数据目录、不占名额，日志一行 `warmup done in N ms`。
- **基线改正**：以前表里“改造前冷载入 194 ms”是有快照时测的。用 `git worktree` 检出 record-results 之前的 `766655f`，和现在的版本在同一台机器、同一份 1.1 万事件语料（113 会话）上各跑 3 个样本（`ReadBench`，中位数）：

| 指标 | record-results 之前（766655f） | 现在 | 预算 |
|---|---|---|---|
| 启动 | 1881 ms | 1932 ms | 5000 ms |
| 冷载入首屏（长会话） | 415 ms | 320 ms | 不比基线慢 |
| 常驻首屏 | 21 ms | 10 ms | 100 ms |
| 长会话 `/turns` / `/trace` | 151 / 170 ms | 166 / 165 ms | 1000 ms |
| 启动后留存堆 | 27 MB | 29 MB | 100 MB |
| 会话文件 | 20 MB | 25 MB（+25%） | 合成语料不设预算 |

record-results 第 2 步测到的 573 ms 降到 320 ms，比 record-results 之前还快。预热和每行只解析一次各占多少没有分开测。合成语料的事件小、每几轮压缩一次，结果占比高，所以文件比真实会话增加得多（真实会话见第 2 步，103.8%）。

### local-workspace（2026-11-06）：工具在用户自己的电脑上执行

- **结构**：服务器上不再有读写文件、跑命令的工具（只剩读本人附件的 `read_attachment`）。用户电脑上的连接程序 `tantalum-connect` 主动用 WebSocket 连 `/api/local/connect`，令牌（`tc_…`，30 分钟到 1 天，只存 SHA-256，可撤销）只从环境变量或不回显的输入读。服务器的转发工具把调用交给 `LocalLinks`，电脑上 `Calls` 再查一遍用户、工具类别、路径后执行。工具代码和消息格式在 `tantalum-local`，服务器和连接程序共用（ArchUnit：`tantalum.local` 不依赖 shell、kernel、Spring）。工具定义固定，电脑连上、断开只改“开着哪些工具”，走现有的工具变化消息，缓存前缀和思考签名不受影响。
- **命令沙箱**（`tantalum.local.sandbox`）：微软 MXC v1.0.1 的 `mxc-exec-mac`（Seatbelt）。启动时 `--dry-run` 检查实际规则、再真跑 `/usr/bin/true`，不通过就没有 `bash`，不会退回到不隔离（照 DSH `SANDBOX_UNAVAILABLE`）。规则：允许目录有 `--allow-write` 时读写、否则只读；每次调用一个临时目录当 `TMPDIR`；只读 `/opt/homebrew`、`/usr/local`、`--allow-read`；禁止 `~/.ssh`、`~/Library/Keychains`；出站整体开关，`hostLoopback` 一律拒绝；环境变量只有 `PATH`、`HOME`（连接程序自己的空目录：设成真实家目录时 git 读 `~/.gitconfig` 被拒报 fatal）、`TMPDIR`、`LANG` 和 `--env` 放行的（名字含 KEY、TOKEN、SECRET、PASSWORD 的不行）。规则文件 600，放在命令读不到的私有目录。MXC 本身也以空环境启动。
- **进程树**：超时交给 MXC 的 `process.timeout`（到期退出码 255，错误输出最后一行是 `{"error":{"code":"backend_error","message":"script timed out after …"}}`，这行不给模型看）。直接结束 `mxc-exec-mac` 时它启动的进程会留下，所以取消时先反复取 `ProcessHandle.descendants()` 逐个结束，再结束 MXC；连接程序退出时结束自己的全部子孙进程。`MxcIT` 用 `pgrep` 确认超时和取消后没有残留。
- **安装包**：`jpackage` 会把包里每个 Mach-O 重新做临时签名，MXC 的微软签名也被换掉；构建脚本换回原文件、不带 `--deep` 重新封 `.app`，再验证 `.app` 和 MXC 的微软签名。macOS 的包版本第一位不能是 0，包版本写 1.0.0，程序自己的版本照旧。默认初始堆是内存的 1/64（128 GB 的 Mac 上 512 MB 封顶），空闲 RSS 245 MB；加 `-Xms16m`（`-XX:+UseSerialGC -Xmx512m`）后 114 MB。
- **实测**（`LinkBench`，同一台 Mac Studio，服务器和安装包里的连接程序都在本机，各热身 200 次后 1000 次，读一个 120 行的 Java 文件）：

| 指标 | 结果 | 要求 |
|---|---|---|
| `read_file` 直接执行 | P50 0.10 ms，P95 0.13 ms，P99 0.20 ms | |
| `read_file` 经过连接程序 | P50 0.55 ms，P95 1.07 ms，P99 1.70 ms | |
| P95 多花 | 0.94 ms | ≤ 20 ms |
| 连接程序空闲 RSS（调用完等 10 秒，`ps`） | 114 MB（堆已提交约 16 MB，`jcmd GC.heap_info`） | ≤ 150 MB |
| `bash true` 往返（20 次，参考） | P50 28 ms，P95 32 ms | |

`bash` 的往返主要花在 MXC 每次起沙箱（设计时实测跑 `/usr/bin/true` 一次 10–20 ms）和 bash 自己启动上。

### 示例插件上部署、策略插件默认关、工具审批“一直允许”、Git 按路径找仓库（2026-11-06，BACKLOG“示例钩子插件上 8091”）

- **加载**：示例插件单独一个 jar（`tantalum-examples-<版本>.jar`，只有示例插件和自动配置 `tantalum.examples.boot.ExamplePlugins`）。可执行 jar 不改：用它自带的 `PropertiesLauncher`（`java -cp exec.jar -Dloader.path=examples.jar org.springframework.boot.loader.launch.PropertiesLauncher`）加 `--tantalum.examples.enabled=true`。`-cp` 启动时清单里的 `Enable-Native-Access` 不生效，要自己加 `--enable-native-access=ALL-UNNAMED`。`ExamplesJarIT` 用打好的两个 jar 按这条命令起服务，核对插件页。
- **默认关**：SDK 加 `KernelHook.defaultEnabled()`（用户没设置过时开不开）和 `failClosed()`（出错按拦截，默认同 `mandatory()`）。`plugin_settings` 表加 `enabled` 列，开和关都记（旧行是关，意思不变）。`mandatory()` 不变；管理员 `tantalum.hooks.required` 把插件设成必须开启，`PluginGate` 和 `HookChain` 都按它，名字不认识拒绝启动。
- **引用核对**：不读文件。依据是会话里 `read_file` 读到的行（`offset` 起，按结果行数或 “Showing lines a-b”）和 `grep` 结果的 `路径:行号:`，判断全在钩子里；`CitationWork` 只把更正要求作为 `continueText` 交回模型（钩子不能追加消息）。改过 2 次还不对就 `Stop`。
- **一直允许**：`WorkDone` 加可选的 `note`（对象），内核在 `workDone` 里先并进 `SessionState.notes[工作类型]` 再跑钩子；结果行加 `notes`，没变不写，旧行逐字节不变。问用户的是新的 `tantalum.shell.question.OpenQuestions`（SDK `Questions`，经 `ToolContext.questions()`），只在内存里：每次等 `answer-wait`，没回答就回“待定”、钩子再要一轮（问题 id 按调用不变，两次询问之间的回答也算），没人再问 5 秒后撤下。参照 DSH 和 pi：两边的工具审批都没有“一直允许”（DSH `user-approval/README.md:165` 只有一次性的；pi `permission-gate.ts:25` 每次都问），DSH 电脑操作插件按应用记“这个会话里允许”但存在内存（`cua/src/application-access.ts:154-182`）；我们按工具名记、记进 journal。
- **Git**：连接协议 2：`hello.roots[].git` 换成 `hello.git`（电脑上 `git --version` 能跑通）。意思变了所以加版本：旧的连接程序被拒绝并提示下载新的，不会悄悄少了 Git 工具。每次调用按路径找仓库，仓库顶层必须在允许目录里这条不变。连接程序拒绝把家目录或 `/` 当允许目录（按真实路径比较）。

### web-tools（2026-11-07）：`web_fetch` 和每用户一个的浏览器

- **结构**：两样工具都在 `tantalum-shell`，内核不改。`web_fetch` 用 JDK `HttpClient` 下载、jsoup 解析、自己写的 `HtmlMarkdown` 转 Markdown；浏览器是 Playwright for Java，每个用户一个 `Playwright` 实例（一个驱动进程加一个 Chromium headless shell），内存里的环境，懒启动、空闲关闭、崩溃重开。两样工具的每个连接都经进程内的出口 `Egress`（只听 `127.0.0.1` 的过滤代理）：解析一次、检查每个地址、连到检查过的地址。方案选择见 Decision Log 13，实测和设计全文在 `specs/web-tools/design.md`。
- **8091 真实演示里抓到的（已修）**：所有回跳转的 https 网址（`api-docs.deepseek.com` 的 302、arXiv 的 303、DuckDuckGo 跨主机的 302）都报 “the connection failed”，没跳转的 https 页面正常。原因：跳转和不收的类型不读正文，`Fetcher` 的正文订阅一开始就先取消、再交出空正文；https 经出口的隧道在 TLS 里协商成 HTTP/2，JDK `HttpClient` 在 HTTP/2 上把取消当成整个回复失败（`IOException: Stream 1 cancelled`），HTTP/1.1 上只是关连接，所以明文 http 的 `FetcherIT` 没暴露。读满 5 MB 截断和图片、PDF 的提示走的是同一段代码，https 上也坏了。改成先交出正文再取消。`FetcherTlsIT` 用内嵌 Tomcat（TLS + h2、自签名证书，测试期间只信任这一张，生产的信任设置不变）钉住同主机 302、303、跨主机 302、不收的类型、5 MB 截断，并断言服务端真的是 `HTTP/2.0`；修之前三项都失败。修好后手动对那三个公网网址各试一次，都跟到了最后一页。
- **按类型认联网工具**：`WebAccess` 只认实现了 `access/WebTool` 的工具（`WebFetchTool`、`BrowserTool`），再按 `server()` 选子开关。原来只看 `server()` 的名字，一个碰巧叫 `browser` 或 `web-fetch` 的插件工具（`Tool.server()` 默认就是工具名）会跟着联网的开关走、来源记成 `web`；`WebAccessTest.onlyWebToolsAreOwnedNotToolsThatShareTheirServerName` 钉住。MCP 工具的服务端名带 `mcp:` 前缀，本来就不会撞。
- **NFR 实测**（同一台 Mac Studio，macOS 26.2，arm64，JDK 25；`FetcherIT`、`BrowsersIT` 打印 `NFR …` 一行）：

| 指标 | 结果 | 要求 |
|---|---|---|
| `web_fetch` 处理 100 KB HTML（解码、jsoup、转 Markdown、拼结果），热身 20 次后 100 次 | P95 9.9 ms，中位 7.9 ms（100.2 KB 的合成文档页：标题、段落、链接、列表、表格、代码、隐藏内容、脚本） | ≤ 200 ms |
| 整次 `web_fetch`（本机回环测试站、经出口），同上 | P95 9.2 ms（参考：是上面那段的上限，两次量的波动比差值大） | |
| 浏览器第一次打开（驱动已解压，同 `browser install` 之后）：起驱动、起 Chromium、新环境、打开本地页、取快照 | 单独跑 398–510 ms（三次）；全量回归里和别的测试一起跑约 1.5 s（两次） | ≤ 5 s |
| 1 个浏览器空闲（开着一个小页面，等 2 秒），`footprint` 整组进程 | 156–157 MB（其中驱动 node 约 125 MB） | ≤ 300 MB |
| 4 个同时开着本地搜索结果页（50 条结果、40 个导航链接），各取快照、截图 | 714–715 MB；4 人并行共 491–509 ms | ≤ 1.2 GB |
| 4 个同时开着很重的本地页（340 KB：3,000 段、1,000 行带按钮的表、400 个链接），同上 | 1,224–1,245 MB | 只量不判定 |

  内存用 macOS `footprint`（共享页只算一次）量全部驱动和 Chromium 进程；Linux 上 `BrowsersIT` 改用 `/proc/<pid>/smaps_rollup` 的 PSS 相加，还没在 Linux 上跑过。很重的页面上 4 人略超 1.2 GB，所以 NFR 按“比 Bing 结果页大的普通结果页”判定（设计“实测”里 4 人开真实 Bing 是 1,050 MB），重页面的数字写进 README，管理员按 `max-open` × 300 MB 预留内存。每人固定多一个约 125 MB 的驱动进程是方案 A 的代价（Decision Log 13）。
- **工具定义的 token 和压缩场景的余量（估算，没有调真实模型）**：12 个联网工具的定义约 5.8 KB。用 `TokenEstimate.ofTools` 在默认配置的服务器上量：Claude 系数 1.5 时 3,197，本地系数 1.75 时 2,749，3 时 1,606。按 core-tools 用 `count_tokens` 实测的 JSON、英文每 token 约 1.8 字节算，真实 Claude 约 3,200 token（5.8 KB ÷ 1.8），和 Claude 的估算 3,197 差不多；任务 11 的实施日志记的 1,606–2,749 是系数 3 和 1.75 的估算，按 Claude 算偏少。`CompactionScenario`（窗口 32000，阈值 15616）里，system 加工具从 6,768 估算 token 变成 10,121，阈值下留给历史的从约 8,850 降到约 5,500，一篇 7.9 KB 的笔记按 1.5 估算约 5,300：每读一篇就要压缩一次，保留段只够放一篇，很可能“nothing fits”或连续压缩，和 tech.md 里记下的几次真实结果（12 次调用、1 次压缩）不再可比。这个场景测的是压缩，所以 `SETTINGS_CLAUDE`、`SETTINGS_OPENAI` 关掉两样联网工具（同 2026-11-06 对 `CompactApiTest` 等小窗口测试的处理），余量回到原来。`WindowCompactionTest`、`CoreToolsClaudeTest` 用模型自己的窗口（1M），多 3.2K 无关紧要，没改。真实部署（8091，Claude 1M 窗口）每次请求多约 3.2K 输入 token，有缓存时几乎都是缓存读。
- **测试**：`WebChainTest`（examples）走真实服务器：关掉浏览器后下一次提问的工具变化正好去掉 11 个 `browser_*`、来源 `web`；截图在会话文件里只有 `ImageRef`、没有 `iVBORw0K`；`web_fetch` 的正文在会话文件里，重启后读会话不再访问网站。控制台端到端：插件页“联网”一块（开关每人各管、刷新还在），`E2eServer` 在主端口 + 1 另起一个 `tantalum.web.browser.enabled=false` 的服务看“管理员已关闭”，`fetch:` 剧本经 `web_fetch` 抓 `E2eServer` 自带的本地页。都不访问外网。

### model-picker（2026-11-07）：几条路线，按会话选模型和思考档位

- **处理时间**（这台 Mac，`ModelApiTest`、`RoutesTest` 量，含本机 HTTP 往返，比服务器处理时间长）：200 个模型时 `GET /api/models` 100 次 P95 3.0–3.2 ms（目标 ≤ 50 ms）；提交接口 100 次 P95 不带 `model` 22.3–24.8 ms、带 `model` 22.4–25.7 ms，差在 ±1 ms 内（目标 ≤ 5 ms，选模型只查内存里的列表）；三条路线、一条 8 秒才回列表，启动等了 5011 ms（目标 ≤ 5 s 加调度误差），另两条照常可用。选择器 200 个模型打开到能点：jsdom 里 23–29 ms（目标 ≤ 100 ms）。
- **真实模型**（`ModelSwitchScenario`，`TANTALUM_SMOKE_CLAUDE=1` 门控，Claude 严格校验加本机 qwen）：2026-11-07 跑过一次，通过（24.5 秒）：
  - 第 1 步 claude-sonnet-5-5 中：2 次调用，输入 6，输出 152，缓存读 4332，写 4408，4.0 秒
  - 第 2 步 claude-opus-5-5 高：2 次，输入 6，输出 165，缓存读 4585，写 4661，3.6 秒
  - 第 3 步 本地 qwen 关：2 次，输入 3383，输出 51，缓存读 3375，10.6 秒
  - 第 4 步 claude-sonnet-5-5 低：1 次，输入 4，输出 23，缓存读 4332，写 652，1.6 秒
  - 第 5 步 claude-haiku-5-5 低：1 次，输入 4，输出 9，缓存写 4989，0.8 秒
- **8091 演示**（任务 21，2026-11-07，会话 `s-modelswitch`）：Sonnet 5.5 低 → 本地 qwen 开 → 本地 qwen 关 → Opus 5.5 高，4 轮全部完成。换路线或模型的两次在日志里各追加一条系统通知，只换档位的一次没有追加，但对话里照样显示“换成…”。qwen 开思考有思考内容，关了就没有。Opus 读到前面几轮的模型通知，答得出哪几轮是别的模型。Claude 用量：Sonnet 输出 203、缓存写 3380；Opus 输出 469、缓存写 3886。这个会话因为有工具变化，选单里 Haiku 5.5、Sonnet 5、Opus 4.7、Sonnet 4.6、Opus 4.6 是灰的（M11）。
- **顺带修的两处不稳**：`web_fetch` 不要正文的回复（跳转、不收的类型）在 HTTP/2 上取消订阅时，JDK 有时把已经交出的回复当成失败（“Stream N cancelled”，`FetcherTlsIT` 约四次一次，8091 上表现为跳转的 https 网址偶尔“连接失败”），改成小的正文读完丢掉，64 KB 以上才取消；钩子矩阵测试读 journal 前先等最后一行落盘（运行视图先于这一行更新）。
- **`check`**：用新 jar 检查 8091 的数据目录，6 个会话里 `yi/s-onelinesmoke` 和记录不一致（seq 1 的会话结果）；用部署在 8091 上的旧 jar 检查结果相同，不是本 spec 引起的。

### console-i18n（2026-11-07）：中文、英文、日文

- **做法**（决定第 15、16 条）：控制台每种语言一份带类型的字典（`console/src/text/zh.ts` 定键），组件在渲染时用 `useText()` 取，切换语言只重新渲染、不重新挂载；用户的选择存在 `user_settings` 的 `language`，浏览器里的 `tantalum.language` 只为刷新时第一帧就对；服务器的错误是代码加参数（45 个代码，`console/src/api/errorCodes.json`），插件作者的文字是 `LocalText`；连接程序、命令行的终端文字在各模块的 `Text*.properties`。四层检查：`tsc`、三份字典对比（`dictionaries.test.ts`）、写死文字的正则扫描（`literals.test.ts`，112 个源文件）、英文渲染各主要页面（`english.test.tsx`）。
- **给模型的不变**：`I18nChainTest`（三个用户分别设中、英、日，同样提问，模型收到的 `Prompt` 逐字相同）；`tantalum-shell/src/test/resources` 下请求（`model/`）、接口和轨迹（`wire/`）的金样文件一个字节没变（只新加了插件页的 `wire/plugins.json`），比对它们的测试照过。变了的只有两处“拒绝原因”：图片存不进去时给模型的 `Cannot store image …: …` 后半句、沙箱用不了时的 `The command sandbox is not available: …` 后半句，原来是中文，现在是英文诊断（设计 I9、I12 定的，给模型的本来就该是英文）。
- **切换语言的时间**（生产构建 `vite preview`，Chromium，这台 Mac；1,000 条记录的轨迹页，时间概览 865 个标记，记录表虚拟滚动、DOM 里 29 行；在设置页“语言”里点选，从点击到新文字出现再过一帧）：5 次 18.7–37.2 ms，最大 37.2 ms；再跑一遍最大 30.9 ms（目标 ≤ 300 ms）。测量脚本在会话的 scratchpad（`i18n-nfr/switch.spec.ts`，接口全部由 `page.route` 假造），不进仓库。
- **主包大小**（`vite build`，gzip）：改造前 231.31 kB（`model-picker` 收尾时）→ 第 1 步 231.97 kB → 第 2 步 234.07 kB → 三种语言都打进去 252.74 kB，增加 21.4 kB（目标 ≤ 30 KB）。
- **和设计不同的几处**：中文的相对时间用 `Intl` 的写法（“5分钟前”，原来是“5 分钟前”，“刚刚”成了“现在”）；`CliText` 放在新包 `tantalum.shell.cli`（放 `boot` 会让 `auth`、`store`、`browser` 和 `boot` 成环）；`Bundle` 写成 record（`tantalum.value` 只放 record、接口、枚举）；任务 14（控制台按代码说错误）挪进第 2 步，否则服务器先改成英文诊断时中文界面会露出英文；错误代码比设计的表多了十来个（图片边长、像素、处理繁忙，消息里图片合计、文件个数，选模型的六种，记忆正文和索引超长，会话暂时不可用），`plugin/required` 状态码保持 400。

### windows-server（2026-11-07）：服务器在 Windows 上

不改设计的部分照 spec（`PrivateFiles`、保留设备名、数据目录检查、计划任务脚本），在 GitHub Actions `windows-latest` 上跑全部测试（`.github/workflows/windows.yml`，不用密钥）。全绿的运行：<https://github.com/intelac/tantalum/actions/runs/42318846814>。runner 是 GitHub 托管的 Windows Server 2025（公开仓库的标准规格 4 vCPU、16 GB），和 Mac Studio 不直接比。

| 量什么 | Windows CI 上 | 要求 |
|---|---|---|
| 整个工作流（有 Maven、pnpm、Chromium 缓存） | 21 分 38 秒（控制台 1 分 15 秒、打包 24 秒、`mvn verify` 12 分 13 秒、冒烟 41 秒、计划任务 69 秒、Playwright 4 分 35 秒） | ≤ 45 分钟 |
| 没有缓存时 | 没单独量全量；第一次运行打包多 25 秒（49 对 24 秒）、装 Chromium 多 6 秒（9 对 3 秒），其余依赖在 `mvn verify` 里下载 | — |
| 空数据目录（只有一个账号）从计划任务启动到 HTTP 有回复 | 5.2 秒、5.3 秒、9 秒（三次） | ≤ 20 秒 |
| 结束进程后计划任务把它再起来 | 60 秒（每分钟的触发器） | 1 分钟 |
| `JarCrashIT` 整个用例（起 jar、杀掉、重启、补齐、继续） | 16.6–19.5 秒；重启补齐那一段的日志在子进程的文件里，没上传，没单独量 | — |

CI 抓到、macOS 上看不到的问题（都已修，测试钉住）：

- **出口隧道卡死：** 两个虚拟线程同时在一个套接字上等读、等写时，Windows 上等写的那个收不到“可写”的唤醒（`FetcherTlsIT` 抓 6 MB 页面 30 秒超时；线程转储里 `egress-relay` 停在 `NioSocketImpl.implWrite`，换平台线程 2.6 秒通过）。`Egress` 的连接线程在 Windows 上用平台线程。
- **连接程序先收到调用、后收到 welcome：** `LocalLinks.attach` 先登记再发 welcome，调用可能抢在前面，电脑按“连的是别的用户”拒绝（`LinkIT`）。改成先发 welcome，所有平台都有这个竞争。
- **401 发新 Cookie：** Spring Security 的请求缓存给每个 401 建会话，新 Cookie 顶掉同一浏览器刚登录拿到的 Cookie（Playwright `approval.spec`）。请求缓存关掉，所有平台。
- **语言缓存晚写：** 选了语言要等服务器回了才写第一帧用的缓存，马上刷新还是原来的语言（`i18n.spec`）。改成和画面一起当场写，所有平台。
- 计划任务的“失败后重启”不管运行中退出：改成每分钟触发一次（tech 决定 18）。
- `MoveFileEx` 替换一个还有句柄开着的文件（NIO 的也算）照样拒绝：靠 `PrivateFiles.replace` 的 1 秒重试；`LockFileEx` 是强制锁，别的句柄读不了 `store.lock`；`destroyForcibly` 的退出码是 1；Java 按 ANSI 代码页解码命令行参数（英文系统上中文参数变成 `?`，环境变量和当前目录不受影响）。

Windows 上跳过的测试都写明了原因：检查 600/700 和用 POSIX 权限造读写失败的（Windows 的 ACL 由 `WindowsFilesTest` 查）；macOS 连接程序的（MXC、`Git.java` 的 `/dev/null`、路径用 `\`、`fileKey()` 为空，Playwright 里两个跑真连接程序工具的用例同理）；提交带不带模型的 P95 差值（每次提交都 fsync，runner 的磁盘偶尔停上百毫秒，两组都被拖到 100–250 ms）。控制台单元测试在 Windows 上一个文件一个文件地跑：ModelPicker 的 jsdom 计时和并行的测试文件抢 CPU 时量到 65–100.6 ms。

### waiting-state（2026-11-07）：等用户回答

内核新阶段 `AwaitingAnswer`：钩子返回 `Ask(question)`，或模型调用内置工具 `ask_user`，运行停下来等人——没有在途命令，不计工作轮数，不超时。问题在这一步的结果行里（运行阶段带着问题，事实 `QuestionAsked`），回答是事件 `Answered`（实现 `Submitted`：占名额、去重、落盘后才回 200）；结算后事实 `QuestionSettled`，钩子的问题调新钩子位置 `onAnswer` 再回到原处（`Resume`，和插件工作共用），`ask_user` 的问题把回答写成工具结果。

- **重启**：`Engine.interrupt` 按索引里的 `run_status` 跳过 `AwaitingAnswer` 的会话，不写 `RunInterrupted`；带期限的由 `DeadlineTimers` 按索引里记的到期时刻重起定时器（索引库版本 2 → 3，加 `answer_deadline`、`answer_timer` 两列；旧库启动时整个重建）。`WaitingRestartTest`：等人跨重启、回答后继续；过期的期限重启后立刻按“没有回答”结算。
- **回收**：等人的会话按 `tantalum.residency.idle` 可移出内存（`Evictor.evictable` 看运行阶段），回答来了和提交一样触发载入。
- **删掉的**：`Questions`、`OpenQuestions`、`ToolContext.questions()`、`ApprovalWork`，配置 `tantalum.examples.tool-approval.answer-wait`/`max-polls`。工具审批只剩 `ToolApprovalHook`（`beforeToolCall` + `onAnswer`），“一直允许”经 `Allow.withNote` 记进会话。
- **journal**：`LocalText.byLanguage` 是无序 Map，编码时按 en、zh、ja 固定顺序写，同样的问题写成同样的字节；格式版本不变。
- **投影**：对话条目 `Question`（OPEN/ANSWERED/EXPIRED/CANCELLED，文字按接口写法）、轨迹记录 `Question`（等人的那段从问到答，第五条泳道）、会话列表 `awaitingAnswer`、SSE `question` 只刷侧栏标记。控制台 `QuestionItem` 代替输入框上方的问题卡，不再轮询。
- **内核里两处容易漏的**：`Kernel.isAwaited` 原来只认 `Awaiting` 的在途命令，等人阶段的定时器事件会被当成“没人等的结果”丢掉；`HookChain` 合并多个 `Allow` 时原来新建一个 `Allow`，会把 `withNote` 丢掉。都有测试钉住。
- **8091 演示**（任务 15，2026-11-07，会话 `s-waitdemo1`，Sonnet 5.5 低档）：开工具审批（审批名单临时加了 `web_fetch`），让模型抓 example.com → 对话页出现“允许调用 web_fetch 吗？”；不回答，`up.sh` 重启；36 秒后重新打开，问题还在（索引从版本 2 重建后 `run_status` 是 `AwaitingAnswer`，启动没给它发 `RunInterrupted`）；答“允许这一次”→ 工具执行 → 运行 COMPLETED。第二轮让模型用 `ask_user` 问“删除还是保留？”，模型自己把 `allow_text` 关了，带文字的回答被接口按 `request/invalid` 拒绝（正确），只选“保留”后模型复述并照做。两轮合计 Claude 用量：输出 359、缓存读 11611、缓存写 4173，约 0.05 美元。顺带发现 8091 上 `web_fetch` 这次返回“无法解析主机名”（和等人无关，待查）。
- **测试**：`AnswerStepsTest` 19 个（五个位置、回答四种去向、期限、取消、重启不变、`ask_user` 的槽位和顺序），`HookChainTest` 加 `onAnswer` 和 `Ask` 行，`ExampleHooksPipelineTest` 的审批改经回答接口，`QuestionApiTest`、`WaitingRestartTest`、`IndexUpgradeTest`、`EvictorTest`；控制台 vitest `QuestionItem` 5 个；Playwright `question.spec`（刷新还在、另一个浏览器同账号、取消）和 `approval.spec` 照过。8091 演示见任务 15。

### ops-hardening-2（2026-11-07）：登录不随重启丢、管理员、按用户名额、载入失败自愈

- **登录**：`users.db` 加 `logins` 表（令牌 SHA-256、用户、口令戳、创建和最后访问时间），Cookie `tantalum.login` 只有 32 字节随机令牌；`LoginContextRepository` 实现 Spring Security 的 `SecurityContextRepository`，每个请求查一次表（在、没过期、没停用、口令戳没变），角色（`ROLE_ADMIN`）也每次从表里读。最后访问时间最多每分钟写一次；过期按 `server.servlet.session.timeout`（12h）。401 不动 Cookie（带旧令牌的请求可能和新登录并行到达，清掉会误伤）；退出时才清。不再有 `HttpSession`。
- **管理员**：`users` 表 `user_version` 1→2 加 `admin` 列；升级时没有管理员就把最早的账号设为管理员并记日志（8091 上 yi 就是这样成的）。`/api/users/**` 要 `ROLE_ADMIN`，否则 403 `auth/forbidden`。规则：不能停用或取消自己（`user/self`），至少留一个启用的管理员（`user/last-admin`）；管理员重置密码或停用 → 那个人的全部登录失效、SSE 断开；自己改密码 → 别处失效、当前保留（口令戳记到当前登录上）。
- **按用户名额**：`UserPermits`。到上限（`tantalum.loop.max-user-permits`，默认 `input-ring-size/4`）后这个用户之后的事件停在分发器里、不占总名额，输入环的消费者继续取别人的；每处理完一个按原顺序放一个进去；任何人归还总名额也替停住的用户试一次（归还那一刻总名额被消费者抢走的边角）。定时器和重启收尾不计入上限但排在停住的事件后面。真实服务里凑不出稳定的“到上限”（在途只是进 cleanroom 前的一瞬），所以 4.3 由分发器单元测试覆盖。
- **载入失败自愈**：`Residency` 记失败时刻，`tantalum.residency.load-retry`（默认 1m）内 `pin` 返回 FAILED、接口 500 `session/unavailable`（文案本就是“暂时不可用，请稍后重试”）；过了时间 `pin` 返回 COLD 让通道重新载入，条目留着，再失败只记一条警告。
- **8091**：部署阶段 1 后登录 → 重启服务 → 原 Cookie 仍有效，`me` 返回 `admin:true`。

### hook-patch（2026-11-07）：钩子补丁改成追加的 system 消息

- **为什么**：补丁拼进 system 和工具列表，补丁一变整个会话的思考签名失效（core-tools 设计里实测过 400）。官方做法（claude-api skill `prompt-caching.md` § Mid-conversation system messages、`preserved-thinking-migration.md:197`）：system 冻结，中途的说明用 `role: system` 消息追加（Opus 5/5.5/4.8、Fable、Mythos、Sonnet 5.5 不需要 beta；Sonnet 5 不支持），工具以 `defer_loading` 追加在列表末尾再 `tool_addition`。pi 的 `before_agent_start` 整段替换 systemPrompt 是因为它的系统提示词本来就是历史第一条消息；DSH 的 `developer` 消息（`llm-pi-ai/src/context.ts:170-203`）和我们现在的做法一样，不支持时降级成用户消息里的 system-reminder。
- **做法**：新内容块 `HookTextBlock(hook, text)`（journal 里 `hookText`）；`SessionContext.hooks`（`HookState(text, tools, active)`，可空，旧行逐字节不变）；`HookPatches.apply`（纯函数）在 `ModelSteps.callModel` 放行后、判断压缩前把合并后的补丁按 `HookDecided` 的长度切回每个钩子的一段，和上次比：文字变了就追加一条 `Role.SYSTEM` 消息（文字块在前、`ToolChangeBlock` 在后），工具按“钩子现在想要的”算净增减；和服务器全集重名的工具忽略；最后一条是助手消息时不追加、报 `HookReported`。`PromptProjection.project(session)` 不再收补丁，钩子工具接在全集后永远 deferred；`placeToolChanges` 推广成 `placeSystemUpdates`（没等到回复的更新往后并成一条）。`Compacting.patch` 永远 `NONE`（留着读旧行）。
- **路线**：`ClaudeRequest` 对 `TOOL_CHANGE_MODELS` 发 `role: system` 文字块（只有文字不带 beta 头）；别的 Claude 模型 `HookTexts.fold` 并进前一条用户消息（`ToolChanges.PREFIX`），工具变化照旧丢；OpenAI 路线按用户消息发、带前缀。请求期望文件新增 `claude-hook-text-requests.txt`、`openai-hook-text-requests.txt`，旧文件逐字节不变。
- **轨迹**：system 行只在初始和压缩后出现（`TraceSystem.split` 不再按钩子切 system）；请求详情多 `additions: [{hook, text}]`，“系统提示词”页签在 system 下面列出；`trace.json`/`trace-sse.txt` 重录。对话页：钩子加的工具在第一次提问那一步会出“工具变化”条目（和用户开关工具走同一条路），之后几圈的变化不出条目（`TranscriptPass.toolChanges` 只看 `ContextAssembled`）；说明不显示。
- **真实 Claude**（`CoreToolsClaudeTest.hookPatchesArriveAsSystemMessagesAndKeepTheThinkingValid`，claude-sonnet-5-5，严格校验）：三问里说明变三次、第 2 问加工具（服务器上没有，模型调用得到 Unknown tool）、第 3 问撤回。4 次模型调用，0 次 400，模型调用了钩子加的工具，第 3 问说工具已不可用。adaptive thinking 在简单题上常不思考，判定改成“最后一次请求之前有思考块”。
- **没做**：对话页不显示钩子的说明；`clear_at` 和 `inline-tools` 两个 beta 不用；钩子加的工具必须是服务器上能找到的才执行得了（`ToolHandler` 按名字找 `ToolCatalog`），所以 `addTools` 实际只能加全集之外、模型只需看见不需执行的工具——这是老问题，这次没改。

### review-fixes 阶段 1（2026-11-07）：管线与 journal

整体 review（`.spec-workflow/reviews/2026-11-07-architecture-review.md`）第 4 节的第一组，全部用脚本化模型，不花额度。

- **回执不说谎**：`SubmissionLedger.claim(principal, request, onNew)` 在认领对别人可见之前、同一个临界区里登记回执（`Intake` 传 `receipts::expect`），重复提交看到认领时回执一定已在；原提交被拒、没进环或中途抛异常时 `Receipts.fail` 以 `NotSaved` 完成回执，名额、新运行计数、认领全部退回；关机（`Engine.close` 在 drain user lanes 之后）和管线停住（`LaneDispatcher.onHalt`）时 `Receipts.failAll`；去重的索引查不了时 `Storage.findSubmission` 抛 `UncheckedIOException`，入口回 503，不再当作新提交。
- **用户通道不会死**：`UserLane.drain` 对 `process` 包一层 Throwable 兜底（停机、丢弃、done 跑完，线程留着）；pin 和写入在同一个受保护区里，异常时按真正钉住的解开；`afterDurable` 的 catch 改 Throwable；`exit.publish` 抛异常按拒绝处理，剩余解钉并丢弃、刚载入的会话 `Residency.cold`；分发器等名额被中断时归还用户名额；停机时 `UserPermits.discardAll` 丢掉停在上限外的事件。多会话并行写一部分失败仍整批失败并停机（既定做法，不改）。
- **journal 写失败**：`SessionFile.appendLines` 失败时截回写入前的长度并作废（再写抛 IOException），`SessionFiles.append` 关掉并丢弃句柄，下次重新 `open` 走 `repairTail`；`RecordCodec` 编码用 `UnknownFact.raw().deepCopy()`，解码的任何 RuntimeException 都是“corrupt result line”；`SessionFileReader.Lines` 一行上限 64 MiB；`StoreLock.acquire` 加锁前失败关通道；`EventCodec.OrderedLanguages` 固定三种之后把其余 `Language` 值按枚举顺序补上。
- **驻留**：`Residency.pin` 返回 `Pinned(state, failure)`；失败过期且无人钉住的条目回收；新 `cold(session)`；`Cleanroom.rejected` 对属于已有运行的事件也报 `PhaseChanged(LOAD_FAILED)`。
- **内核**：新事实 `StepFailed(run, event, failure)`（`Kernel.step` 在没有运行可结束时报，journal 里 `"type":"StepFailed"`）；`CancelSteps.commandDropped` 只在丢的是正在等的命令时结束运行；`ToolSteps.toolFinished` 只收 SENT 的槽位；MAX_TOKENS 截断且带工具调用的回复不执行工具、按“截断”结束；排队的提问各自带模型选择（`SessionState.followUpModels` 和 `followUps` 等长，替代原来的单个 `followUpModel`——journal 里从没出现过那个字段，数据目录 grep 为 0）；第一次冻结上下文时开着的名单和全集求交；`Resume.continueWithText` 把 `work` 交给 `callModel`；`Compactor.isUserText` 接受带附件的提问。
- **异常文字**：`tantalum.value.text.Failures.describe` 只给类名；`HookChain`、`UserLane.loadOne`、`ModelHandler.failed`、`ToolHandler`、两个模型客户端的连接失败都经它，原文记服务器日志；连接程序发给服务器的工具失败是固定文字，细节只打到本机终端。
- **钩子说明按钩子比较**：`HookState.texts` 记每个钩子上次的说明，只有变了的钩子重发自己那块。
- **没做**（留给后面阶段或不做）：`Outcome.enter` 的快照覆盖只有两个调用点、都在同一步内安全，不改；`HookPatches` 跳过时重复的 `HookReported` 不改（那条路径正常不会走到）。

### review-fixes 阶段 2（2026-11-07）：模型客户端与流

- **看门狗持有流**：`StreamWatchdog(firstChunk, betweenChunks)` 自己拿着流（`watch(AutoCloseable)`），流发布时已经过期就立刻关；`abandon()` 给失败路径用。两个客户端不再用 `open[]` 数组，“首块超时时关了个 null、之后流挂死”的洞补上。
- **Claude 流**：不认识的块类型（server_tool_use 等）记成 ignored、不产生内容块；没有 start 的 delta 只有文字才补块，思考、签名、参数的孤立分片丢掉；没签名的思考块和空文字块不进回复（发回去会 400）。失败分类先看中断和看门狗，再分 HTTP 和连接。
- **Claude 请求**：全空白文字块不发；工具结果空白时用 `(no output)`，带图片的只发图片；思考块字段缺失或签名为空时不发、不抛。
- **OpenAI 流**：响应体 try-with-resources 关；200 之后流里的 `{"error":…}` 是一次不重试的失败（`stream error: …`）；没有 `index` 的并行调用按 `id` 分片；错误文字进 journal 前截到 1 KB、去掉控制字符（`OpenAiClient.detail`）。
- 请求期望文件逐字节不变（阶段 2 没有改任何既有请求的字节）。

