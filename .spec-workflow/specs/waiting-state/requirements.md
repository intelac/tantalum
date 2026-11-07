# Requirements Document

## Introduction

现在插件要问用户（如工具审批），只能在插件工作 `WorkType.run` 里调 `Questions.ask`，在内存里等（`tantalum-shell/src/main/java/tantalum/shell/question/OpenQuestions.java:23-24`：“问题本身不进 journal……重启后问题没了，等着的工作被关服务打断，运行按‘因重启中断’收尾”）。等待受三层上限约束：

- 一次工作最多等 `answer-wait`（要比 `tantalum.tool-timeout`，默认 60 秒，短；`tantalum-examples/src/main/java/tantalum/examples/boot/ExamplePlugins.java:53-55`）；
- 没回答时工作回“待定”，钩子再要一轮工作，最多 `max-polls` 次（`ToolApprovalHook.java:40`）；
- 一个等待点连续要工作有上限 `tantalum.hooks.max-work-rounds`，默认 8，超过运行失败（`tantalum-kernel/src/main/java/tantalum/kernel/step/WorkSteps.java:30-46`）。

结果是：用户离开几分钟，审批就超时，整个运行按失败结束；服务器重启，问题消失。模型自己也没有办法问用户（没有 `ask_user` 这类工具）。简报定的首个场景“COBOL 迁移类长任务……中途要人工确认”（`QAGENT_DESIGN_BRIEF.md:23`）直接撞上这一条。BACKLOG 第 0 节把它排在 0.1 的第一位；Super-agent 调研候选 ①“内核加可持久等人的等待状态（问题进 journal，用户回答作为事件回来，不占工作轮数）”说的也是它。

本 spec 做到：

- **等人是内核的一个阶段。** 运行停在“等回答”，没有在途命令，不计工作轮数，不超时；人几小时、几天后回答都接着跑。
- **问题和回答都在 journal 里。** 重启不丢、页面照样显示、回放一致、`check` 通过。
- **插件和模型都能问。** 钩子返回 `Ask` 就进入等人阶段，回答从新钩子位置 `onAnswer` 回来；模型用内置工具 `ask_user` 问，回答作为工具结果。
- **工具审批示例插件改用它**，不再有“等 N 秒、问 M 次”的配置。

例子：

| 时间 | 发生了什么 | 结果 |
|---|---|---|
| 周五 17:50 | Yi 让 agent 整理一个目录，开着工具审批。模型要调 `bash` | 对话页出现“允许调用 bash 吗？”三个按钮；运行状态“等你回答”；侧栏这个会话标着“待回答” |
| 17:55 | Yi 下班，关掉浏览器 | 运行停在等人阶段，不占模型、工具、工作轮数 |
| 周六 03:00 | 管理员重启服务器 | 这个运行不按“因重启中断”收尾；30 分钟没动静后会话被移出内存 |
| 周一 09:10 | Yi 打开会话 | 问题还在（从 journal 还原），按钮能点 |
| 09:11 | 点“允许这一次” | 落盘后页面才显示“已允许”；运行接着调 `bash`，几秒后模型继续 |
| 09:20 | 模型调 `ask_user`：“目录里有 3 个备份文件，删还是留？” | 对话页出现问题；Yi 选“留”并补一句“归档到 old/”；模型收到工具结果后照做 |
| 09:30 | 打开轨迹页 | 两次提问各是一条记录：问了什么、什么时候答的、答了什么 |

### 调研

参照 pi-mono `main`（7f47a2e，2026-11-05）、DSH `main`（fcc0ad4621，2026-11-04）、DeepSeek 的 Super-agent 研究原型 `deepseek-ai/dsh-superagent-prototype`（2026-11-07 读其 `docs/001-domain.md`、`docs/003-execution.md`，下称“SA 原型”）。路径写法：pi 的 `CA` 指 `packages/coding-agent`；DSH 路径相对于仓库根目录。

