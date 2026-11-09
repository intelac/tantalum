# QAgent / Tantalum 设计简报（交给 Claude Code 实施）

> 来源：Yi 与 Claude 于 2026-10-30/31 的架构讨论整理。
> 用途：作为底座的设计约束与实施路线。实施前先通读，再读现有代码，再产出差距清单。
> 命名：项目拟定名 **Tantalum**（元素 Ta：耐腐蚀、存电荷）。裸句柄已被占，实际形态为带后缀：GitHub org `tantalum-rt` / `tantalum-dev`，Maven groupId `dev.tantalum`（取得域名后）或暂用 `io.github.<handle>`。代码中现有的 `QAgent` 标识在 Yi 确认域名/org 后统一更名；更名前不要动。

---

## 0. 给实施者的工作方式

1. 先读本文档全文，再读现有代码（入口：`ApiController`、`Input Disruptor`、`Kernel.step`、`RunSteps`、`ToolRound`、`Phase`、`loop/` 下六个等待状态、hooks、各 Handler、`SseHub`、journal）。
2. 对照第 2 节的不变量和第 9 节的检验清单，产出一份**差距清单**（现状 vs 本文档），按第 10 节的阶段排序。
3. 每个阶段先写测试（第 8 节），再改代码。
4. 遇到本文档未定义的决定，列入第 11 节"待决问题"，不要自行决定架构级问题；实现细节可以自行决定但要在 PR 说明里写出理由。

---

## 1. 项目定位

- **是什么**：生产级、通用的 Agent 运行时**底座**，供他人在其上开发插件和垂直 Agent。
- **不是什么**：不是开箱即用的 agent，不是 personal agent，不是编码 agent。
- **语言/生态**：Java（现有代码即 Java）。Pi Durable（TypeScript）只作为**参考实现**，不移植代码，采纳其词汇和语义。
- **首个验证场景**：COBOL→Java 迁移类长任务（跑数小时、扫大代码库、SIer 客户要审计记录、中途要人工确认）。底座的价值必须由一个跑在上面的真实垂直 Agent 证明。
- **底座真正要兑现的四个承诺**：
  1. 运行能跨越进程生命周期（崩溃/重启后接着跑）
  2. 一个进程服务多用户，互不影响
  3. 出事能拿 journal 重放，精确看到每一圈发了什么 prompt
  4. 插件搞不坏内核

---

## 2. 架构不变量（已锁定，不再重评）

这些是和"内核单线程"同级的决定。改任何一条都是架构变更，需要 Yi 批准。

| # | 不变量 | 说明 |
|---|---|---|
| I1 | 内核单线程、不做 I/O | 所有 I/O 在处理器（Handler）里；内核只查表、应用结果、发命令 |
| I2 | 事件先落盘再处理 | journal 是事实来源；状态可从 journal 重建 |
| I3 | 等待状态显式 | 每个 `await` 变成"发命令 + 进入等待状态"，不用 `while(true)` |
| I4 | 环上任何一段不得阻塞 | 见第 4 节 |
| I5 | 内核钩子是纯函数 | 签名无 Promise/异步、无网络句柄、无时钟；见第 5 节 |
| I6 | 扩展 = 追加，不改内核 | 新功能通过注册新事件/命令/处理器/表行实现；内核目录 diff 为零 |
| I7 | 钩子集合与事件格式版本化 | 1.0 前锁死钩子集合；事件类型一旦写入 journal 不删不改语义，新版本是新类 |
| I8 | 强制插件不经过激活过滤 | 额度、审计、安全类插件永远在线；fingerprint 只决定可选插件 |
| I9 | 包依赖方向 value ← kernel ← shell | 三层分类见第 2.5 节；反向引用一律禁止，由 ArchUnit 测试守住 |

**已明确可换的实现细节**（不是架构选型）：
- Disruptor 可换成普通有界队列（吞吐量下无收益）。换不换由实施者评估迁移成本后决定，不强制。若保留，用 `tryPublishEvent`（非阻塞）。

