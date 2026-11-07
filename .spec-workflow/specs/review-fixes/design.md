# Design Document

## Overview

按报告第 4 节五个阶段做。这里只把阶段 1 设计到可以直接写代码；阶段 2–5 在各自开始前补设计段落（追加到本文件末尾），不另开文件。

## Steering Document Alignment

- 内核仍纯函数：新事实 `StepFailed` 由内核报、shell 记；`Failures.describe` 放在 value（纯函数）。
- journal：所有新字段可空、不写出；旧行逐字节不变由现有期望文件守着。

## 阶段 1 设计

### 1. 回执和认领（需求 1）

现状：`Intake.publish` 先 `ledger.claim`（认领一可见，重复提交就能看到）再 `receipts.expect`。

改法：`SubmissionLedger.claim` 加一个回调参数 `Consumer<SubmissionId> onNew`，在持锁、认领写入 `recent` 的同一临界区里调用；`Intake` 传 `receipts::expect`。这样重复提交看到认领时回执一定已登记。

拒绝路径：`receipts.forget` 改成 `fail(submission)`：`pending.remove` 后 `completeExceptionally(new NotSaved)`，等着的重复提交得到失败。`Intake.publish` 的异常路径用 try/catch RuntimeException 包住 `event.apply`、`expect`、`tryPublish`：失败时 `gate.release`、`newRuns.remove`、`receipts.fail`、`ledger.release` 再抛。

关机：`Receipts.failAll()`，`Engine.close` 在 “drain user lanes” 之后、“drain cleanroom ring” 之前调用（之后不会再有落盘）。管线停住（`halted`）时 `Intake.publish` 已经返回 503，在途的由 `failAll` 收尾——`LaneDispatcher.halt` 也调一次。

索引查询失败：`Storage.findSubmission` 不再吞 `SQLException`，改抛 `UncheckedIOException`（包着）；`SubmissionLedger.claim` 让它传出；`Intake.publish` 捕获后返回 `Submission.rejected(503)`。

### 2. 用户通道（需求 2）

`UserLane.drain` 的循环体改成 `process` 外包一层 `try { process(batch) } catch (Throwable failure) { abandon(batch, failure) }`：`abandon` = `exit.failed`、把这批里的事件解钉（按真正钉住的集合）、`exit.discarded`、已落盘步骤的 `done` 各跑一次。`process` 内部：pin 放进 try 里，用一个 `pinned` 列表记真正钉住的；`afterDurable` 的 catch 改 `Throwable`；`exit.publish` 的调用放进 try，异常按拒绝处理。`Error` 不再吞：记日志、丢批、把线程留活。

`LaneDispatcher.onEvent`：`tryAcquire` 的 `InterruptedException` 用 try/catch，`users.giveBack` 后重新抛。`halt()`（现有的 `halted = true` 处）之后调 `users.discardAll(this::discarded)`：`UserPermits.discardAll` 持每个账户的锁把 `waiting` 清空、`parked` 归零、返回事件列表。

### 3. journal 写失败（需求 3）

`SessionFile.appendLines`：先 `long before = channel.size()`；catch `IOException` 时 `channel.truncate(before)`（再失败只记日志）并设 `broken = true`，之后的 `appendLines` 直接抛 `IOException("session file is broken after a failed write")`。`SessionFiles.append` 的 catch 里除了 `memos.remove(key)` 还要 `handle.file.close()`（quietly）、`handle.file = null`。

`RecordCodec.encodeTree`：`unknown.raw().deepCopy()`。`decode` 的 catch 改 `RuntimeException`。`SessionFileReader.Lines`：缓冲上限 64 MiB，超过抛 `SessionCorruptException`。`StoreLock.acquire`：`restrict`/`tryLock` 用 try/catch，失败先 `channel.close()`。`EventCodec.OrderedLanguages`：固定顺序之后把 `Language.values()` 里剩下的按枚举顺序补上；`EventCodecTest` 断言每个枚举值都写出。

