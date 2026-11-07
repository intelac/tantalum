# Design Document

## Overview

“等人”变成内核的一个阶段 `AwaitingAnswer`：钩子返回 `Ask(question)`，或模型调用内置工具 `ask_user`，运行就停在这个阶段，没有在途命令，不计工作轮数，不超时。问题作为这一步的结果写进 journal 那一行（运行阶段里带着问题，另有事实 `QuestionAsked`），用户的回答是事件 `Answered`，经 Intake 落盘后才回“已接受”。收到回答后回到发起位置：钩子的问题调新钩子位置 `onAnswer`，`ask_user` 的问题把回答写成工具结果。重启时不中断等人的运行；等人的会话可以被回收出内存，回答来了再载入。

现有的内存版问答（`Questions`、`OpenQuestions`、`ToolContext.questions()`、`ApprovalWork`）删掉，工具审批示例插件改用 `Ask`/`onAnswer`。

## Steering Document Alignment

### Technical Standards (tech.md)

- 内核仍是纯函数：问题的期限由 shell 在发起时算成绝对时刻写进事件上下文（内核用事件的 `at` 算定时器延迟，不读时钟）；回答是事件；`onAnswer` 是纯钩子，ArchUnit 规则不变。
- 一步一行（one-line-journal）：问题在这一步的结果行里（运行阶段 + 事实），回答是事件行；提交落盘后才回“已接受”的规则沿用到回答。
- 记录结果（record-results）：恢复和查询只读结果行；`check` 用新内核重放含等人的会话结果一致，因为问题、回答都在记录里。
- 事件驱动：等人期间没有线程、没有名额被占；期限用现有的 `StartTimer`/`TimerFired`。
- 和 DSH 一样问题状态可以从记录折叠出来（它折叠工具调用和用户消息，我们直接记阶段和事实），和 SA 原型一样“交给人以后立刻释放名额、重启保持原样”。

### Project Structure (structure.md)

| 模块 / 包 | 改动 |
|---|---|
| `tantalum-value` | 新包 `tantalum.value.question`：`Question`、`Answer`、`AskUser`（工具名和参数解析）；`state.AwaitingAnswer`；`event.Answered`；`decision.QuestionAsked`、`decision.QuestionSettled`；`hook.Ask`、`HookPoint.ON_ANSWER`、`HookContext` 加两个字段、`Allow` 加可选的会话记录 |
| `tantalum-kernel` | 新 `step.AnswerSteps`；`WorkSteps.resume` 抽成 `step.Resume` 两处共用；`ToolSteps` 认 `ask_user`；`CancelSteps` 处理新阶段；`hook.HookChain`、`KernelHook` 加 `onAnswer` |
| `tantalum-shell` | 删 `question` 包和 `plugin.Questions`；`loop.Intake.answer`；`boot.Engine` 启动时跳过等人的运行、重起期限定时器；`store.SessionIndex` 两个新列；`web.QuestionController` 改走 Intake 和查询；`conversation` 新条目 `Question`；`trace` 新记录；`tool.BuiltinTools` 加 `ask_user` 的定义；`journal.RecordCodec`/`EventCodec` 编解码 |
| `tantalum-examples` | `ToolApprovalHook` 改写；删 `ApprovalWork`；`ExampleSettings`、`ExamplePlugins` 去掉 `answer-wait`、`max-polls` |
| `console/` | `conversation/QuestionItem.tsx` 代替 `composer/QuestionCard.tsx`；侧栏标记；运行状态文字；三种语言 |

## Code Reuse Analysis

### Existing Components to Leverage

- **`WorkSteps.start/resume`**：等人和等工作是同一形状（暂停在某个钩子位置，结果来了回到原处）。`resume` 抽成 `Resume.at(out, run, origin, continueWith, stopped)` 共用。
- **`StartTimer`/`TimerFired`/`TimerHandler`**：期限。`CompactSteps.AWAITING_SUMMARY_RETRY`、`RetrySteps.AWAITING_RETRY` 已经这么用。
- **`Intake.publish`/`cancel`**：回答走和取消一样的入口（外部事件、过 `ExternalGate`、不去重），但和提交一样等落盘（`Receipts`）。
- **`SessionIndex.run_status`**：已经存活动运行的阶段名，`AwaitingAnswer` 自动进去；侧栏标记和启动时的筛选都从它来。
- **`SseHub.question`**：已有的 `question` 推送，改由事实监听器触发。
- **`TranscriptPass`**：按事实生成条目的套路（`ContextCompacted` → `CompactionMark`），`QuestionAsked`/`QuestionSettled` → `Question`。
- **`ToolSlot`/`ToolRound`**：`ask_user` 是一个不发 `RunTool` 的槽位，加一个状态 `WAITING`。
- **`SessionState.notes`**：审批“这个会话里一直允许”继续记在这里，来源从 `WorkDone.note` 改成 `Allow.note`。