**已明确不做的**：
- 不做运行时动态拼图（插件往图里加节点/边，LangGraph 式）。
- 不做跨进程/水平扩展。老实声明"一个进程拥有一份存储"，分区承诺从架构图上去掉或标注为未实现。

### 2.5 三层分类：value / kernel / shell

面向对象，全部代码归入三个顶层包（Functional Core / Imperative Shell，value 单独成层）。

```
value  ←  kernel  ←  shell
```
- `value`：不依赖任何其他层。全部不可变对象：Java `record` + `sealed interface`。
- `kernel`：只依赖 `value`。纯逻辑，接口只收 value、只返回 value。
- `shell`：依赖两者。一切 I/O、线程、框架组件。

| 层 | 放什么 | 现有代码对应 |
|---|---|---|
| **value** | Event、Command、State 树（Principal/Session/Run）、Awaiting 载荷、钩子返回值 `Allow/Stop/NeedWork`、Document、PluginManifest、cmdId/requestId、Task 所有权记录 | 事件、命令、state |
| **kernel** | `Kernel.step(state, event) -> Decision(newState, commands)`、转移表与 Step 注册表、内核钩子接口与遍历、状态投影（事件→状态）、所有权树的取消/等待逻辑、**journal 回放**（`Replay`） | Kernel.step、RunSteps、Phase、loop/、hooks |
| **shell** | 两个队列与**调用内核的那根线程**、`JournalWriter`、全部 Handler、SseHub、ApiController、Registry 加载/热替换、时钟、处理器侧钩子、Document 存储、SelectPlugins 匹配 | Disruptor、Handler、SseHub、ApiController |

**关键修正**：现有架构图把"单线程"画在内核里。按本分类，`Kernel.step` 只是纯函数；从输入队列取事件、调 step、写 journal、推命令到输出队列的循环是 shell 的事。内核不知道线程存在。

**kernel 层禁止出现**：`java.io.*`、`java.net.*`、`java.sql.*`、`java.time.Clock`、`Instant.now()`/`System.currentTimeMillis()`、`CompletableFuture`/任何异步类型、Spring 等框架注解。`Instant` 作为 value 的数据字段允许；取当前时间只能在 shell 做并塞进事件。

**journal 分两半**：写入是 I/O → shell（`JournalWriter`）；回放是纯函数（事件序列→状态）→ kernel（`Replay`）。

**钩子两族落到两层**：内核钩子接口在 kernel（由 `Kernel.step` 调用）；处理器侧钩子接口在 shell（由 Handler 调用）。两族接口不在同一个包，物理隔离。

**插件 SDK 按同样三层切**：
- `tantalum-value`：插件与内核共用的类型
- `tantalum-kernel-api`：插件实现纯钩子、注册 Step/表行的接口——签名里只有 value，插件作者想写 I/O 也写不进去
- `tantalum-shell-api`：插件实现 Handler、Work 类型、处理器侧钩子的接口

一个插件自己也是三个包：它的事件/命令/Document 在 value，纯钩子在 kernel，Handler 在 shell。

**测试策略随之确定**：value 与 kernel 的测试零 mock、零线程；shell 的测试是集成测试。TheLoopTest 不启动线程，直接喂事件序列断言 Decision。

---

## 3. 内核：三状态 + 可注册转移表

### 3.1 状态压缩

现有六个 `Awaiting*` 是"同一个状态 × 六种载荷"。内核真正区分的只有三个：

```
Deciding                      // 刚拿到事件，正在决定下一步
Awaiting(cmdIds, resumeAt)    // 已发命令，等结果；resumeAt 是步骤名（字符串）
Ended(reason)                 // Completed / Failed / Cancelled
```

内核固定转移：
- Deciding → 发命令 → Awaiting
- Awaiting + 对应 cmdId 的结果事件 → Deciding
- 任意状态 + Cancel → Ended（先向下传播取消，见第 6.1 节）

