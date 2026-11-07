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