### 4. 驻留（需求 4）

`Residency.pin` 返回 `Pinned(State state, String failure)`；`UserLane.load` 用它。`unpin`/`orNone`：`pins == 0` 且（COLD 或 FAILED 已过期）→ 删。`UserLane` 发布被拒绝时对剩余会话调 `residency.cold(session)`（新方法：pins 不变、状态 COLD）。`Cleanroom.rejected`：`envelope.run() != null` 时用 `new RunRef(principal, session, envelope.run())` 报 `PhaseChanged(LOAD_FAILED)`。

### 5. 内核（需求 5）

- `StepFailed(RunRef run, String event, String failure)` 新事实（value.decision），`Kernel.step` 在 `run == null` 的 catch 里返回 `Decision.unchanged(state)` 加这条事实（`Decision` 已能带事实：用 `new Decision(state, List.of(), List.of(fact))`，核对构造器）；`run` 用 `RunRef(principal, session, RunId.of(session, seq))`（同 `Cleanroom.rejected`）。`RecordCodec` 注册 `StepFailed`；轨迹页把它当错误记录（`TraceHooks` 旁边加一行即可，或先只进 journal/SSE）。
- `CancelSteps.commandDropped`：`run.phase() instanceof Awaiting a && a.commands().contains(event.command())` 才结束，否则 unchanged。
- `ToolSteps.toolFinished`：只更新 `status == RUNNING` 的槽位（核对 `ToolSlot.Status` 的值名）。
- `ModelSteps.modelReplied`：`stop == MAX_TOKENS && !calls.isEmpty()` → 不开工具，`RunEnd.beforeEnd(out, run, null, TRUNCATED)`。
- 排队模型：`SessionState` 加可空字段 `followUpModels: List<ModelChoice>`（和 `followUps` 等长，元素可为 null；为 null 表示旧行），`withFollowUps(messages, models)`；`SubmitSteps` 排队时两边一起追加；`RunEnd` 弹出时取同下标。`followUpModel` 字段删掉（它是 model-picker 这周加的，8091 的 journal 里有没有排队带模型的行？`grep followUpModel` 数据目录，若有则保留字段只读不写）。`RecordCodec` 的 `SessionChange` 跟着加 `Changed<List<ModelChoice>> followUpModels`。
- `ContextSteps.freezeContext` 首次：`active = enabledTools == null ? all : all ∩ enabledTools`。
- `Resume.continueWithText`：`callModel(out, run, work)`。
- `Compactor.isUserText`：块只能是 `TextBlock` 或 `AttachmentBlock`，第一块是文字。

### 6. 异常文字（需求 6）

`tantalum.value.text.Failures`（value，纯函数）：`describe(Throwable) -> String` = 异常类的简单名；`describe(Throwable, String what) -> what + ": " + 简单名`。用处：`HookChain`（`HookReported` 的 detail 和 `Stop` 的 reason）、`UserLane.loadOne`（`Failed.reason`，日志里仍记完整异常）、`ModelHandler.failed`、`ToolHandler` 的 `ExecutionException`、`OpenAiClient` 的连接失败；`tantalum-connect/Calls` 的失败结果改固定文字 `"Tool failed on the computer; see the connector's journal."`。

### 7. 钩子说明按钩子比较（需求 7）

`HookState.text` 改成 `List<HookTextBlock> texts`（上次发给模型的每钩子一段）。`HookPatches.apply`：切段后逐钩子比较，只有变化的钩子生成块；一个钩子的文字变空不发；全集合并后的空串处理照旧。HookState 是本周新加的、journal 里还没出现过（8091 的会话没有钩子补丁），可以直接改形状。

### 测试