### 3.2 转移表是数据，不是 switch

```
Map<(stateName, eventType), StepFunction>
StepFunction: (state, event) -> { newState, commands[] }   // 纯函数
```

- 状态名、事件名、命令名均为字符串（或注册的类型），**不是封闭枚举**。
- 内置八行（下表）在启动时注册；插件注册时可加行。
- 冲突规则：同一 `(state, event)` 只允许一个处理函数；插件撞内置行 → 注册失败报错。
- journal 存步骤名，回放时名字还注册着就能续。

内置行（现状，供映射）：

| 当前状态 | 事件 | 下一状态 | 命令 |
|---|---|---|---|
| AwaitingContext | ContextAssembled | AwaitingModel | CallModel |
| AwaitingModel | ModelReplied(有 tool_use) | AwaitingTools | RunTools |
| AwaitingModel | ModelReplied(无 tool_use) | AwaitingWork | DoWork |
| AwaitingTools | AllToolsDone | AwaitingModel | CallModel |
| AwaitingModel | ModelFailed | AwaitingRetry | StartTimer |
| AwaitingRetry | TimerFired | AwaitingModel | CallModel |
| AwaitingWork | WorkDone | Ended | — |
| 任意 | Cancel | Ended | — |

实现上这些名字可以保留为 `resumeAt` 的取值；重点是把 switch 改成 Map 查找、枚举改成注册。

### 3.3 Step 的两级扩展 API

- **90% 的插件**：只注册 Work 类型（走 AwaitingWork/WorkHandler）或在钩子处回答问题。它们不知道 Step 存在。
- **10% 的流程插件**：注册 Step（表行），改变运行走法。高级 API，单独文档、单独审核。

---

## 4. 环上无阻塞（背压）规则

环：输入队列 → 内核 → 输出队列 → 处理器 → 输入队列。两端有界且阻塞会死锁。规则：

1. **内部反馈路径自然有界**：每个运行同一时刻最多一条在途命令，所以在途命令数 ≤ 活跃运行数。输出队列容量 ≥ 最大活跃运行数，内核永不在输出上阻塞。
2. **输入队列分两通道**：内部结果事件永不拒绝；外部用户事件（提问/插话/撤销）在 Web 接口入口按**每用户在途事件数**限额，超了直接 429，不排队。
3. 内核写输出队列、处理器回写输入队列均用**非阻塞 offer**；失败视为不变量被破坏 → 记日志 + 报警，不等待。
4. 以上三条写进设计文档并有测试覆盖。

---

## 5. 钩子：两族、固定位置、纯净约束

### 5.1 内核钩子（纯，能决定流程）

```
(state, hookContext) -> Allow | Stop(reason) | NeedWork(command)
```
- 无 async、无网络、无时钟（时间从事件里取）。
- 插件想做 I/O 只能返回 `NeedWork` → 内核发命令进 AwaitingWork → 插件的 I/O 在 WorkHandler 跑 → 结果事件回来再继续。代价 = 多转一圈，可接受。
- 钩子需要的数据有三种来源：
  - **投影进状态**：如每日用量从 `ModelReplied` 的 token 累加到 PrincipalState；钩子只读状态。接受"纯"意味着数据是上一事件时的快照。
  - **推迟执行**：如引用核对，返回 NeedWork。
  - 需要外部实时真相的检查只能走推迟执行。
- 位置（1.0 前锁定）：`beforeModelCall`、`beforeToolCall`、`onFailure`、`beforeRunEnd`。可按 Pi Durable 补：`afterModelReply`、`afterTools`、`onYield`（回答后决定是否继续）、`beforeCompact`。

### 5.2 处理器钩子（可 I/O，只改数据不改流程）

- `afterToolExecution`（截断/脱敏/整理输出）等运行在 Handler 内。
- 必须在文档里明确标为处理器钩子，不能混入内核钩子列表。

