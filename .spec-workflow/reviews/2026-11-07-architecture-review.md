# 整体 review（2026-11-07，0.1.0 定稿之后）

范围：2026-11-02 起的 151 个提交（local-workspace → web-tools → model-picker → console-i18n → windows-server → waiting-state → ops-hardening-2 → hook-patch → 内网默认拦 → 0.1.0），1359 个文件、约 10.7 万行新增。主代码现在：value 103 文件 3.2k 行，kernel 39 文件 3.1k 行，local 40 文件 3.2k 行，shell 217 文件 26.6k 行，connect 5 文件 1.0k 行，examples 11 文件 1.0k 行，控制台 14k 行。

两条线：① 我按 steering 里的架构约束逐项核对（第 1、2 节）；② 用 alibaba/open-code-review v1.13.3（Sonnet 5.5）整文件扫描（第 3 节）。

## 1. 架构有没有被破坏：没有，守住了的部分

| 约束（tech.md / README“代码结构”） | 现状 | 证据 |
|---|---|---|
| 三层单向依赖 `value ← kernel ← shell`；`local` 独立；examples 只用公开插件 API | 守住 | ArchUnit 19 条规则全过（`valueDependsOnNoOtherLayer`、`kernelDoesNotDependOnShell`、`localStandsAlone`、`examplesOnlyUsePublicPluginApi` 等），全量回归里跑 |
| 内核纯函数：不做 I/O、不读时钟、不起线程 | 守住 | `kernelDoesNoIo`、`kernelDoesNotReadTheClock`；kernel/value 测试零 mock（grep 为 0） |
| shell 的包依赖单向、无循环 | 守住 | `jdeps` 对 `tantalum.shell.*` 120 条包间依赖做互依赖检查：0 对。扇出最大是 `boot`（22，装配本来就要认识所有人）和 `web`（14） |
| 只有 `boot`、`web` 读全局配置 | 守住 | `TantalumProperties` 没有在别的包出现 |
| 插件只经 `PluginGate` | 守住 | 执行前 `PluginGate.runTool`、执行后 `gate.afterTool`；唯一的 `catalog.find` 在 `ToolHandler` 里只是按名字找实现 |
| 密钥只从环境变量读 | 守住 | `System.getenv` 只在 `boot`（`UpstreamProxy.fromEnvironment`、`McpSettings.from`）和 `UserBrowser`（给 Playwright 传环境）出现；路线的 `api-key-env` 只存变量名 |
| 第三方库圈在包里（Playwright→browser、jsoup→fetch、MCP SDK→mcp）；联网只走 `Egress` | 守住 | 对应 ArchUnit 规则 |
| journal 一步一行、旧行逐字节不变 | 守住 | 每个 spec 都用“旧行逐字节相同”的期望文件守着；本周加的字段全是可空、不写出 |
| 模型请求逐字节不变 | 守住 | `claude-requests.txt` 等 5 个期望文件；本周只新增段，旧段未变 |

结论：分层、纯内核、单一入口、密钥边界这些“硬”约束一条都没破。一周 10 万行没有把架构冲垮，靠的是 ArchUnit + 期望文件这两道机械闸，值得继续坚持。

## 2. “补丁式”代码：有，集中在六处

“补丁式”的判据：为了不改已有形状而把新东西塞进旧字段；同一条规则在两处各写一遍；为兼容旧数据加的可空字段没有归属；一个类因为“顺手加一点”长到看不清职责。按影响大小排。

### 2.1 同一条规则写在内核和 shell 两边（中）

| 规则 | 内核里 | shell 里 |
|---|---|---|
| “不是用户写的”前缀文字 | `projection/ContextUpdate.PREFIX` | `model/ToolChanges.PREFIX`（字面量复制） |
| 什么算 system 更新消息 | `projection/PromptProjection.isSystemUpdate` | `model/HookTexts.isUpdate`（同一函数体） |
| 并进用户消息的折叠 | — | `ModelChanges.fold`、`HookTexts.fold` 两份几乎相同的循环 |
| 插件“必须开启”的判断 | `HookChain` 自己持有 `required` 集合 | `PluginGate.pluginEnabled` 也判 `mandatory`；内核又靠 shell 记进 `SessionContext.activePlugins` 的名单过滤 |
| 工具的调度元数据（名字、服务端、并发） | — | `KernelFactory.safety` 用 `null` 依赖再 new 一遍所有工具（`BuiltinTools.of(null)`、`LocalTool.all(null, null, …)`、`new WebFetchTool(null)`）只为拿到名字和并发数 |