- **pi**：扩展用 `ctx.ui.select / confirm / input` 当场问（`CA/src/core/extensions/types.ts:165-172`），没有界面就直接拦（`CA/examples/extensions/permission-gate.ts:20-23`）。问题只在进程内存里，不进会话文件；没有让模型问用户的工具（`CA/src` 里没有 `ask_user`）。单人终端程序，人就在屏幕前，不需要等人状态。
- **DSH 审批**：审批请求只在一个 turn 打开期间有效，“持久的、turn 之外的审批流程留待以后”；“挂起的审批是进程内的——检查点只恢复策略和 turn 状态，不恢复等着的回答，恢复时不重发问题”（`packages/interaction/user-approval/README.md:160-168`）。和我们现在一样，重启就丢。
- **DSH 问用户**：工具 `ask_user_question` 默认阻塞等回答；`mode: timed` 时等 `timeout`（默认 120 秒）没回答就返回 `{"pending": true}`，模型可以先做别的，问题留着，之后的回答作为一条用户消息进来（`packages/interaction/tool-ask-user/README.md:12,66,74`）。问题的状态不另存，由记下的工具调用和已接收的用户消息折叠出来，所以“问题跨重启、跨客户端存活”（`packages/interaction/user-questions/README.md:57,70`；设计记录 `.agents/notes/implemented/architecture/2026-09-19-timed-user-question-two-settlements.md`“Durable state and late answers”）。
- **SA 原型**：工单有一个“待人工决定”（Attention）：“工单上等人回答的问题”；有 Attention 的工单阶段是 `person / answer`，面板标“Awaiting answer”，“`person` 阶段表示没有人的输入工单就不能继续”；交给人以后立刻释放执行名额；重启时“等待、就绪、受阻、失败的派工保持原样”（`docs/001-domain.md` 术语表；`docs/003-execution.md`“Ticket stages”“Background work and worker slots”“Restart”）。这是本 spec 的参照：等人不占资源、重启不丢、面板能看到谁在等。

我们的做法综合三家：问题和回答进 journal（DSH 的“从记录折叠出来”），等人是内核阶段、不占名额、重启保持（SA 原型），模型可以问（DSH 的 `ask_user_question`，先只做阻塞模式）。

### 范围

做：

- 内核新阶段 `AwaitingAnswer` 和两个新事实：问题（这一步的结果）、回答（事件 `Answered`）；新钩子结果 `Ask`，新钩子位置 `onAnswer`；
- 内置工具 `ask_user`（选项 + 可选自由文本），回答作为工具结果；
- 回答接口改走 Intake（落盘后才回“已接受”）；重启不中断等人的运行；等人的会话可回收；
- 对话页的问题条目、运行状态、侧栏“待回答”标记、轨迹记录；
- 工具审批示例插件改用新机制，去掉 `answer-wait`、`max-polls` 和 `Questions.ask`、`OpenQuestions`；
- 可选的回答期限；
- 三种语言；README；8091 上用真实 Claude 演示一次跨重启的审批。

不做（另开 spec）：

- DSH 的 `timed` 模式：问题留着、模型先做别的、回答作为用户消息晚到。这需要“后台任务”那一套（BACKLOG 0.1 之后第一项）；本 spec 的 `ask_user` 只有阻塞模式；
- 从 GitHub、聊天群回答（Super-Agent 的外部事件来源）；
- 多个人审批、按角色审批。回答的只能是会话的主人；
- 用户管理、登录持久化（`ops-hardening-2`）。

### 待批准的决定