### 5.3 钩子里放什么（指导）

| 类 | 例 | 返回 |
|---|---|---|
| 策略 | 额度、权限、只读、审批 | Stop / NeedWork |
| 变换 | 注入 system prompt 片段、注入工具定义 | Allow + 数据 |
| 观察 | 指标、审计、推进度 | Allow |
| 控制 | 可否重试、结束前补一步 | NeedWork / 改流程 |

钩子每次模型/工具调用都跑，必须便宜；绝大多数是一行判断返回 Allow。

---

## 6. 从 Pi Durable 借鉴的语义（按优先级）

### 6.1 所有权树（最高优先级）

- 每个任务（Task）都有所有者：它的运行/会话，或另一个任务。工具调用 = 由 generation 拥有的任务；子 agent = 由工具任务拥有的运行（会话）。
- 等子任务 = 提交 `waiting` 状态：`on: [taskIds], policy: allSettled | failFast`，等待期间不跑代码。
- `completing` 状态：自身结果已定，但要等拥有的工作全部结束才 terminal。
- **取消自下而上**：先终止拥有的工作，自己的 abort 处理器在那之后跑。
- `background: true` 任务是边界：父取消不影响它，也不让父保持 busy。
- 效果：不再需要专门的 AwaitingChildren；AwaitingTools 和等子 agent 是同一件事——"等我拥有的任务"。
- 子 agent 起始复制所有者的 agent 配置（模型、扩展、工具、cwd），可在创建时覆盖。

### 6.2 工具/处理器的重放策略

- 调用**意图先提交**（journal），再执行。
- 工具声明 `replay: safe | unsafe`（默认 unsafe）。进程死在调用中间：
  - safe → 重启后重跑；
  - unsafe → 不重跑，模型收到 `interrupted` 错误结果（附已提交的部分输出），由模型决定查外部状态/补偿/请人工。
- 每条命令带唯一 ID；处理器能识别"做过了"。
- 外部提交带 `requestId`：重试同一 requestId 返回已有 submission，不重复接纳。
- 嵌套调用（工具调工具）按 key 重放：replay-safe 的调用者重跑时按相同 key 找回已有的嵌套调用而不是重新发起。
- 第 3 节的 Work 类型同样声明 replay 策略。

### 6.3 插件状态：Document

- 插件状态不能塞进内核状态树。提供 **Document**：类型化 JSON，与事件在**同一个 commit** 里提交。
- Document 定义：`kind`、`version`、`scope`（会话/运行/用户）、`fork` 语义（`initial | current | asOf`）、`initial()`、`migrate()`。
- 内置 Document（对应 Pi Durable）：`agent`（模型、扩展选择、工具、指令、cwd）、`live`（运行中的生成与工具）、`inbox`（排队的提交）、`usage`（用量）。
- 状态策略：**内核状态从事件投影；插件状态用同 commit 的 Document**。插件的工具/钩子通过 API 读自己的 Document，缺失视为默认值。

### 6.4 插话（Inbox）语义

忙时提交的四种行为：
- `steer`：当前工具轮之后插入，加入运行中的工作
- `follow-up`（默认）：回答后开始下一轮
- `write`：只追加条目不问模型，立刻追加，永不排队
- `reject`：忙则抛错

运行失败后队列中的输入：`keep`（默认）| `continue` | `withdraw`。可撤回排队的提交。

### 6.5 睡眠入存储

- 重试退避/定时等待存成 `{ status: sleeping, until, checkpoint }`，不占内存计时器；重启后到点从 checkpoint 继续。
- phase 中间不能睡：等待之后的工作放下一个 phase，需要的数据放 checkpoint。
- 提供 `onWake` 回调：告诉宿主最早唤醒时间，宿主可自行设闹钟（为可被驱逐的宿主预留）。
- 所有打开同一存储的进程必须用同一时钟；时钟可注入（便于测试用假时钟）。