### Integration Points

- `Engine.interrupt`：按 `run_status` 跳过等人的会话；新 `DeadlineTimers` 在启动时为带期限的等人会话重起定时器。
- `Evictor.evictable`：有活动运行但在等人 → 可回收。
- `PluginGate`：`ask_user` 是内置工具，开关走现有的内置工具开关（`ToolCatalog`）。
- `ToolHandler`：永远收不到 `ask_user`（内核不发 `RunTool`），防御性地返回错误。

## Architecture

### 两条进入等人的路

```
钩子路：  beforeToolCall / beforeModelCall / onFailure / beforeRunEnd / onWorkResult / onAnswer
          └─ Ask(question) ─► AnswerSteps.ask(origin) ─► 阶段 AwaitingAnswer ─► [StartTimer 若有期限]
                                                                 │
                                   Answered / TimerFired ─────────┘
                                                                 ▼
                                   onAnswer(question, answer|null) ─► Allow(note?) / Stop / NeedWork / Ask
                                                                 ▼
                                   Resume.at(origin)（同 WorkSteps.resume）

工具路：  ModelReplied 含 ask_user ─► ToolSteps.start：ask_user 槽位标 WAITING，其他槽位照常检查、执行
          └─ 其他槽位都完成 ─► AnswerSteps.ask(toolCallId) ─► AwaitingAnswer
                                   Answered / TimerFired ─► 槽位结果 {"choice","text"} / {"timeout":true} ─► dispatch ─► callModel
```

`ask_user` 为什么等其他工具先跑完：需求 5.2 要“同一轮里其他工具照常执行”；一轮里几个 `ask_user` 时按槽位顺序一个一个问（一个运行同一时刻只有一个问题，需求 1.6）。

### 阶段

```java
/** 等用户回答。没有在途命令；{@code timer} 是期限的定时器命令，没有期限为 null。 */
public record AwaitingAnswer(Question question, HookPoint origin, String toolCallId, CommandId timer) implements RunPhase {
    public String name() { return "AwaitingAnswer"; }
}
```

`origin` 和 `toolCallId` 二选一：钩子的问题 `toolCallId == null`；`ask_user` 的问题 `origin == null`。

转移表新增：

| 阶段 | 事件 | 处理 |
|---|---|---|
| `AwaitingAnswer` | `Answered` | `AnswerSteps.answered`：id、选项对得上 → 结算；否则事实 `HookReported("answer ignored: …")`，状态不变 |
| `AwaitingAnswer` | `TimerFired`（是这个阶段的 `timer`） | `AnswerSteps.expired`：按没有回答结算 |
| 任何阶段 | `Answered`（不在等人） | 忽略，记事实 |
| `AwaitingAnswer` | `Cancel` | `CancelSteps.end`：有 `timer` 就 `AbortCommands`；事实 `QuestionSettled(CANCELLED)`；运行 `CANCELLED` |
| `AwaitingAnswer` | `RunInterrupted` | 不变（shell 不会发；万一发了也不能把等人的运行收尾） |
| `AwaitingAnswer` | `SteerSubmitted` / `FollowUpSubmitted` | 现有处理：排队 |

结算（`settle`）：

1. 事实 `QuestionSettled(run, questionId, answer, reason)`，`reason ∈ {ANSWERED, EXPIRED, CANCELLED}`。
2. 有 `timer` 且不是它触发的 → `AbortCommands(timer)`。
3. `toolCallId != null` → 槽位结果 → `ToolSteps.dispatch`。
4. 否则 `onAnswer`：`HookContext.withQuestion(question).withAnswer(answer)`；结果：
   - `Allow`：有 `note` 则 `session.withNote(key, obj)`；`Resume.at(origin, stopped = null)`；
   - `Stop`：`Resume.at(origin, stopped = reason)`（拦工具时只拦这一个调用，其他位置结束运行，同 `WorkSteps.resume`）；
   - `NeedWork`：`WorkSteps.start(origin)`；
   - `Ask`：`AnswerSteps.ask(origin)` 新问题。