- `IntakeTest`/`ReceiptsTest`：空当里的重复提交等到原提交失败后得到 `NotSaved`；`failAll`；索引失败 503；发布路径抛异常时四样都退回。
- `UserLaneTest`：`process` 抛 Error 后通道还能处理下一批；pin 异常解开已钉的；`exit.publish` 抛异常；`LaneDispatcherTest`：中断归还名额、停机后丢弃停住的事件。
- `SessionFileTest`：写失败后截回、句柄作废；`RecordCodecTest`：`UnknownFact` 不被改写、解码任意 RuntimeException 变 corrupt；`SessionFileReaderTest`：超长行；`StoreLockTest`：失败关通道；`EventCodecTest`：语言全覆盖。
- `ResidencyTest`：`Pinned`、过期回收、`cold`；`CleanroomTest`：已有运行的 LOAD_FAILED。
- 内核：`KernelTest`（StepFailed）、`CancelStepsTest`、`ToolStepsTest`、`ModelStepsTest`（MAX_TOKENS 带工具）、`SubmitStepsTest`/`RunEndTest`（两条排队各自模型）、`ContextStepsTest`、`ResumeTest`、`CompactorTest`。
- `FailuresTest`；`HookChainTest` 的 detail 断言；`HookPatchesTest` 的按钩子比较。

## 阶段 2 设计：模型客户端与流（需求 10）

### 1. 看门狗持有流（10.1）

`StreamWatchdog` 自己持有要关的流：`AtomicReference<AutoCloseable> stream`，新方法 `watch(AutoCloseable)` 发布流对象，**发布时已经过期就立刻关掉**；`onExpire` 关的是引用里的流（为 null 时等流发布时再关）。两个客户端不再用 `open[]` 数组：拿到 `StreamResponse`/响应体后 `watchdog.watch(…)`。构造函数去掉 `onExpire` 参数。

### 2. Claude 流（10.2、10.6）

- `pending(start)`：不认识的块类型（server_tool_use、web_search_tool_result 等）记成 `Pending("ignored")`，`finish` 时不产生内容块。
- 没有 start 就来的 delta：只有文字 delta 才补一个 text 块；thinking、signature、input_json 的孤立 delta 丢弃（M99）。
- `finish`：thinking 块没有签名（`signature` 为空）→ 丢弃，不以空签名发回（M101）；空的 text 块不产生（M100）。
- 失败分类顺序：先 `isInterrupt`、再 `watchdog.expired()`，然后才按 `AnthropicServiceException`（HTTP）、`AnthropicIoException`（连接）分（M98）。

### 3. Claude 请求（10.3）

- `param(TextBlock)`：全空白的文字块不发（`isBlank`），和 `blocks()` 的判断一致（M102）。
- 工具结果：文字为空白时用占位 `(no output)`（M103）；`toolResultWithImages` 只在文字非空白时加文字块，图片照加（M104）。
- `reasoningParam`：`thinking`/`signature`/`data` 任一缺失或不是字符串 → 返回 null（不发这块），不抛异常（M105）。

### 4. OpenAI 流（10.4、10.5）

- `read()` 用 try-with-resources 关响应体；`httpFailure` 已关。
- 流里的 `{"error":…}`（200 之后）→ `ModelOutcome.Failed(UNKNOWN, 不重试, detail = "stream error: " + 脱敏截断的 message)`（M108）。
- 工具调用分片没有 `index` 时按 `id` 分：有 id 的开新片段并记下 id→序号，没 id 也没 index 的接到最后一个片段（M109）。
- 错误体：`httpFailure` 读 4 KB 后再截到 1 KB、去掉控制字符，进 detail（#33）。`OverflowRules` 对 400 的匹配不动（另有 `OverflowRulesTest` 守着）。

### 测试

- `StreamWatchdogTest`（新）：过期后再 `watch` 立刻关；正常 `activity` 续期；`close` 后不再关。
- `ClaudeClientTest`：不认识的块类型不产生空文字块；没签名的思考块被丢掉；孤立的 thinking delta 不当文字；看门狗到期时 HTTP 异常也报超时（用 server 延迟）。
- `ClaudeRequestTest`：全空白文字块不发、空工具结果有占位、缺字段的思考块不发——用 `params()` 的 JSON 断言，不改旧期望文件。
- `OpenAiClientTest`：流内错误块；无 index 的两个并行调用按 id 分开；错误体截断到 1 KB。