### 6.6 崩溃循环停车

- 任务被崩溃/重启切断的次数达到 `restartLimit`（默认 5，连续且无新 checkpoint）→ `parked`：不算活跃、不自动重跑，通过 `onReport(task_parked)` 上报，提供 `retryTask(id)` 和 `abortTask(id)`。

### 6.7 注册表与版本化

- Registry 是进程内的；journal/会话只存**名字**（扩展名、工具名、步骤名、任务定义名）。
- 热替换：已开始的 phase 用它取到的代码，下一个 phase 用新代码。
- 定义缺失或 `migrate()` 失败的任务保持 `blocked`，不猜新状态；注册后自动解除。
- 事件类型带版本；旧类保留只用于回放。

### 6.8 流式输出两级持久化

- 部分回答、工具输出：内存视图最多每 100ms 更新，观察者立刻可见；存储延后成批写，最迟 `persistWithinMs`（默认 1s）。
- 崩溃最多丢这一窗口。只对"丢了也便宜"的进度数据启用，entry/任务状态/新 Document 仍立即落盘。

### 6.9 提示词 = 位置化 section

- system prompt 由选中插件的 `sections` 按序渲染；section 和工具列表的变化存为位置化的系统条目；只重发变化部分（保持 provider 缓存）。
- 返回值随时变的 section（如当前时间）会破坏缓存，文档中警告。

### 6.10 上下文管理（现有设计的缺口）

- Compaction 做成任务，可后台提前运行；只有下一次请求放不下时才阻塞等待。
- 旧条目留在存储，summary 只是模型看到的入口。
- `beforeCompact` 钩子可拒绝或提供自己的摘要。
- 提供 `reset(handoff)` 开新上下文。
- 按模型配置阈值（`reserveTokens`、`keepRecentTokens`、`backgroundTokens`，支持按模型覆盖）。

### 6.11 错误三规则

1. 存储错误结束整个运行时（Harness），等待方全部拒绝，需重新打开；提交原子所以存储一致。
2. 无效读只失败那一次调用。
3. 用户/插件代码只失败它自己的单元：抛异常的 phase → 该任务 faulted；抛异常的工具 → 错误结果给模型；抛异常的钩子 → 上报并跳过（beforeTool 则拦截该调用）；抛异常的 commit 回调 → 回滚该 commit。

### 6.12 遥测

- 属性只记 ID 和控制元数据，**不记消息、参数、结果**；ID 指向 journal。
- 关键 span：submit、phase（含 resumed、outcome）、checkpoint、replay；指标：phase 时长、活跃任务数。

---

## 7. 插件模型

### 7.1 插件 = 三件套

- **工具**：模型选择调用（`callers: model | tools | both`；`deferred: true` 的工具需经 tool_search 加载后才暴露给模型）。
- **钩子**：固定位置必跑（第 5 节）。
- **Work 类型 / 自定义任务**：插件自己的 I/O 与多步工作，声明 replay 策略。

另有 `sections`（提示词片段）和 `wraps`（按名字装饰别的插件的工具/section）。

### 7.2 加载 vs 激活

- **加载**：插件代码进进程，启动时全加载（或懒加载但在内核外），不按对话做。
- **激活**：本次运行在钩子处问哪些插件、给模型暴露哪些工具。激活集合是 RunState 的字段。

### 7.3 Fingerprint 激活流程

- 在 UserPrompt 之后、组装提示词之前加一步：`AwaitingPluginSelect`（命令 `SelectPlugins`，处理器做匹配，结果事件 `PluginsSelected(ids)`）。
- 匹配三层，由便宜到贵：
  1. 确定性：关键词、正则、环境条件（仓库里有 pom.xml、用户在某 JIRA 项目）
  2. 语义：插件描述 embedding 与对话 embedding 相似度 top-k
  3. 模型选：候选插件一行描述给模型挑 → 做成 `tool_search` 工具让模型运行中自行调用