5. 工作轮数：进入等人时 `workRounds` 不动；从等人回到原处后再要工作，轮数接着原来的数（和 `onWorkResult` 接着要工作一样）。

### 期限

`Ask`/`ask_user` 的期限是 `Duration`（插件给 `Duration`，`ask_user` 的参数 `timeout_seconds`）。内核在 `ask` 时用事件的 `at` 算出 `deadline = at + duration` 写进 `Question`，并发 `StartTimer(id, run, duration)`。重启后 `TimerHandler` 的线程没了：`Engine` 启动时从索引（`answer_deadline`、`answer_timer`）为每个等人的会话起 `DeadlineTimers`，到时发布 `TimerFired(answer_timer)`；已过期的立刻发。定时器到了但会话已移出内存：`TimerFired` 和别的事件一样触发载入。

### 重启

`Engine.interrupt(unfinished)` 只处理 `run_status != "AwaitingAnswer"` 的会话。等人的会话什么都不写：下一个事件（回答、取消、插话、期限）到来时按平时载入。

### 回收

```java
private static boolean evictable(SessionState session, KernelState state) {
    if (session == null) return true;
    if (!session.followUps().isEmpty()) return false;
    if (session.activeRun() == null) return true;
    RunState run = state.runs().get(session.activeRun());
    return run != null && run.phase() instanceof AwaitingAnswer;   // D3
}
```

有 follow-up 排队的会话不回收（它们在内存里，不在 journal 的会话状态外……实际 `followUps` 在 `SessionState` 里、随结果行落盘，但回收后再载入要重放，保持现在的规则不动）。

### 回答的路

```
POST /api/sessions/{s}/questions/{q} {"choice","text"}
  → QuestionController：会话归属、取当前问题（SessionQueries.openQuestion，冷会话从最后一行结果的运行阶段读）
     没有 / id 不对 → question/gone；choice 不是选项、text 超长或不允许 → request/invalid
  → Intake.answer(principal, session, run, Answered(request, q, choice, text))
     ExternalGate 准入（同 cancel）→ 发布 → 等落盘（Receipts）→ 200
  → 内核 AnswerSteps.answered（再验一次 id 和选项：两次回答抢先的那个有效）
  → 事实 QuestionSettled → 事实监听器 → SseHub.question(user, session) + 对话页条目更新
```

## Components and Interfaces

### `tantalum-value`

包 `tantalum.value.question`：

```java
public record Question(String id, LocalText title, LocalText detail, List<Choice> choices, boolean freeText, Instant deadline) {
    public record Choice(String id, LocalText label) {}
    // id、title 必填；choices 可空当且仅当 freeText；deadline 可空
    public boolean offers(String choice);
}
public record Answer(String choice, String text) {}   // 两者至少一个非空；由 Question.accepts(answer) 校验

/** 内置工具 ask_user 的名字、参数和结果（内核和 shell 共用，不依赖 JSON 库以外的东西）。 */
public final class AskUser {
    public static final String NAME = "ask_user";
    public static Question parse(String callId, Json arguments, Instant at);  // 参数错抛 IllegalArgumentException（信息给模型）
    public static String result(Answer answer);                               // {"choice":…,"text":…}
    public static final String TIMEOUT = "{\"timeout\":true}";
}
```

`tantalum.shell.plugin.Question` 删除，插件改 import。`LocalText` 已在 value。

其他：

```java
public record AwaitingAnswer(Question question, HookPoint origin, String toolCallId, CommandId timer) implements RunPhase
public record Answered(RequestId request, String question, String choice, String text) implements Event   // TYPE "Answered"，version 1
public record QuestionAsked(RunRef run, Question question, String askedBy) implements Fact     // askedBy：钩子名或 "ask_user"
public record QuestionSettled(RunRef run, String question, Answer answer, Reason reason) implements Fact { enum Reason { ANSWERED, EXPIRED, CANCELLED } }
public record Ask(Question question) implements HookResult      // HookResult permits Allow, Stop, NeedWork, Ask
public record Allow(PromptPatch patch, SessionNote note)         // note 可空；Allow.PLAIN 不变；record SessionNote(String key, Json.Obj value)
public enum HookPoint { …, ON_ANSWER }
public record HookContext(…, Question question, Answer answer)   // withQuestion(question, answer)
```

`Ask` 的 `Question.freeText` 固定 false，构造时检查。`Ask` 的 `deadline` 由钩子给 `Duration`：`Ask.after(Duration)` 记在 `Ask` 上，内核进入阶段时算成 `Instant`（钩子不读时钟）。

### 内核

`step.AnswerSteps`：