方向：
- 把“消息分类”这类纯规则放到 value（`Message.isSystemUpdate()`、`ContextUpdate.PREFIX`）让两层共用；`fold` 合成一个 `MessageFolding`（一次遍历，按块类型决定并到哪）。
- 工具拆成“描述”（`ToolSpec` + 调度属性，纯数据）和“实现”；`ToolSafety` 只吃描述，`KernelFactory.safety` 就不用造假对象。
- 插件开关只在一处判：`HookChain` 不再持有 `required`，只信 `activePlugins`（它本来就是 shell 按 `mandatory`/`required` 算好的）。

### 2.2 为了不改 journal 形状而复用旧字段（中）

- `HookDecided.reason` 在 `ASK` 时放的是问题 id（`HookChain.java:101` 注释明说“不给 HookDecided 加字段，旧记录的形状不变”）。读的人要靠注释才知道这个字段是什么。journal 已经有“可空字段不写出、旧行不变”的机制（`EventCodec` 的 `NON_NULL`），加一个 `question` 字段代价为零。
- `Compacting.patch` 永远是 `NONE`，只为读旧行留着（hook-patch 留下的）。可以用 `@JsonInclude(NON_DEFAULT)` 不写出、解码时忽略，字段从记录里删掉。

### 2.3 可空“兼容旧会话”的字段在增多（中）

`SessionContext` 8 个字段里 4 个可空（`initialTools`、`activeTools`、`activePlugins`、`hooks`），`SessionState` 14 个字段 8 个 `withX`，`RunState` 14 个字段 11 个 `withX`，`ContextAssembled`、`UserPrompt`、`FollowUpSubmitted`、`ModelReplied`、`CompactionSettings` 各有“旧事件为 null”的字段。每个单看都合理，加起来就是：读一个记录要记住“null 在这个字段上是什么意思”。BACKLOG 已经排了“拆 `RunState`、`SessionState`”，建议提前到 Super-Agent 之前，做法：
- 可空的“旧数据”语义收进一个方法（如 `SessionContext.toolActive`、`hookState()` 已经这么做了），字段本身不对外暴露。
- `RunState` 按阶段拆成子记录（`Compacting`、`Work`、`Model` 三组），`withX` 跟着分组。

### 2.4 大类（中）

| 类 | 行数 | 问题 |
|---|---|---|
| `journal/RecordCodec` | 922 | 五件事在一个类里：会话差量、运行差量、文本切片、运行引用压缩、指纹。各自有独立的编解码对，可以拆成 `SessionDelta`、`RunDelta`、`TextSlices`、`RunRefs` 四个包内类，`RecordCodec` 只做编排 |
| `boot/Engine` | 501（构造函数约 190 行） | 装配全部 40 多个协作者。不是逻辑问题，但每加一个功能都改它。可以按“存储 / 工具与插件 / 管线 / 查询”分成四个装配步骤（各一个包内类返回一个记录），`Engine` 只串起来 |
| `net/Egress` | 696 | 代理协议、地址检查、连接转发、日志在一起；至少把 HTTP CONNECT/明文代理的协议解析拆出去 |
| `model/OpenAiClient` 451、`model/ClaudeRequest` 410 | 各自 400+ 行，且共享的概念（附件说明、工具变化、换模型说明、钩子说明）分别实现 | 见 2.1 |
| `web/ApiController` 380 | 提交接口一个方法 100 行（附件、模型选择、校验） | 提交体解析拆成 `SubmissionRequest` |
| `boot/TantalumProperties` 380 | 12 个配置记录 | 可接受，但校验逻辑和默认值散在各记录里，`application.properties` 又写一遍默认值（`Limits` 的 10/8 两处） |

### 2.5 注释依赖 spec 编号（低但普遍）