- 规则：
  - 激活是事件，进 journal；回放不重算。
  - 一次运行内只增不减；运行边界（新 UserPrompt）重算，可收缩。
  - `mandatory` 插件不经过过滤（I8）。
  - 内核不知道 fingerprint 存在，只知道 `activePlugins` 集合并在钩子遍历时过滤。

### 7.4 渐进披露

- `beforeModelCall` 只注入一行工具描述；详细上下文放工具返回结果里。
- 插件审核指标：在 `beforeModelCall` 注入的 token 数；超过几百 token 需解释为何不能推迟到工具结果。

### 7.5 多租户隔离

- 一个用户的运行炸了，其他用户无影响；一个用户塞满输入由入口限额拦截（第 4 节）。

---

## 8. 测试资产

1. **TheLoopTest**（已有）：逐圈断言；改坏回边第 N 圈失败。保留并扩展到表驱动后。
2. **journal 回放测试**：拿真实 journal 回放，断言最终状态一致、每圈发给模型的 prompt 可完整重建。
3. **故障注入**：第 K 圈杀进程再恢复，验证 replay safe/unsafe 语义、requestId 去重、睡眠恢复、park。
4. **背压测试**：输出队列满 / 输入内部通道满时非阻塞 offer 失败并报警；外部通道超限返回 429。
5. **取消传播测试**：父取消 → 子任务先终止 → 父 abort 处理器后跑；background 任务不受影响。
6. **分层守护（ArchUnit）**：把 I9 和第 2.5 节的禁止列表写成测试，CI 必跑：

```java
noClasses().that().resideInAPackage("..value..")
    .should().dependOnClassesThat().resideInAnyPackage("..kernel..", "..shell..");
noClasses().that().resideInAPackage("..kernel..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "..shell..", "java.io..", "java.net..", "java.sql..",
        "java.util.concurrent..", "org.springframework..");
noClasses().that().resideInAPackage("..kernel..")
    .should().dependOnClassesThat().haveFullyQualifiedName("java.time.Clock");
classes().that().resideInAPackage("..value..")
    .should().beRecords().orShould().beInterfaces().orShould().beEnums();
```
   同样的规则套用到插件 SDK 的三个模块。
7. **扩展检验**（第 9 节）作为架构守护测试：每个扩展的实现不得修改内核目录。

---

## 9. 架构守护：五个预期扩展的追加检验

每个扩展写下"加什么、改什么"。只要有"改"落在内核目录里 → 设计有洞，先补。

| 扩展 | 预期加的东西 | 内核应改 |
|---|---|---|
| 子 agent | 工具 + 所有权（6.1） | 0 |
| 人工审批 | beforeToolCall 返回 NeedWork + Work 类型 + Document | 0 |
| 流式输出 | 处理器 + 两级持久化（6.8） | 0 |
| 多模型路由 | onFailure/beforeModelCall 钩子 + agent Document | 0 |
| 按对话激活插件 | 两行表 + SelectPlugins 处理器 + RunState 字段 | RunState 字段 + 钩子遍历过滤（一次性） |

---

## 10. 实施顺序

**阶段 0：盘点**
- 读现有代码，输出差距清单（现状 vs 本文档），标出每项落在哪个阶段。

**阶段 1：最小内核（不加功能，先改骨架）**
- 先做三层拆包（第 2.5 节）：建 `value` / `kernel` / `shell` 顶层包，把现有类归位，加 ArchUnit 测试并让它通过。这一步**只移动代码不改逻辑**，是后续所有改动的前提。
- 把调用 `Kernel.step` 的线程循环从内核移到 shell；`Kernel.step` 变为纯函数。
- 状态枚举 → 字符串/注册；RunSteps 的 switch → Map 查表；Awaiting 加 `resumeAt`/`cmdIds`。
- 背压三规则 + 测试（第 4 节）。
- 内核钩子签名改为 `Allow | Stop | NeedWork`，分离处理器钩子。
- 命令 ID、requestId 去重。
- 事件类型加版本字段。