| # | 决定 | 理由 |
|---|---|---|
| D1 | **去掉 `Questions.ask`、`OpenQuestions` 和插件工作里问人的路径。** 插件要问人只有一条路：钩子返回 `Ask`。`WorkType` 的 `ToolContext.questions()` 删掉 | 两条路并存会让“为什么这个问题重启后没了”说不清。现在用它的只有工具审批示例插件。插件 SDK 还没对外锁定（0.1 之前） |
| D2 | **新钩子位置 `onAnswer`**，收到回答时调用，和 `onWorkResult` 一样对所有钩子调用，钩子按 `question.id` 过滤。可以返回 `Allow`、`Stop`、`NeedWork`、再次 `Ask` | 回答不是“工作结果”，混进 `onWorkResult` 要靠类型判断；单独一个位置清楚。hook-coverage 的矩阵测试加一列 |
| D3 | **等人期间会话可以被回收出内存**（照 `tantalum.residency.idle`）。回答来了和提交一样把会话载入 | 等几天的运行不该占内存。等人阶段没有在途命令，状态全在 journal 里，载入就能接着跑。现在 `Evictor.java:106` 只要有活动运行就不回收，要改成“有活动运行且不在等人”才不回收 |
| D4 | **`ask_user` 允许自由文本**（选项之外补一句话，上限 4000 字符），插件的 `Ask` 不允许 | 模型问人常要补充说明（“归档到 old/”）；插件的问题是固定选项的决定，自由文本进钩子反而要插件自己解析。DSH 的 `ask_user_question` 也有 `custom` 文本 |
| D5 | **期限可选，默认没有。** 给了期限到时按“没回答”回到原处（钩子在 `onAnswer` 看到 `answer == null`；`ask_user` 的工具结果写明超时）。重启后按记下的到期时刻重新起定时器 | 审批应该一直等；但有的插件要“10 分钟没人管就按拒绝”，给它们一个口子。期限写进问题里，回放一致 |
| D6 | **等人期间 steer 照旧排队**到下一次模型调用，不打断等待；follow-up 排队；取消结束运行并撤下问题 | 和现在工具运行期间的行为一致（`SubmitSteps.java:56-67`）。要让一句插话代替回答，就得定义插话的语义，不值 |
| D7 | **重启时不中断等人的运行**：启动时只给“在等命令结果”的运行收尾，等人的保持原样 | 等人阶段没有在途命令，没有什么要中止或重做。SA 原型的做法相同 |

## Requirements

### Requirement 1：等人阶段

**User Story:** 作为运行长任务的用户，我希望 agent 要我确认时停下来等我，等多久都行，不要因为我没及时回答就失败。

#### Acceptance Criteria

1. WHEN 钩子返回 `Ask(question)` 或模型调用 `ask_user` THEN 内核 SHALL 把运行置为 `AwaitingAnswer`（`RunPhase` 的新实现，没有在途命令），记下问题、发起位置和（`ask_user` 时）工具调用 id。
2. WHILE 运行在 `AwaitingAnswer` THEN 内核 SHALL 不发任何命令，不计 `workRounds`、`endWorkRounds`，不受 `tantalum.tool-timeout`、`tantalum.hooks.max-work-rounds` 约束。
3. WHEN 收到这个问题的 `Answered` 事件 THEN 内核 SHALL 回到发起位置：钩子的问题调 `onAnswer`，按返回值继续（同 `WorkSteps.resume` 的分支）；`ask_user` 的问题把回答写成这个调用的工具结果，照常进入下一步。
4. WHEN 收到的 `Answered` 不是当前问题（id 不对、运行已结束、不在等人）THEN 内核 SHALL 忽略它并记一条 `HookReported`/事实说明原因，状态不变。
5. WHEN 期限到（`TimerFired`，D5）THEN 内核 SHALL 按“没有回答”回到发起位置，之后再来的回答按第 4 条忽略。
6. 一个运行同一时刻 SHALL 最多有一个待回答的问题；`onAnswer` 再返回 `Ask` 是新问题（新 id 或同 id 都算新一轮）。

### Requirement 2：问题和回答进 journal

**User Story:** 作为管理员，我希望谁问了什么、谁什么时候答了什么都有记录，重启不丢，回放一致。

#### Acceptance Criteria

1. WHEN 内核进入 `AwaitingAnswer` THEN 问题（id、标题、说明、选项、是否允许自由文本、期限、发起位置、工具调用 id）SHALL 作为这一步的结果写进 journal 那一行（record-results 的结果行），不另开文件。
2. WHEN 用户回答 THEN 回答 SHALL 是事件 `Answered(question, choice, text)`，和提交一样经 Intake 落盘后才回“已接受”（one-line-journal 的规则）。
3. WHEN 重放或 `check` 一个含等人的会话 THEN 结果 SHALL 和记录一致：问题来自结果行，回答来自事件，内核不读时钟、不问任何人。
4. 期限 SHALL 记成绝对时刻（由 Handler 在发起时算出写进结果，内核不读时钟）；重启后由 shell 按记下的时刻重新起定时器。

### Requirement 3：重启和回收

**User Story:** 作为用户，我希望服务器维护重启、或者会话很久没人碰，都不影响我回来接着回答。

#### Acceptance Criteria