主代码里 551 处像 “（core-tools C14）”“model-picker M12”“one-line-journal J7” 这样的引用，`ApiController` 23 处、`Engine` 19 处。spec 文档在 `.spec-workflow/` 里，不会跟 jar 一起发；外部贡献者读不懂。这是“spec 驱动开发”的副产品，不是功能缺陷，但 1.0 前要清：把编号换成规则本身（一句话说明为什么），编号只留在 `tech.md`。可以逐包做，不急。

### 2.6 内核 SPI 带着控制台的东西（中）

`KernelHook`（纯内核的插件接口）上有 `description()`（三语文案 `LocalText`）、`plugin()`、`mandatory()`、`defaultEnabled()`：前者是插件页的展示，后三者是开关策略。回放的钩子回调和插件页元数据绑在一个接口上，意味着控制台改文案要动内核 SDK。方向：拆成 `KernelHook`（只有回调）和 `PluginInfo`（名字、说明、开关策略），shell 的 `PluginGate` 吃后者。ocr 也独立报了这一条。

### 2.7 其他小的

- `ContextSteps.contextAssembled` 用 `justCompacted` 这种“看上一步状态猜”的布尔值决定下一步（`before.context() == null && before.compaction() != null`），再传给 `callModel(…, compactionAllowed)`。应该由压缩完成的那一步明确在 `RunState` 上记“本运行不再自动压缩”。
- 控制台 `api/types.ts` 725 行是所有接口类型的总表，和服务器端的记录手工对照，没有生成；每加接口两边各写一次。暂时可接受，接口稳定后考虑从 Java 记录生成。
- 本周 9 个 `fix:` 提交里 5 个是 windows-server 的 CI 迭代，1 个是 waiting-state 收尾（“只数真正关掉的运行”）——后者说明启动恢复路径的测试覆盖还薄。

## 3. open-code-review 扫描

### 3.1 试扫（value + kernel，19 个文件扫完后到预算上限）

224 万 token（输入 217 万，其中缓存写 150 万、缓存读 68 万；输出 6.9 万），1 分 53 秒，约 7 美元。50 条评论：6 high、22 medium、22 low。逐条核对后：

**值得修（6）**
1. `Kernel.step`：步骤函数抛异常而 `run == null` 时直接返回 `unchanged`，没有事实、没有日志、journal 里没痕迹（`Kernel.java:49`）。建议报一条 `StepFailed` 事实。
2. `Compactor.isUserText` 要求每块都是 `TextBlock`，带附件的用户提问不算“当前请求”，摘要会拿更早的纯文字提问当请求（`Compactor.java:188`）。
3. `HookChain` 把钩子异常的 `toString()` 写进 `HookReported` 事实，进 journal 和 SSE；第三方钩子的异常消息可能带路径、令牌（`HookChain.java:54`）。建议只记异常类名，消息进服务器日志。
4. `HookPatches` 的变化检测只比合并后的整串：一个钩子的文字变了，所有钩子的说明都重发一遍（`HookPatches.java:53`）；跳过时的 `HookReported` 每圈重复（`:78`）。按钩子分别比较。
5. `ContextUpdate.isUpdate` 只靠文字前缀识别合成消息，用户消息以同样文字开头会被当成系统更新（`ContextUpdate.java:19`）。用块类型标记，不用前缀。
6. `KernelHook` 接口混入展示和开关元数据（同 2.6）。

**便宜的加固（10）**：`Trimming` 对 `\d+` 用 `Long.parseLong`（19 位以上数字抛异常，整个压缩步骤失败）；`TokenEstimate` 对 `BigDecimal.toPlainString()` 没有上限；`ContextDiff` 的 `toMap` 没有合并函数（同名段抛异常）；`HookChain` 里 `hook.plugin()`/`name()` 在 try 外；`HookPatches` 没检查负数 `systemChars`；`Limits` 不校验 ≤ 0；`PromptProjection.project` 和 `placeSystemUpdates` 的调用约定只在一处生效；`HookDecided.PATCH` 在 `beforeModelCall` 以外的位置也报“已应用”；`SummaryPrompt` 的大小判定没算 `<files-touched>` 块；`UsageProjection`/`ToolsGone` 的空值。

