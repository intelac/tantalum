# Tasks Document

工作方式：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志（日志只记结论和数字，不记过程）。

每个阶段结束：
- 只在阶段末跑一次全量回归（`scratchpad/regress.sh`：控制台构建、`mvn -o clean verify`、跳号回归、vitest、Playwright）；阶段中间只跑改到的模块（`mvn -o -q test -pl <模块> -am`）；
- 提交一次，推到 GitHub `main`（Windows 工作流自动跑，不等它）；
- 有服务器代码改动时重新打包，复制 `tantalum.jar` 和 `tantalum-examples.jar` 到 `scratchpad/app/`，跑 `up.sh` 更新 8091；
- 用几句话向 Yi 汇报：做了什么、测试数字、8091 上怎么看。

构建：本 spec 不加依赖，本地一律 `mvn -o … -am`。提交前扫描密钥。安全规则（每个任务都适用）：
- 回答的文字不进服务器日志；只有会话主人能看、能答；
- 密钥不进任何新文件；服务器默认只绑 `127.0.0.1`。

路径前缀：
- `value/` = `tantalum-value/src/main/java/tantalum/value/`，`valuetest/` = `…/src/test/java/tantalum/value/`
- `kernel/` = `tantalum-kernel/src/main/java/tantalum/kernel/`，`kerneltest/` = `…/src/test/java/tantalum/kernel/`
- `shell/` = `tantalum-shell/src/main/java/tantalum/shell/`，`shelltest/` = `…/src/test/java/tantalum/shell/`
- `examples/` = `tantalum-examples/src/main/java/tantalum/examples/`，`examplestest/` = `…/src/test/java/tantalum/examples/`

每个任务的 `_Prompt` 开头省略的固定部分：“Implement the task for spec waiting-state, first run spec-workflow-guide to get the workflow guide then implement the task: 开始时在 tasks.md 把本任务标成 [-]，做完用 log-implementation 记录，再标成 [x]。”

## 阶段 1：值类型和内核

这一阶段结束时内核已经会等人，shell 还不会发 `Answered`，8091 上看不到变化。

- [x] 1. 值类型：`Question`、`Answer`、`AskUser`、新阶段、新事件、新事实、新钩子结果
  - File: value/question/Question.java、Answer.java、AskUser.java（新）；value/state/AwaitingAnswer.java（新）；value/event/Answered.java（新）；value/decision/QuestionAsked.java、QuestionSettled.java（新）；value/hook/Ask.java（新）、HookResult.java、HookPoint.java、HookContext.java、Allow.java；value/state/ToolSlot.java（`WAITING`）；删 shell/plugin/Question.java，改 shell、examples 里的 import
  - 内容照设计“`tantalum-value`”：`Question` 的校验（`choices` 可空当且仅当 `freeText`；`Ask` 的 `freeText` 固定 false）、`accepts(answer)`、`AskUser.parse/result/TIMEOUT`、`Allow.withNote`
  - 测试：valuetest/question/QuestionTest（校验、`accepts`、`AskUser.parse` 的合法和不合法参数、`result` 的 JSON）；`mvn -o -q verify` 全仓库照过（只是搬迁和新增）
  - _Requirements: 1.1, 4.1, 4.2, 5.1, 5.3_
  - _Prompt: Role: Java 值类型 | Task: 加新类型，搬 Question，保持全仓库编译和测试通过 | Restrictions: 不改内核行为；字段顺序固定、全部不可变 | Success: 新测试通过，verify 全绿_