```java
public static final String AWAITING_ANSWER = "AwaitingAnswer";
static void ask(Outcome out, RunState run, Question question, HookPoint origin, String toolCallId, String askedBy)
public static Decision answered(StepContext context)
public static Decision expired(StepContext context)
public static Decision ignored(StepContext context)   // 任何别的阶段收到 Answered
```

`step.Resume`：从 `WorkSteps.resume` 搬出来，`WorkSteps` 和 `AnswerSteps` 共用；`continueWithText` 一起搬。

`ToolSteps`：`start` 时 `ask_user` 槽位 `ToolSlot.waiting(call)`（新状态 `WAITING`，不算 `allFinished`，`ToolScheduler.next` 跳过）；`dispatch` 里“没有 SENT、没有 PENDING、有 WAITING”时取第一个 WAITING 槽位 → `AnswerSteps.ask(toolCallId)`；参数解析失败 → 槽位直接 `done(error)`。`beforeToolCall` 钩子对 `ask_user` 照常跑（审批插件可以拦它）。

`KernelHook`：`default HookResult onAnswer(KernelState state, HookContext context) { return Allow.PLAIN; }`；javadoc 补“`onAnswer` 对所有钩子调用，按 `context.question().id()` 过滤”。`HookChain.run(ON_ANSWER, …)` 同其他位置；`HookDecided.Kind` 加 `ASK`，记问题 id。

`Kernel`/`BuiltinSteps` 注册上面三条转移；`CancelSteps.end` 处理 `AwaitingAnswer`；`CancelSteps.interrupted` 对 `AwaitingAnswer` 返回不变。

### 包 `tantalum.shell.loop`

`Intake.answer(Principal, SessionId, String question, String choice, String text)` → `Submission`：归属检查同 `submit`；`activeRun` 从 `sessions.find`（冷会话也能找到）；没有活动运行 → 409（控制器映射成 `question/gone`）；发布 `Answered`，`needsNewRun = false`；`processed` 里 `Answered` 归还名额。

`Evictor.evictable` 如上。

### 包 `tantalum.shell.boot`

`Engine`：
- 删 `OpenQuestions`、`questions()`；`WorkHandler` 构造去掉 `questions`。
- `interrupt`：`unfinished.stream().filter(s -> !AWAITING_ANSWER.equals(s.runStatus()))`。
- 新 `DeadlineTimers`（`loop` 包）：启动时 `index.awaitingWithDeadline()` → 每个用虚拟线程 `sleep(until)` 后 `input.tryPublish(TimerFired(timer))`；关闭时中断。运行中进入等人的定时器仍由 `TimerHandler` 处理（`StartTimer` 命令），`DeadlineTimers` 只管重启后的。
- 事实监听器：`QuestionAsked`/`QuestionSettled` → `hub.question(principal, session)`。

### 包 `tantalum.shell.store`

`SessionIndex`：新列 `answer_deadline TEXT`、`answer_timer TEXT`（`ALTER TABLE … ADD COLUMN` 兼容旧库），由 `IndexBatch` 从结果行的运行阶段填：阶段是 `AwaitingAnswer` 且有期限时写，否则 null。`awaitingWithDeadline()` 查询。`SessionQueries.openQuestion(principal, session)`：常驻会话读内存状态，冷会话读 `SessionFile` 最后一行结果的 `runs`（`RecordReplay` 已能折叠）。

### 包 `tantalum.shell.web`

`QuestionController`：

- `GET /api/sessions/{s}/questions` → `{"questions": [ {id, title, detail, choices, freeText, deadline, askedAt} ]}`（0 或 1 个）。
- `POST …/questions/{q}` → 校验后 `Intake.answer`；`Submission.saved` 完成后 200 `{"question", "choice", "text"}`。
- 会话列表（`ApiController` 现有接口）每项加 `awaitingAnswer: runStatus == "AwaitingAnswer"`。

`ErrorCode`：沿用 `QUESTION_GONE`、`REQUEST_INVALID`。

### 包 `tantalum.shell.tool`

`BuiltinTools` 加 `AskUserTool`：只有 `ToolSpec`（名字、描述、参数 schema：`question` string 必填，`choices` string[] 1–8，`allow_text` boolean 默认 true，`timeout_seconds` integer 可选），`execute` 抛 `IllegalStateException("ask_user is handled by the kernel")`。描述文字（给模型，英文）：“Ask the user when you cannot continue without their decision. Waits as long as needed. Offer choices when there are natural options.” 插件页开关同其他内置工具。