1. WHEN 服务器启动 THEN 它 SHALL 只给“在等命令结果”的未结束运行发 `RunInterrupted`（现在 `Engine.interrupt`），等人的运行保持 `AwaitingAnswer`，不写任何事件。
2. WHEN 会话在等人且空闲超过 `tantalum.residency.idle` THEN `Evictor` SHALL 可以把它移出内存（D3）；在等命令结果的运行照旧不回收。
3. WHEN 对一个已移出内存的等人会话回答 THEN 它 SHALL 和提交一样被载入，然后处理 `Answered`。
4. WHEN 启动时有等人的会话带着期限 THEN shell SHALL 为每个重新起定时器；已过期的立刻触发。

### Requirement 4：插件 SDK

**User Story:** 作为插件作者，我希望“问用户、等回答”是钩子的一种返回值，和 `NeedWork` 一样简单，不用自己轮询。

#### Acceptance Criteria

1. `HookResult` SHALL 新增 `Ask(Question)`；`Question` 从 `tantalum.shell.plugin` 移到 `tantalum-value`（内核要用），字段：`id`、`title`、`detail`、`choices`、`deadline`（可空）。`freeText` 只有 `ask_user` 用，插件的 `Ask` 固定为 false。
2. `KernelHook` SHALL 新增 `onAnswer(KernelState, HookContext)`，`HookContext` 带 `question()` 和 `answer()`（`Answer(choice, text)`，没回答时为 null）；默认实现返回 `Allow.PLAIN`。
3. `Ask` SHALL 只在 `beforeModelCall`、`beforeToolCall`、`onFailure`、`beforeRunEnd`、`onWorkResult`、`onAnswer` 有效；`beforeCompact` 返回 `Ask` 当作拒绝（同 `NeedWork` 现在的规则）。
4. `Questions`、`OpenQuestions`、`ToolContext.questions()` SHALL 删除（D1）；`WorkType.run` 里没有办法问人。
5. ArchUnit SHALL 继续保证钩子不做 I/O；`Ask` 不含任何可变对象。
6. hook-coverage 的钩子矩阵测试 SHALL 覆盖 `onAnswer` 的每种返回值和 `Ask` 在每个位置的行为。

### Requirement 5：内置工具 `ask_user`

**User Story:** 作为用户，我希望模型拿不准的时候问我，而不是猜。

#### Acceptance Criteria

1. 内置工具 `ask_user` SHALL 有参数 `question`（必填）、`choices`（可选，1–8 个字符串）、`allow_text`（可选，默认 true）；描述里写明“只在没有答案就不能继续时用；等多久都行”。
2. WHEN 模型调用它 THEN 内核 SHALL 进入 `AwaitingAnswer`（需求 1），对话页显示问题；同一轮里其他工具照常执行，`ask_user` 的结果等回答。
3. WHEN 用户回答 THEN 工具结果 SHALL 是 `{"choice": "...", "text": "..."}`（没选的字段省略）；没有选项时只有 `text`；期限到时结果是 `{"timeout": true}`。
4. `ask_user` SHALL 在插件页“内置工具”里有开关，默认开；关掉后不发给模型（同其他内置工具）。
5. 自由文本 SHALL 限 4000 字符（D4），超出接口回 `request/invalid`。

### Requirement 6：回答接口

**User Story:** 作为用户，我希望点一下按钮就算回答了，页面上立刻看到结果，别人答不了我的问题。

#### Acceptance Criteria

1. `GET /api/sessions/{s}/questions` SHALL 返回这个会话当前待回答的问题（0 或 1 个）：id、标题、说明、选项、是否允许自由文本、期限、问的时间；只看得到自己的。
2. `POST /api/sessions/{s}/questions/{q}` 请求体 `{"choice": "...", "text": "..."}` SHALL 经 Intake 提交 `Answered`，落盘后回 200；运行已结束或问题不是当前的回 `question/gone`；`choice` 不是选项、`text` 超长或不允许回 `request/invalid`；不是本人的会话按现在的规则 404。
3. WHEN 问题出现、被回答、被撤下（取消、期限到）THEN SSE SHALL 推一条 `question`（现在已有），页面据此刷新，不再每 3 秒轮询（`console/src/composer/QuestionCard.tsx:21`）。

### Requirement 7：对话页、侧栏、轨迹

**User Story:** 作为用户，我希望一眼看到哪个会话在等我，问题和我的回答以后也能看到。