## 阶段 3 设计：登录与安全加固（需求 11）

### 1. 登录、改密码、SSE（任务 15）

- **改密码一个事务**：`UserStore.changePassword(name, next, keepLoginId)`：`BEGIN` → 更新哈希和口令戳 → `restamp(keepLoginId)` → `DELETE` 其余登录并返回被删的 id → `COMMIT`；`AuthController.changePassword` 对每个被删的 id 调 `engine.hub().closeLogin(id)`（和 logout 一样）。
- **`touch` 失败不影响登录**：`valid()` 里 `touch` 包 try/catch，失败只记 debug；`issue()` 先 `create` 再 `sweep`，`sweep` 失败只记日志。
- **Cookie 随 touch 续期**：`touch` 时设请求属性 `REFRESH_TOKEN`，`saveContext` 看到它就用同一个令牌重发 Cookie（Max-Age 重新算），和服务器端的滑动窗口一致。
- **绝对上限**：`Logins.check/byId` 多一个 `maxAge`（30 天，`LoginContextRepository.MAX_LIFETIME`），`created` 太早的当过期；`sweep` 同样删。
- **SSE 收尾**：`pump` 的 `finally` 里关订阅后一定 `emitter.complete()`（已完成的忽略异常）；`IllegalStateException` 单独捕获记 debug（容器已结束响应），其他 RuntimeException 记 error 并 `completeWithError`。
- **Origin**：没有 `Origin` 时看 `Sec-Fetch-Site`，`cross-site` 拒绝；拒绝写 JSON `{"error":"bad origin","code":"auth/bad-origin"}`（新错误码，三语文案）。反向代理后的主机比对不动（要开 `server.forward-headers-strategy`，README 已写）。
- **请求体先看长度**：`AuthBodyLimitFilter`（只对 `/api/auth/**`）：`Content-Length` 超过 `MAX_BODY` 或缺失（chunked）直接 413 JSON，不读体。
- **最短密码 8 位**：`AuthController.MIN_PASSWORD`，改密码、新建、重置、命令行都用；错误码 `REQUEST_INVALID`，消息写明。
- **`UserStore`**：`verify` 只在锁里读行，Argon2 在锁外算；`add`/`changePassword` 先算哈希再进锁；最后一个管理员的检查变成一条条件 UPDATE（`… AND (admin=0 OR disabled=1 OR EXISTS(SELECT 1 FROM users o WHERE o.admin=1 AND o.disabled=0 AND o.name<>?))`），0 行时按 `find` 分 NO_SUCH 和 LAST_ADMIN；`open` 建表失败关连接；`update(name, admin, disabled)` 一个事务（`UsersController.update` 用它）。
- **`LoginThrottle.evictOldest`** 先清过期的再按最老淘汰。
- **接口校验**：`requestId` 匹配 `[A-Za-z0-9._:-]{1,128}`；`/api/runs/{run}` 的 `run` 用 try/catch `IllegalArgumentException` → 404。

### 2. 出口与地址（任务 16）

- `AddressRules.blocked`：自端口守卫对 `plain` 和内嵌的 IPv4 都查 `isLocal`；`interfaces()` 对 `getNetworkInterfaces()` 为 null 和 `SocketException` 都返回“未知”，这时自端口守卫按“任何地址都算本机”（fail-closed）。
- `Egress`：`readHead` 改成带缓冲对象的 `HeadReader`，超时重试不丢已读字节；请求头有总时限（`HEAD_TIMEOUT_MS` 从第一个字节起算）；转发请求体时近端 `SO_TIMEOUT` 改成 `POLL_MS`；`open.add(socket)` 在 `closed` 标志的锁下做，关闭后加进来的立即关。
- `Fetcher`：DNS 解析放到虚拟线程，`get(left)` 超时按不可达处理。

### 3. 本机工作区与连接程序（任务 17）