- [x] 2. 内核：等人阶段的钩子路
  - File: kernel/step/AnswerSteps.java、Resume.java（新）；kernel/step/WorkSteps.java（`resume`、`continueWithText` 搬到 `Resume`）；kernel/hook/KernelHook.java（`onAnswer`）、HookChain.java（`ON_ANSWER`、`Ask` 的 `HookDecided.Kind.ASK`）；kernel/step/BuiltinSteps.java（三条转移）；kernel/step/ModelSteps.java、ToolSteps.java、RetrySteps.java、RunEnd.java（钩子返回 `Ask` 时调 `AnswerSteps.ask`）；kernel/step/CancelSteps.java（`AwaitingAnswer` 的取消和 `RunInterrupted`）；kernel/step/CompactSteps.java（`beforeCompact` 的 `Ask` 当拒绝）
  - 测试：kerneltest/step/AnswerStepsTest（新）：5 个有效位置 `Ask` → 阶段、`QuestionAsked`、无命令、工作轮数不变；回答后 `onAnswer` 四种返回值的去向（`Allow` 带 `note` 记进会话；`Stop` 在 `beforeToolCall` 只拦一个调用、别处结束运行；`NeedWork`；再 `Ask`）；不合法回答、别的阶段收到 `Answered` → 事实、状态不变；取消 → `QuestionSettled(CANCELLED)`；`RunInterrupted` 不变；`beforeCompact` 的 `Ask` 当拒绝；`WorkStepsTest` 照过
  - _Requirements: 1.1–1.4, 1.6, 4.2, 4.3, 8.1_
  - _Prompt: Role: 内核 | Task: 实现 AnswerSteps 的钩子路和 Resume 共用 | Restrictions: 内核不读时钟；不发 RunTool；事实字段顺序固定 | Success: AnswerStepsTest 全过，kernel 模块测试全绿_

- [x] 3. 内核：`ask_user` 槽位和期限
  - File: kernel/step/ToolSteps.java（`WAITING` 槽位、其他槽位完成后 `AnswerSteps.ask(toolCallId)`、参数错直接 `done(error)`、回答写成槽位结果）；kernel/tool/ToolScheduler.java（跳过 `WAITING`）；kernel/step/AnswerSteps.java（期限：`StartTimer`、`expired`、`AbortCommands`）
  - 测试：kerneltest/step/AnswerStepsTest 加：一轮里 `ask_user` 和两个普通工具 → 普通工具先发、都完成后进等人；回答 → 槽位结果 `{"choice","text"}` → `callModel`；两个 `ask_user` 顺序问；参数错不进等人；期限 → `StartTimer(duration)`，`TimerFired` → `{"timeout":true}` / 钩子 `answer == null`，过期后的回答被忽略；不是当前 `timer` 的 `TimerFired` 忽略；回答时 `AbortCommands(timer)`
  - _Requirements: 1.5, 1.6, 5.2, 5.3, 2.4_
  - _Prompt: Role: 内核 | Task: ask_user 槽位和期限 | Restrictions: 期限的绝对时刻用事件的 at 算；不改 ToolScheduler 的并发规则 | Success: 新测试全过_

- [x] 4. journal 编解码
  - File: shell/journal/RecordCodec.java（`AwaitingAnswer` 子类型、`Question`/`Answer`、两个事实）、EventCodec.java（`Answered`）
  - 测试：shelltest/journal/RecordCodecTest、EventCodecTest 加往返；两次编码逐字节相同；旧格式样本照过；`StoreFormat` 版本不变
  - _Requirements: 2.1–2.3, 10.2_
  - _Prompt: Role: 存储 | Task: 新类型的编解码 | Restrictions: 字段顺序固定；不改格式版本；不动旧类型的编码 | Success: 往返测试过，`WireFormatTest`/期望文件不变_

- [x] 5. 钩子矩阵加 `onAnswer` 和 `Ask`
  - File: examplestest/hooks/HookMatrixTest.java、MatrixHook.java（及它用的真实管线夹具）
  - 测试：`onAnswer` 一列（Allow、Allow+note、Stop、NeedWork、Ask、抛异常按 `failClosed`）；`Ask` 在 6 个位置各一格（`beforeCompact` 当拒绝）；真实管线上回答用 `Intake`——本阶段 `Intake.answer` 还没有，矩阵先直接发布 `Answered` 事件到输入环，任务 6 再换成 `Intake.answer`
  - _Requirements: 4.3, 4.6_
  - _Prompt: Role: 测试 | Task: 扩钩子矩阵 | Restrictions: 不放宽现有断言 | Success: 矩阵全绿，格数在日志里_

阶段 1 结束：回归（全量一次）、提交、推送、汇报。

## 阶段 2：shell 管线、接口、示例插件

这一阶段结束时 8091 上工具审批可以等几天、重启不丢；对话页用的还是旧的问题卡（任务 11 才换），所以要先让 `GET /questions` 的形状兼容旧卡。