**不成立或可忽略（约 30）**：`ImageBudget` 按图片 id 省略是有意的；`HookChain` 开关过滤“可能跳过必须开启的钩子”——名单由 `PluginGate` 按 `mandatory` 算好，内核只信名单（但这正是 2.1 第四行“规则写两处”的来源）；一批 O(n) 性能和文档措辞的 low。

### 3.2 核心 63 个文件（内核全部步骤 + shell 关键类 + 沙箱 + 连接程序）

750 万 token（缓存写 414 万、缓存读 302 万、输出 35 万），9 分 45 秒，约 22 美元；两次扫描合计 975 万 token、约 30 美元。274 条评论：1 critical、21 high、13 medium/security、107 medium/bug、其余 low。我按代码逐条核过 high 和 security，medium 看了全部摘要，分三档。

**A. 真缺陷，要修（按模块；括号里是 ocr 的编号或文件:行）**

管线与 journal（最要紧，这是“落盘才生效”的根）
- `Intake.publish` 先 `ledger.claim` 再 `receipts.expect`：重复提交在这个空当到达会拿到一个已完成的回执、得到 200，而原提交随后可能被 429/503 拒掉——客户端以为存了其实丢了（#15、#16）。`Receipts.forget` 删条目不完成 future，拿到它的重复请求永远等（#17）；关机或管线停住时在途回执也不收尾（M84）。修法：回执先登记再放出 claim，失败一律以 `NotSaved` 完成。
- `UserLane.drain/process` 没有兜底 catch：任何 RuntimeException/Error 杀掉虚拟线程而 `running` 仍为 true，这个用户的通道到重启前都死了；pin、名额、done 回调一起漏（#18、M89–M92、M82）。
- `RecordCodec.encodeTree` 边编码边改 memo（runs、context、文本切片表），写失败时 memo 已经领先于文件，之后的行会引用不存在的东西（M56）；`UnknownFact.raw()` 的节点被原地改写（M55）；`SessionFile` 写失败后句柄继续用，完整行跟在残行后面（#13）；`SessionFileReader.Lines` 缓冲区无上限（M65）。
- `Cleanroom` 的状态和视图先推进、再交给 Release，中间抛异常时读的人看到了没落盘的状态（M76）；载入失败只在 `run == null` 时发 `LoadFailed`，属于已有运行的迟到结果直接丢（M78）；`Residency.failure()` 和 `pin()` 各读一次时钟（M86）、FAILED 条目没有回收（M87）；`UserPermits` 停机后停住的事件永远不出来（M97）。
- `Storage.findSubmission` 查库失败就当新提交，去重失效（M40）；`EventCodec` 语言转换写死三种语言，新加语言会无声丢数据（M54）。

内核
- `SubmitSteps`：排队的提问各自带模型选择，但会话只有一个 `followUpModel` 槽，后来的覆盖先来的，`RunEnd` 又只给第一条——排队两条以上时用错模型（#4）。要把选择存进每条排队消息。
- `Kernel.step` 在 `run == null` 时吞掉异常不留痕；`CancelSteps.commandDropped` 不核对命令属不属于当前阶段就结束整个运行（M7）；`RetrySteps.timerFired` 不看是哪个定时器（M15）；`ToolSteps.toolFinished` 不查槽位状态，重复的结果会覆盖（M16）；`ModelSteps` 回复因 MAX_TOKENS 截断但带工具调用时仍去执行可能被截断的参数（M11）；`Outcome.enter` 用调用者的快照覆盖同一步里更新过的运行（M12）；`ContextSteps` 第一次冻结时 `active` 没和全集求交（M9）；`Resume.continueWithText` 丢了 `work`（M14）。
- `PluginGate.HOOK_POINTS` 手抄的钩子点漏了 `onAnswer`，只实现它的插件在插件页上没有钩子点（M24）；after-tool 钩子抛异常时保留未过滤的输出，等于 fail-open（M25）。