- `LocalLinks.attach`：先在锁里登记（`links.put`，顶掉旧的）、安排到期定时器，再发 welcome；`Link.call()` 在 welcome 发出前先等（`welcomed` 闩）；welcome 发不出去就 `dropped()` 走正常清理。`verify` 发现账号停用时走 `revoke(principal)`（加锁、记 `revokedAt`、断开已有连接）。`LocalEndpoint` 的 hello 定时器和 attach 用 `AtomicBoolean attaching` 互斥。
- 连接程序 `Link`：`socket` 在 `onOpen` 里赋值，赋值后 `ended` 已完成就 `abort()`；`run()` 在 `current = session` 之后再查一次 `stopped`；`Session.ended()` 同时等 `stopped`；`send()` 被中断时结束会话；`handle` 里的异常按 1011 结束而不是“不可读”；`calls.context` 的线程包 try/catch；`Calls`：重复 id 回错误结果、`running.remove` 放 finally、catch Throwable。
- `Main`：`--allow-read` 同样 `toRealPath` 加 `refuseTooBroad`；`--server` 不是回环且是 `http`/`ws` 时拒绝，除非 `--insecure`；`MxcPolicy` 的拒绝路径存在时用 `toRealPath()`，字面和真实两种都写。

### 4. 浏览器（任务 18）

- `Browsers.close()`：每个槽位单独 try/catch（`getNow` 抛 CompletionException 当没起来），一个失败不影响其余；清扫线程 catch Exception 记日志继续；`sweep()` 每个浏览器的 close 单独 try/finally。
- `UserBrowser.open`：catch RuntimeException 关驱动再抛 `Unavailable`；`trimTabs` 每页单独 try/catch；`use()` 任何 RuntimeException 都走崩溃检查。驱动环境的黑名单不改成白名单（Playwright 只能并入环境、不能替换），补几个常见名字。

### 5. 装配与启动（任务 19）

- `Engine` 构造：一个 `List<AutoCloseable> opened`，每打开一样加一个；catch Throwable 时倒序关、再抛。环启动之后的部分也包进去（失败时 `close()`）。
- `interrupt()`：`tryPublish` 循环有上限（`DRAIN`）并看 `input.broken()`，超时或坏了抛 `IllegalStateException`；`awaitDrained` 的结果记日志；被中断时抛出，不再装作成功。

### 测试

每个任务改到的类都有对应测试（`AuthApiTest`、`LoginsTest`、`UserStoreTest`、`LoginThrottleTest`、`EventStreamTest`、`SecurityConfig`/`OriginFilterTest`、`AddressRulesTest`、`EgressTest`、`FetcherTest`、`LocalLinksTest`、`LinkTest`、`CallsTest`、`MainTest`、`MxcPolicyTest`、`BrowsersTest`、`EngineTest`），只加新用例、改被影响的断言。

## 阶段 4 设计：去重与归位（需求 12）

### 1. 消息分类和折叠（任务 21）

- value 新增 `tantalum.value.model.SystemUpdates`（接口，只有静态成员）：`PREFIX`（“不是用户写的”开头）、`HEADER`（带后半句和空行的整段）、`isSystemUpdate(Message)`（工具变化或钩子说明的 system 消息）、`isModelChange(Message)`、`isContextUpdate(String)`。内核 `ContextUpdate` 和 `PromptProjection.isSystemUpdate`、shell 的 `ToolChanges.PREFIX`、`HookTexts.isUpdate`、`ModelChanges.isModelChange` 都改成用它；字面量只剩一份。
- `ModelChanges.fold` 和 `HookTexts.fold` 合成 `shell/model/Folding.fold(messages, foldHookTexts)`：一次遍历，system 消息里换模型的说明并到前一条用户消息的开头（有工具结果时末尾）、钩子说明（`foldHookTexts` 为真时）并到末尾、工具变化照旧丢；前面不是用户消息时单独成一条。两个客户端的调用点换成它。请求期望文件逐字节不变。