**阶段 2：持久化语义**
- 所有权树与自下而上取消（6.1）。
- 工具/Work 的 replay 策略与 interrupted 结果（6.2）。
- 睡眠入存储（6.5）、park（6.6）。
- journal 快照策略（每 N 事件或会话结束存快照，回放从快照起）。
- 故障注入测试。

**阶段 3：插件模型**
- Document（6.3）与内置 agent/live/inbox/usage。
- Inbox 四种插话（6.4）。
- sections / wraps / deferred 工具 / tool_search。
- 激活集合 + SelectPlugins（7.3）。
- 插件 SDK + 文档（把事件模型藏在"我提供一个处理器"的心智模型后面）。

**阶段 4：上下文与运维**
- Compaction 任务（6.10）。
- 两级持久化（6.8）。
- 遥测（6.12）、错误三规则（6.11）落地。
- 事件级调试工具：按运行回放、按钩子过滤。

**阶段 5：垂直验证**
- 用 COBOL 迁移类长任务跑通，验证四个承诺。

每阶段结束：跑第 9 节检验，更新架构图（去掉未兑现的"× N 分区"标注，补上所有权/Document/Inbox）。

---

## 11. 待决问题（需 Yi 决定）

1. Disruptor 保留还是换普通队列（实施者评估迁移成本后给建议）。
2. 内核钩子位置的最终集合（现有四个 + 是否补 afterModelReply / afterTools / onYield / beforeCompact）——1.0 前锁定。
3. Document 的存储形式：与 journal 同一 SQLite 事务，还是独立表 + 同 commit 序号。
4. journal 快照周期与格式。
5. 插件状态 scope 的粒度：会话 / 运行 / 用户三级是否都要。
6. 审批（人工确认）的 UI/推送路径（SseHub 之外是否还有渠道）。
7. 正式名称与句柄：确认 tantalum.dev / tantalum.io 域名、GitHub org 后缀形式、商标检索（J-PlatPat、USPTO 第 9/42 类）；确认后统一更名。
8. 三层拆包时现有的 `Phase` / `ToolRound` 归 kernel 还是拆成 value（数据）+ kernel（逻辑）——看现有代码耦合程度再定。

---

## 12. 参考

- Pi Durable README（1.4）：https://raw.githubusercontent.com/earendil-works/pi/main/packages/durable/README.md
  - 重点章节：Concepts → Child Tasks → Abort and Subagents → Tools（replay）→ Busy Conversations → Your Own State → Errors
  - 示例：21-subagent-foreground、22-subagent-background、23-child-tasks、12-recovery
- Cloudflare Pi harness 集成（Recovery / Parked tasks / Limitations）：https://developers.cloudflare.com/agents/harnesses/pi/
- 解读：https://www.80aj.com/2026/10/17/agent-task-recovery/
- 亲缘系统：LMAX Disruptor、Temporal durable execution、Erlang gen_statem、Akka

---

## 附：讨论中的关键判断（供理解动机）

- 这套设计"不新但在 agent 领域是少数派"：事件溯源 + 显式状态机 + 单线程 reactor，亲戚是 Temporal / gen_statem；Pi 作者自己也走到了 Pi Durable，证明方向不是炫技。
- 底座的核心问题只有一个：**扩展契约是什么**。QAgent 选的是"固定钩子位置 + 事件"，比 pi 的"代码钩进循环"更稳定、更隔离，代价是插件作者门槛更高，必须靠 SDK 和文档把事件模型藏起来。
- agent = 模型的眼睛（组装 prompt）+ 手（执行）+ 不信任模型的监护人（策略钩子）。架构里的任何东西都要能回答"改善了哪一半"。
- 你比 Pi Durable 多的是约束（内核纯净、转移表可测、审批），它比你多的是经验（所有权、replay、Document、Inbox、park）。约束是产品，经验可以抄。