### 包 `tantalum.shell.conversation`

`TranscriptItem.Question(id, session, run, at, question, status, answer, settledAt, askedBy)`，`status ∈ {OPEN, ANSWERED, EXPIRED, CANCELLED}`；`TranscriptPass` 在 `QuestionAsked` 时 `put`，`QuestionSettled` 时更新；`ItemJson` 输出 `kind: "question"`。`RunStatus.phase` 自然是 `AwaitingAnswer`。

### 包 `tantalum.shell.trace`

`TraceRecord.Question(question, askedBy, askedAt, settledAt, answer, reason)`；时间轴上单独一条泳道“等回答”，时长不计入模型和工具（`TraceTools`/`TraceRequests` 不变）。

### 包 `tantalum.shell.journal`

`RecordCodec`：`AwaitingAnswer` 子类型注册；`Question`、`Answer` 的编解码（字段固定顺序，回放逐字节相同）；事实 `QuestionAsked`、`QuestionSettled`。`EventCodec`：`Answered`。格式版本不变（新增类型，旧文件没有）。

### `tantalum-examples`

`ToolApprovalHook`：

```java
beforeToolCall: 列出的工具且没“一直允许” → new Ask(question("approval-" + call.id(), …三个选项…))
onAnswer:      不是自己的问题（id 不以 "approval-" 开头）→ Allow.PLAIN
               answer == null（期限到 / 取消不会到这里）→ Stop("no approval decision")
               once → Allow.PLAIN；session → Allow.PLAIN.withNote("approval", {tool: true})；deny → Stop("the user denied …")
```

删 `ApprovalWork`、`ExampleSettings.ToolApproval.answerWait/maxPolls` 及 `ExamplePlugins` 的两条启动检查；`tools` 列表保留。`README`“写插件 › 部署示例插件”的配置表同步。

### `console/`

- `conversation/QuestionItem.tsx`：OPEN 时标题、说明、按钮、（`freeText`）文本框、期限倒计时，点按钮 → `answerQuestion`，成功后等条目更新（不本地假设）；ANSWERED/EXPIRED/CANCELLED 时只读显示。删 `composer/QuestionCard.tsx` 和 `listQuestions` 轮询；SSE `question` 只用来刷新侧栏标记。
- 侧栏会话项：`awaitingAnswer` → 小标记“待回答”，三种语言。
- 运行状态文字：`AwaitingAnswer` → “等你回答 / Waiting for your answer / あなたの回答を待っています”。
- 轨迹页：新记录类型的行和泳道。

## Data Models

### journal

结果行 `runs[].phase`：

```json
{"type":"AwaitingAnswer","origin":"BEFORE_TOOL_CALL","toolCallId":null,"timer":null,
 "question":{"id":"approval-toolu_01","title":{"en":"Allow calling bash?","zh":"允许调用 bash 吗？"},"detail":{"en":"{\"command\":\"ls\"}"},
             "choices":[{"id":"once","label":{"en":"Allow once","zh":"允许这一次"}},{"id":"session","label":{…}},{"id":"deny","label":{…}}],
             "freeText":false,"deadline":null}}
```

事实：

```json
{"type":"QuestionAsked","run":"s-x_3","askedBy":"toolApproval","question":{…}}
{"type":"QuestionSettled","run":"s-x_3","question":"approval-toolu_01","answer":{"choice":"once","text":null},"reason":"ANSWERED"}
```

事件行：

```json
{"seq":12,"at":"…","type":"Answered","version":1,"run":"s-x_3","payload":{"request":"…","question":"approval-toolu_01","choice":"once","text":null}}
```

### HTTP

`GET /api/sessions/{s}/questions`：

```json
{"questions":[{"id":"approval-toolu_01","title":{"en":"…","zh":"…"},"detail":{"en":"…"},
  "choices":[{"id":"once","label":{…}}],"freeText":false,"deadline":null,"askedAt":"2026-11-07T09:10:00Z"}]}
```

`POST /api/sessions/{s}/questions/{q}`：请求 `{"choice":"once"}` 或 `{"choice":"keep","text":"归档到 old/"}` 或 `{"text":"…"}`；200 `{"question":"…","choice":"once","text":null}`；错误 `question/gone`、`request/invalid`。

会话列表项新增 `"awaitingAnswer": true|false`。

对话条目：

```json
{"kind":"question","id":"q:approval-toolu_01","run":"s-x_3","at":"…","askedBy":"toolApproval","status":"answered",
 "question":{…},"answer":{"choice":"once","text":null},"settledAt":"…"}
```