### 2. 工具的描述和实现（任务 22）

- `shell/plugin/ToolDispatch(name, server, serverConcurrency, Predicate<Json> concurrencySafe)`：调度要的全部信息，纯数据加一个按参数判断的函数。`Tool` 加 `default ToolDispatch dispatch()`（从现有方法取）。
- 不用依赖就能给出调度信息的静态工厂：`BuiltinTools.dispatch()`、`LocalTool.dispatch(timeout)`、`WebFetchTool.DISPATCH`、`MemoryTools.dispatch()`、`BrowserTools.dispatch()`；`ToolCatalog.safety()` 改成从 `ToolDispatch` 列表建 `ToolSafety`；`KernelFactory.safety` 用这些工厂，不再 `new` 带 null 依赖的工具。

### 3. 插件开关只在一处（任务 23）

- `HookChain(List<KernelHook>)` 不再拿 `required`：“必须开启的插件出错按拦截处理”由 shell 决定——`PluginGate.kernelHooks()` 把管理员配置为必须开启的钩子包成 `FailClosed`（委托全部回调、`failClosed()` 恒真），`KernelFactory.kernel` 用它。内核放行与否只看 `SessionContext.activePlugins`（已经如此）。
- `KernelHook` 只留 `name()`、`plugin()`、`failClosed()` 和七个回调；`description()`、`mandatory()`、`defaultEnabled()` 挪到 shell 的 `PluginInfo` 接口（`plugin()`、`description()`、`mandatory()`、`defaultEnabled()`），示例钩子同时实现两者；`AfterToolHook.description()` 同样挪走。`PluginGate` 的分组从 `instanceof PluginInfo` 取文案和开关策略，没有实现的按“无说明、非必须、默认开”。
- `PluginGate.HOOK_POINTS` 从 `HookPoint` 枚举派生（`BEFORE_MODEL_CALL` → `beforeModelCall`），`overrides` 按派生出的方法名查；`ON_ANSWER` 也进插件页的位置列表。
- `afterTool`：`AfterToolHook` 加 `default boolean failClosed()`；钩子抛异常时，`failClosed()` 或管理员要求必须开启的插件让这次工具调用以错误结束（`ToolOutput` 的错误文字只给类名），其余照旧记日志、结果保持上一个。

### 4. 事实和状态的归位（任务 24，实施时折中）

实施时发现 journal 的硬规则（旧会话用新版本重写后的行逐字节相同，`ModelChoiceCodecTest`）不允许改运行状态和事实的形状，所以：

- `HookDecided` 不加字段：加 `questionId()`（`ASK` 时从 `reason` 位取），`reason` 字段的注释写明两种含义。
- `Compacting.patch` 留着（永远 `NONE`），注释写明留着的原因。
- “压缩刚结束”不进 `RunState`：`SessionState.justCompacted()`（`context == null && compaction != null`）给这个判断一个名字，`ContextSteps` 在冻结上下文前算出来传给 `ModelSteps.callModel(…, compactionAllowed)`。
- `CompactSteps.notAdopted` 拆成 `refused`、`notSent`、`notAdopted` 三个小函数，共用 `failed(out, run, compacting, reason, mayTrim, failsSwitch, summarySent)`，`Stage` 枚举删掉。
- `Fetcher.precheck` 返回 `Lookup`（`RESOLVED`、`UNRESOLVED`）而不是布尔；`get` 和 `failure` 按它分支。

### 测试

- `SystemUpdatesTest`（value）、`FoldingTest`（合并后的用例来自两个旧测试）、`ToolDispatchTest`/`KernelFactoryTest`（safety 不造对象且答案和真目录一致）、`HookChainTest`（FailClosed 包装）、`PluginGateTest`（位置从枚举派生、after-tool failClosed）、`HookDecided` 的编解码测试（新行带 `question`、旧行 `reason` 读出来一样）、`EventCodecTest`（`Compacting` 旧行带 `patch` 能读）、`CompactStepsTest`/`ContextStepsTest`（`compactionJustDone`）、`FetcherTest`。
- 请求期望文件和 trace 期望文件逐字节不变。