模型客户端
- 两个客户端的看门狗都通过普通数组槽 `open[0]` 传流对象，没有 happens-before；首块超时发生在流对象赋值之前时，看门狗关了个 null 就退出，之后块间超时再也没人管，流一停就永远挂住（#19、#20）。
- `ClaudeClient`：没见过的块类型（server_tool_use 等）都变成空 text 块、回放时 400（M100）；没拿到 signature 的思考块以空签名发回（M101）；孤立的 thinking_delta 当文字（M99）；只跳过空串不跳过全空白的文字块（M102–M104）；思考块原样 JSON 强转没检查（M105）。
- `OpenAiClient`：成功路径不关响应体（M107）；200 之后流里的 `{"error":…}` 不认（M108）；没有 `index` 的并行工具调用全并成一条（M109）；错误体 4 KB 原样进 journal（#33）。

登录与接口
- `AuthController.changePassword` 的 `deleteOthers` 不关别处的 SSE，改完密码另一台设备还在收事件直到下一次心跳（#35）；`resetPassword → restamp → deleteOthers` 三步不原子，中间本次登录也是无效的（M117、M33）。
- `LoginContextRepository`：`touch` 写失败就当没登录（M29）；`issue` 里 `sweep` 失败阻断登录（M30）；Cookie 的 Max-Age 固定 12 小时，服务器端却是滑动窗口（M32）；没有绝对上限（M34）。
- `EventStreamController.pump` 非正常退出不 `complete` emitter，连接挂着不断、浏览器不重连（#23、M118、M119）。
- `SecurityConfig.OriginFilter` 没有 Origin 头就放行（#29），反向代理后 `getServerName` 比对会错（M35）；登录体先整个读进内存再查大小（#36）。
- `UserStore`：Argon2 在全局锁里算，并发登录把 `status` 也堵住，可被用来拖慢整个服务（#5）；最后一个管理员的检查是 check-then-act，命令行和服务器两个进程同时操作能把管理员清零（#6、M36、M37）。
- `UsersController.update` 两步不原子（M120）；`ApiController` 的 `run` 路径变量不校验格式（M116）。

联网与本机工作区
- `AddressRules` 永远开着的“自己的端口”守卫只还原 `::ffff:` 映射，NAT64/6to4 内嵌的 IPv4 能绕过（#21）；网卡枚举返回 null 或抛异常时 fail-open（M110、M111）。
- `Egress`：读请求头超时后丢掉已读字节、把剩余当新头解析（#22）；头超时按每次读算，慢速客户端能占满 512 个槽（M113）；上传中途停顿 10 秒就断（M112）；`close()` 和新连接有竞争（M114）。`Fetcher` 的 DNS 解析不计入期限、不可中断（M46）。
- `LocalLinks.attach` 先发 welcome 再登记，对端在空当里断开会留下一条死连接并顶掉健康的旧连接（#14）；到期定时器登记前的空当（M71）；`verify` 撤销停用账号时不走锁、不结束已有连接（M72）。`LocalEndpoint` 的 hello 定时器和 attach 有 check-then-act（M69）。
- 连接程序：`Link.stop()` 和新会话的竞争会让 `run()` 永不返回（#2）；握手完成前的 `end()` 不关 socket（#3）；重复的调用 id 被无声丢掉（M1）；只 catch `Exception`，Error 让 id 永远留在 running（M2）；`calls.context` 的线程没有错误处理（M5）。`--allow-read` 不做真实路径和“太宽”检查（#25）；令牌可以明文发到非回环地址（#26）；`MxcPolicy` 的家目录拒绝规则没用真实路径（#27）。
- 浏览器：`Browsers.close()` 遇到失败的槽位抛出、剩下的 Chromium 不关（#10）；清扫线程只 catch InterruptedException（#11）；`UserBrowser.open` 只处理 PlaywrightException，其他异常漏掉驱动进程（#12）；`newTab` 会抢别的会话的标签页（M44）。

装配与关机
- `Engine` 构造函数失败时只关 routes、web、storage，`localTokens`、`switches`、`userSettings`、`mcp`、`memories` 都漏；环启动之后再失败就没人关了（#7、#8）；启动时 `interrupt()` 的 `tryPublish` 循环没有上限也不看环是否已坏（#9）；关机时 `awaitIdle` 的结果被丢掉（M39）。