#### Acceptance Criteria

1. 对话页 SHALL 有新条目 `Question`：等回答时显示标题、说明、选项按钮、（允许时）文本框和期限倒计时；答过后显示问题、选的选项、填的文字和回答时间；期限到或取消后显示“没有回答”。刷新、重启、另一个浏览器看到的一样。
2. 运行状态 SHALL 显示“等你回答”（`RunStatus.phase` 新值），三种语言。
3. 侧栏会话列表 SHALL 给等回答的会话一个标记，列表接口带 `awaitingAnswer: true`；索引库记这个状态，不载入会话就能列。
4. 轨迹页 SHALL 有一条记录：问题、发起位置或工具调用、问的时间、回答和回答时间；时间概览里等人的那段标出来，不算进模型或工具时长；统计页签不变。
5. 现有 `QuestionCard` 的内存版问题 SHALL 删掉，由 `Question` 条目代替。

### Requirement 8：取消、插话、追问

#### Acceptance Criteria

1. WHEN 用户取消在等人的运行 THEN 运行 SHALL 按 `CANCELLED` 结束，问题标“没有回答”，之后的回答回 `question/gone`。
2. WHEN 用户在等人期间插话（steer）THEN 它 SHALL 排进 `pendingSteers`，下一次调模型时带上（D6）；追问排进 follow-up；新提问按现在“有活动运行”的规则。
3. WHEN 等人期间管理员关掉这个用户的某个插件或工具 THEN 不影响当前问题；回到原处时按现在“运行开始时记下的名单”执行。

### Requirement 9：工具审批示例插件

#### Acceptance Criteria

1. `ToolApprovalHook` SHALL 在 `beforeToolCall` 返回 `Ask`，在 `onAnswer` 决定：允许这一次 → `Allow`；这个会话里一直允许 → `Allow` 并把记录写进会话（现在用 `WorkDone.note`，改成 `Answered` 后由钩子返回的 `Allow` 带 `note`，或等价机制，设计定）；拒绝、没有回答 → `Stop`，只拦这一个调用。
2. `ApprovalWork` 和配置 `tantalum.examples.tool-approval.answer-wait`、`max-polls` SHALL 删除；`ExamplePlugins` 里对应的启动检查删除。
3. 其他 6 个示例插件 SHALL 不受影响（它们不问人）。

### Requirement 10：文档、迁移和 8091

#### Acceptance Criteria

1. README“写插件”SHALL 说明 `Ask`/`onAnswer`、`ask_user`、回答接口、期限；“能力对照”加一行“问用户、等回答”（pi 当场问、不存；DSH 阻塞或 timed、从记录折叠；我们内核阶段、进 journal）。
2. 旧会话文件 SHALL 照常读（没有等人阶段的会话不受影响）；journal 格式版本不变，新结果字段按 record-results 的规则加。
3. 8091 SHALL 用真实 Claude 演示一次：开工具审批，让模型调一个要审批的工具，不回答，重启服务器，再回答，运行完成；再让模型用 `ask_user` 问一次并回答。记用量。

## Non-Functional Requirements

### Performance

- 等人不占线程、名额、定时器（有期限的除外）。
- 侧栏的“待回答”标记从索引库读，列 1000 个会话不载入任何会话文件。
- 回答接口的延迟和提交相同（落盘后返回，one-line-journal 的 P95 预算）。

### Security

- 只有会话的主人看得到问题、答得了问题；别人的问题和不存在的一样 404。
- 自由文本原样进 journal 和模型上下文，不进服务器日志；长度上限 4000 字符。
- 插件的问题文字由插件给，可能含工具参数（现在审批已如此），只显示给本人。
- `ask_user` 关掉后模型看不到它；插件页的开关是每个用户自己的。

### Reliability

- 重启不丢问题、不丢回答；回答和运行结束同时到时以 journal 的先后为准，晚到的回答回 `question/gone`。
- 期限跨重启按记下的时刻生效，偏差不超过一次定时器调度。
- `check` 对含等人的会话一致。

### Usability

- 中文、英文、日文；问题文字用 `LocalText`，`ask_user` 的问题是模型写的原文。
- 对话页的问题条目和现在 `QuestionCard` 的样子一致，多了“答过后留在对话里”。