## 阶段 5 设计：拆大类与清注释（需求 13）

### 1. `RecordCodec` 拆分（任务 26）

`journal` 包内新增五个包内类，都拿同一个 `JsonMapper`（结果行的 mapper）和 `RecordMemo`：

- `Deltas`：`delta(now, before, keep)` 和 `merge(delta, before, what)`，会话上下文和运行都用；
- `SessionDelta`：`encode(SessionChange, seq, memo)`、`decode(JsonNode, seq, memo)`，含 `context`、`notes`、`changed`、`changedList`；
- `RunDelta`：`encode(now, before)`（`toolSlots`/`toolCount`/`toolsChecked`）和 `merge(delta, before)`；
- `RequestDelta`：`CallModel` 的编解码（system、工具下标、消息下标、`keep`/`add`、模型选择、指纹）；
- `TextSlices`：`shrink`/`expand`（内容块引用、长字符串引用、切片、转义）；
- `RunRefs`：`sameRun`/`restoreRun`。

`RecordCodec` 只留 mixin、mapper 的构造、`encode`/`decode`/`encodeTree`/`decode(tree)`、`see`、`parse`、`fingerprint` 和事实的编解码（`fact`），按原来的顺序调上面几个。常量（标记、阈值）跟着搬到用它的类里。期望文件逐字节不变（`RecordCodecTest`、journal 和 trace 的期望文件都在回归里）。

### 2. `Engine` 装配分步（任务 27）

`boot` 包内四个包内记录，各有一个静态 `open(...)`：

- `Stores(storage, localTokens, switches, userSettings, memories)`：打开的数据文件和数据库；
- `Toolbox(catalog, localLinks, localWorkspace, addressRules, egress, browsers, webAccess, mcp, gate, routes, compaction)`：工具、插件和联网；
- `Pipeline(cleanroomRing, lanes, inFlight, input, deadlines, handlers, output, cleanroom, intake, receipts)`：环、通道和处理器；
- `Queries(view, hub, queries)`：读取端。

每一步打开的东西都加进 `Engine` 的 `opened` 列表（传进 `open`），失败时 `Engine` 倒序关。`Engine` 的字段改成这四个记录加 `clock`、`codec`、`shutdownWait`、`closing`；原来的访问器照旧（委托给记录）。`close()` 的关闭顺序不变。

### 3. `RunState` 按阶段分组（任务 28）

journal 里的运行状态是扁平字段，老行要能读、新行逐字节不变，所以记录本身不动，分组做成视图：

- `RunState.Work(phase, tools, pendingSteers, retries, workOrigin, workRounds, endWorkRounds)`、`RunState.Compaction(compacting, autoCompactionFailed, overflowRecovered, compactionJustDone)`、`RunState.Models(model, modelBefore)`，各有 `withX`；
- `run.work()`、`run.compaction()`、`run.models()` 返回视图，`run.withWork(work)` 等整组替换；原来的 `withPhase` 这些保留（委托）。
- 记录分组真正落到存储要改 journal 形状，留给 BACKLOG 的 RunState/SessionState 重构。

### 4. spec 编号注释（任务 29）

主代码里 374 处全角括号引用（如“（model-picker M10）”）和 28 处裸提及。做法：

- 引用只跟在一句已经说明了规则的话后面（绝大多数）：去掉括号里的引用，句子保留；括号里还带别的内容（DSH/pi 文件行号、日期、人名）的，只删编号、留其余；
- 整条注释只有引用、没有规则的：改写成规则本身（一句话）；
- 类注释里的“这是哪个 spec 做的”改成“这是做什么的”；
- `tech.md` 已经按 spec 记了每条规则的出处，编号只留在那里。
- 逐包做，一个包一个提交点（都在同一次提交里），每包改完跑该包的测试，最后全量回归。测试代码里的引用不动（测试名就是需求编号的索引）。