- [x] 6. `Intake.answer`、启动不中断等人、删内存版问答
  - File: shell/loop/Intake.java（`answer`、`processed`）；shell/boot/Engine.java（`interrupt` 筛选、事实监听器触发 `hub.question`、删 `OpenQuestions`）；删 shell/question/OpenQuestions.java、shell/plugin/Questions.java；shell/plugin/ToolContext.java（删 `questions()`）；shell/handler/WorkHandler.java
  - 测试：shelltest/loop/IntakeTest（归属 404、没有活动运行 409、名额归还）；shelltest/boot/EngineTest：等人 → 关 → 开 → 没有 `RunInterrupted`、阶段还是 `AwaitingAnswer` → `answer` → 运行完成；`OpenQuestionsTest` 删除；ArchUnit：`tantalum.shell.question` 包不存在
  - _Requirements: 2.2, 3.1, 4.4, 6.2_
  - _Prompt: Role: shell 管线 | Task: 回答走 Intake，重启保持等人，删旧路径 | Restrictions: 回答和提交一样落盘后才返回；不改 Receipts 的规则 | Success: EngineTest 的跨重启用例过_

- [x] 7. 回收、索引新列、重启后的期限
  - File: shell/loop/Evictor.java（`evictable`）；shell/store/SessionIndex.java（`answer_deadline`、`answer_timer`、`awaitingWithDeadline`、旧库 `ALTER TABLE`）、IndexBatch.java；shell/loop/DeadlineTimers.java（新）；shell/boot/Engine.java（启动起定时器、关闭中断）
  - 测试：EvictorTest（等人可回收、等命令不可、回收后回答触发载入）；SessionIndexTest（新列、旧库升级）；EngineTest：带期限的等人 → 重启 → 到期 `TimerFired` → 钩子收到 `answer == null`；已过期的启动后立刻触发
  - _Requirements: 3.2–3.4, 2.4_
  - _Prompt: Role: shell 存储 | Task: 等人可回收、期限跨重启 | Restrictions: 索引可由会话文件重建的性质不变；DeadlineTimers 只管重启后的 | Success: 新测试过_

- [x] 8. 接口和 `ask_user` 的工具定义
  - File: shell/web/QuestionController.java（GET 从 `SessionQueries.openQuestion`，POST 走 `Intake.answer`，校验）；shell/store/SessionQueries.java（`openQuestion`，冷会话读最后一行结果）；shell/web/ApiController.java（会话列表 `awaitingAnswer`）；shell/tool/BuiltinTools.java、AskUserTool.java（新，只有定义）；shell/handler/ToolHandler.java（防御）；shell/access/ToolCatalog.java（开关）
  - 测试：shelltest/web/QuestionControllerTest（0/1 个问题；`gone`、`invalid`、超长、不允许文本、别人的 404；冷会话也能 GET）；会话列表字段；`ToolCatalogTest` 开关；`GET /questions` 的形状兼容现有 `QuestionCard`（`id/title/detail/choices`）
  - _Requirements: 5.1, 5.4, 5.5, 6.1–6.2, 7.3_
  - _Prompt: Role: Web | Task: 问答接口改走新路径，加 ask_user 定义 | Restrictions: 错误码只用现有的；回答文字不进日志 | Success: 控制器测试过，旧问题卡在 8091 上还能用_

- [x] 9. 对话条目和轨迹记录
  - File: shell/conversation/TranscriptItem.java（`Question`）、TranscriptPass.java、ItemJson.java；shell/trace/TraceRecord.java（`Question`）、TraceQuery.java 及时间轴数据（泳道“等回答”，不计入模型/工具时长）
  - 测试：TranscriptPassTest 四种状态和刷新一致；TraceQueryTest 记录和时长不受影响
  - _Requirements: 7.1, 7.2, 7.4_
  - _Prompt: Role: 投影 | Task: Question 条目和轨迹记录 | Restrictions: 条目只从事实生成，不读内存里的问题 | Success: 测试过，JSON 形状照设计_

- [x] 10. 工具审批示例插件改用 `Ask`/`onAnswer`
  - File: examples/ToolApprovalHook.java；删 examples/ApprovalWork.java；examples/boot/ExampleSettings.java、ExamplePlugins.java（删 `answer-wait`、`max-polls`，发现旧键报错）；examplestest/E2eServer.java（注册改）；examplestest/ToolApprovalHookTest.java；README“写插件 › 部署示例插件”的配置表；`scratchpad/app/start.sh` 里如有旧键则删
  - 测试：`Ask` 的问题形状（三个选项、id `approval-<call>`）；`onAnswer` 的 once/session/deny/null；“一直允许”记进会话、下次不问；别的问题不管；旧配置键启动报错
  - _Requirements: 9.1–9.3_
  - _Prompt: Role: 插件 | Task: 审批插件改写 | Restrictions: 问题文字三种语言照旧；拒绝时告诉模型不要绕过 | Success: 测试过，8091 上开审批、离开 5 分钟再回答仍然成功_