异常文字进 journal / SSE / 模型（一条规则多处违反）
- `HookChain`、`ModelHandler`、`ToolHandler`、`OpenAiClient`、`UserLane.loadOne` 都把 `failure.toString()` 或 `getMessage()` 写进事实、事件或工具结果（试扫 3、#31、#32、#37、M94）；连接程序 `Calls` 把本机异常消息发给服务器（#24）。违反“密钥和路径不进 journal、SSE、模型上下文”的规则。修法：一个 `Failures.describe(Throwable)` 只给类名加固定说明，原文进本地日志。

**B. 架构 / 补丁味（和第 2 节同一来源，ocr 独立报出）**
- `CompactSteps.notAdopted` 用三个 switch 把“原因 × 阶段”交叉起来，混着裁剪、上报、模型回滚和结束运行（M8）；`ModelSteps.callModel` 的 `compactionAllowed` 布尔和 `RunState.autoCompactionFailed` 是同一件事的两套表达（M10；同 2.7）。
- `Fetcher` 把 `resolved`、`plain` 两个布尔穿过 `get/limit/failure` 四个方法、各处重新解释（M47）。
- `RecordMemo` 一个类当三种模式用（writer/reader/blind），`block()` 在 writer 上 NPE、`position()` 在 reader 上恒 -1（M60）。
- `Engine` 构造函数里局部变量 `ExternalGate gate` 遮住了字段 `PluginGate gate`（M38）。
- `TransitionTable` 构造后仍可改（M18）；`AfterToolHook`、`WorkType` 没法声明 mandatory/defaultEnabled（M26）；`KernelHook` 混入展示元数据（同 2.6）。

**C. 不成立或可忽略**
- 唯一的 critical（`LocalEndpoint` 握手把结果放进共享的 `ServerEndpointConfig.userProperties`，并发握手会串用户）**不成立**：嵌入式 Tomcat 11 每次握手都复制一份 `WsPerSessionServerEndpointConfig`（jar 里能看到这个类），属性是每连接一份。但建议补一个并发握手的测试把这件事钉住，换容器就会出事。
- `ImageBudget` 按图片 id 省略是有意的；`TailSequence` 的非原子更新有“每用户通道串行”的约定保护（M67）；一批 O(n) 性能和文档措辞的 low。

## 4. 建议的处理

开一个 `review-fixes` spec，排在 Super-Agent 方向之前，分五个阶段、每阶段一次全量回归、不用真实模型（用脚本化模型和录制的流）：

1. **管线与 journal**：A 组“管线与 journal”全部 + 内核的 `Kernel.step`、`Outcome.enter`、`CancelSteps`、`RetrySteps`、`ToolSteps`、`ModelSteps` MAX_TOKENS、`SubmitSteps` 排队模型。这是最可能悄悄丢数据的一组。
2. **模型客户端与流**：看门狗改成 `AtomicReference` 并在流发布后补查过期；Claude 流的块类型、空签名、空白文字；OpenAI 的关流、流内错误、无 index 合并；错误体脱敏。
3. **登录与安全加固**：`Receipts`/`Intake` 之外的 A 组“登录与接口”“联网与本机工作区”里的安全项，加上统一的 `Failures.describe`、`OriginFilter` 补 `Sec-Fetch-Site`、登录体先看 Content-Length、密码最短长度、`AddressRules` 内嵌 IPv4、连接程序非回环明文拒绝。
4. **去重与归位**：第 2.1、2.2、2.6 节；`PluginGate.HOOK_POINTS` 改成从 `KernelHook` 方法派生；`compactionAllowed` 并进 `RunState`；`notAdopted` 按原因拆成三个小函数。
5. **拆大类与清注释**：`RecordCodec` 四拆、`Engine` 装配分步、`RunState` 按阶段分组；spec 编号注释逐包换成规则本身。

不建议做的：为了“好看”改 `ApiController`、`TantalumProperties` 这类边缘的大类；控制台类型生成；再跑一次 ocr（额度只剩约 10 美元，留给以后必要的真实模型验证）。

原始结果：`scratchpad/ocr-kernel.json`（试扫 50 条）、`scratchpad/ocr-core.json`（核心 274 条），已复制到 `.spec-workflow/reviews/ocr-2026-11-07/`。