### 索引库

`sessions` 表新列 `answer_deadline TEXT NULL`、`answer_timer TEXT NULL`。

### 配置

无新配置。删除 `tantalum.examples.tool-approval.answer-wait`、`tantalum.examples.tool-approval.max-polls`（启动时发现它们 → 报错指出已删除，避免悄悄忽略）。

## Error Handling

| 情况 | 处理 |
|---|---|
| 回答的 `choice` 不是选项 / `text` 超 4000 字 / 问题不允许文本 | 控制器 `request/invalid`；内核再验一次，不合法的 `Answered` 记事实忽略 |
| 回答时运行已结束、问题换了、不在等人 | 控制器 `question/gone`；抢先落盘的 `Answered` 由内核忽略并记事实 |
| 两次回答几乎同时 | 先落盘的有效，后一个内核忽略、控制器对第二个人也回 200（它落盘了）——页面以条目状态为准 |
| `ask_user` 参数不合法 | 槽位立刻 `done(error)`，不进入等人，错误文字给模型 |
| 一轮里多个 `ask_user` | 按槽位顺序一个一个问 |
| `Ask` 出现在 `beforeCompact` | 当作拒绝（同 `NeedWork`） |
| `Ask` 的 `Question` 构造不合法（没选项、freeText） | 钩子抛异常 → `HookChain` 按插件 `failClosed()` 处理（现有规则） |
| 期限到和回答同时 | 先落盘的有效；`TimerFired` 不是当前 `timer` 的忽略 |
| 重启时索引落后（崩溃） | 启动先补齐索引（现有），再筛等人的会话和期限 |
| 等人期间插件被关 | 回到原处时用运行开始记下的名单（现有规则，需求 8.3） |
| 回答的事件在 `Evictor` 刚回收后到 | 和提交一样触发载入 |

## Testing Strategy

### Unit Testing

- 内核（`kerneltest/step/AnswerStepsTest`）：从 5 个有效位置 `Ask` → 阶段、事实、无命令；回答 → `onAnswer` 四种返回值各自的去向；期限 → `StartTimer`、`TimerFired` 结算、过期后回答被忽略；取消 → `AbortCommands`、`QuestionSettled(CANCELLED)`；`RunInterrupted` 不变；不合法回答记事实；`ask_user`：其他槽位先跑完、参数错、两个 `ask_user` 顺序、结果 JSON、超时结果；`beforeCompact` 的 `Ask` 当拒绝；工作轮数在等人前后的计数。
- 钩子矩阵（hook-coverage 的 `HookMatrixTest`）加 `onAnswer` 一列和 `Ask` 一行。
- `RecordCodec`/`EventCodec` 往返：新阶段、事实、事件；字段顺序固定（回放逐字节）。
- `Evictor`：等人可回收、等命令不可回收。
- `SessionIndex`：新列、`awaitingWithDeadline`、旧库升级。
- `Intake.answer`：归属、没有活动运行、名额归还。
- `TranscriptPass`：`Question` 条目的四种状态。
- 示例插件：`ToolApprovalHook` 的 `Ask`/`onAnswer`，“一直允许”记进会话。
- 控制台 vitest：`QuestionItem` 四种状态、文本框、倒计时；侧栏标记。
- ArchUnit：钩子不做 I/O 规则覆盖 `onAnswer`；`tantalum.shell.question` 包不存在。

### Integration Testing

- `EngineTest`：开审批 → 模型要调工具 → 等人 → 关引擎 → 重开 → 问题还在、`run_status` 是 `AwaitingAnswer`、没有 `RunInterrupted` → 回答 → 工具执行 → 运行完成；带期限的在重启后到期。
- `JarCrashIT` 类：等人期间 kill -9，重启后回答。
- 真实管线的钩子矩阵（hook-coverage 的 86 个）加等人的路径。
- `check`：含等人的会话一致。

### End-to-End Testing

Playwright（E2eServer 的假模型加一个会调 `ask_user` 的脚本，审批用 E2e 的策略插件）：
- 审批：问题出现 → 刷新页面还在 → 另一个浏览器同账号也看到 → 点“允许这一次” → 条目变成已回答、工具执行；
- `ask_user`：选项 + 文本 → 模型收到；
- 取消在等人的运行 → 条目“没有回答”；
- 侧栏“待回答”标记出现、消失。

### 8091

需求 10.3 的真实 Claude 演示：审批跨重启、`ask_user` 一次；记用量。