阶段 2 结束：回归、提交、推送、打包、更新 8091、汇报（8091 上演示：开审批，让模型调一个工具，不回答，`up.sh` 重启，再回答）。

## 阶段 3：控制台、端到端、文档、8091

- [x] 11. 控制台：`QuestionItem` 代替 `QuestionCard`
  - File: console/src/conversation/QuestionItem.tsx、QuestionItem.test.tsx（新）；console/src/api/types.ts、client.ts（条目类型、`answerQuestion` 带 `text`、删 `listQuestions`）；console/src/conversation/MessageList.tsx/RunBlock.tsx（挂条目）；删 console/src/composer/QuestionCard.tsx、QuestionCard.test.tsx；console/src/store/events.ts（`question` SSE 只刷侧栏）
  - 测试：vitest：OPEN 显示按钮、`freeText` 文本框、期限倒计时；点选后禁用等条目更新、不本地假设结果；ANSWERED/EXPIRED/CANCELLED 只读；超长文本提示
  - _Requirements: 7.1, 7.5, 6.3_
  - _Prompt: Role: 前端 | Task: 问题作为对话条目 | Restrictions: 不轮询；沿用现有 QuestionCard 的样式 | Success: vitest 过，旧卡删除_

- [x] 12. 控制台：侧栏标记、运行状态、轨迹页、三种语言
  - File: console/src/shell/SessionRow.tsx、Sidebar.tsx（`awaitingAnswer` 标记）；console/src/conversation/RunStatusRow.tsx（`AwaitingAnswer` 文字）；console/src/trace/*（新记录行、泳道）；console/src/text/zh.ts、en.ts、ja.ts
  - 测试：vitest：标记出现/消失；状态文字三种语言；轨迹页新记录
  - _Requirements: 7.2–7.4_
  - _Prompt: Role: 前端 | Task: 侧栏、状态、轨迹、翻译 | Restrictions: 文字全部走 useText | Success: vitest 过，`pnpm build` 过_

- [x] 13. 端到端
  - File: examplestest/E2eModel.java（`ask:` 剧本：调 `ask_user` 两个选项允许文本，回答“你选了：…”）；console/e2e/question.spec.ts（新）
  - 测试：审批：问题出现 → 刷新还在 → 第二个浏览器同账号也看到 → 点“允许这一次” → 条目已回答、文件写出；`ask:`：选项 + 文本 → 模型回复里有两者；取消在等人的运行 → 条目“没有回答”；侧栏“待回答”标记出现、消失；现有 `approve:` 用例改成新条目
  - _Requirements: 6.3, 7.1, 7.3, 8.1_
  - _Prompt: Role: 端到端 | Task: Playwright 用例 | Restrictions: 不加 sleep，等条目状态 | Success: 57 个旧用例 + 新用例全过_

- [x] 14. 文档
  - File: README.md（“写插件”：`Ask`/`onAnswer`、`ask_user`、回答接口、期限；“能力对照”加一行；“数据目录”索引新列）；README.en.md 一段；.spec-workflow/steering/tech.md（结果：阶段名、事实、测量）；.spec-workflow/BACKLOG.md（第 0 节这一项完成，Super-agent 候选 ① 完成）
  - _Requirements: 10.1, 10.2_
  - _Prompt: Role: 文档 | Task: 说明和对照 | Restrictions: 说人话；不写过程 | Success: 三处都改到_

- [ ] 15. 8091 真实 Claude 演示
  - 步骤：开 toolApproval（插件页），让 Sonnet 5.5 调一个要审批的工具，不回答，`up.sh` 重启，回到会话回答“允许这一次”，运行完成；再让它用 `ask_user` 问一次，选项 + 文本回答。记两次的用量、等待时长、重启后页面上问题是否还在，写进 tech.md 和本任务日志
  - _Requirements: 10.3_
  - _Prompt: Role: 验收 | Task: 8091 演示 | Restrictions: 一次真实模型两轮，不重跑 | Success: 两轮都完成，用量记下_

阶段 3 结束：回归、提交、推送、打包、更新 8091、汇报。
