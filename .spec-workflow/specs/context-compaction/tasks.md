# Tasks Document

工作方式：一次一个任务，测试先行；完成后标记、记录实现日志；每个阶段结束提交一次，并汇报做了什么、验证结果、是否偏离 spec、下一步。压缩代码不引用供应商类型。提交前扫描密钥。真实模型测试要有判定标准和结论，事后报用量。

## 阶段 0：客户端支持摘要模式

- [x] 1. `Prompt.Output` 和两个客户端
  - File: tantalum-value/src/main/java/tantalum/value/model/Prompt.java, tantalum-shell/src/main/java/tantalum/shell/model/ClaudeClient.java, tantalum-shell/src/main/java/tantalum/shell/model/OpenAiClient.java, tantalum-shell/src/test/java/tantalum/shell/model/ClaudeClientTest.java, tantalum-shell/src/test/java/tantalum/shell/model/OpenAiClientTest.java
  - `Output(toolsAllowed, maxTokens)`，默认 `(true, 0)`；禁用工具时 `tool_choice none` 且工具定义仍发；`maxTokens > 0` 覆盖配置
  - 验证：捕获的请求体符合；默认 Output 时请求体与现在逐字节相同
  - _Requirements: 3.1_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“`Prompt.Output` 和两个客户端”，满足需求 3.1，遵守 design.md | Restrictions: 只做这一件事；保留 Prompt 三参构造器；不改内核 | Success: 捕获的请求体符合；默认 Output 时请求体与现在逐字节相同 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [x] 2. 真实服务确认摘要模式请求被接受
  - File: tantalum-shell/src/test/java/tantalum/shell/model/OpenAiSmokeTest.java, tantalum-shell/src/test/java/tantalum/shell/model/ClaudeSmokeTest.java
  - 对 strata（qwen）和 Claude 各发一次：历史含工具调用和结果、末尾用户指令、禁用工具、限长 512；判定：200、无工具调用、有文本
  - 验证：两边通过；strata 不可用记为未验证并写进 tech.md
  - _Requirements: 3.1_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“真实服务确认摘要模式请求被接受”，满足需求 3.1，遵守 design.md | Restrictions: 只做这一件事；API Key 只从环境变量读；测试受环境变量门控 | Success: 两边通过；strata 不可用记为未验证并写进 tech.md | 完成后把任务标为 [x] 并用 log-implementation 记录_

## 阶段 1：值对象

- [x] 3. value 新记录与字段
  - File: tantalum-value/src/main/java/tantalum/value/state/Compaction.java, UsageAnchor.java, CompactionSettings.java, Compacting.java, Plan.java, SessionState.java, SessionContext.java, RunState.java, tantalum-value/src/main/java/tantalum/value/event/ContextAssembled.java, CompactRequested.java, tantalum-value/src/main/java/tantalum/value/decision/ContextCompacted.java, CompactionFailed.java
  - 按 design 的 Data Models；`SessionState.view()`；`ContextAssembled.compaction` 可空且保留旧构造器
  - 验证：value 测试全过；`EventCodec` 能读旧格式的 `ContextAssembled`（无 compaction 字段）
  - _Requirements: 4.1_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“value 新记录与字段”，满足需求 4.1，遵守 design.md | Restrictions: 只做这一件事；value 只依赖 JDK；旧事件必须能反序列化 | Success: value 测试全过；EventCodec 能读旧格式的 ContextAssembled | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [x] 4. `EventCodec` 注册 `CompactRequested`，配置项
  - File: tantalum-shell/src/main/java/tantalum/shell/journal/EventCodec.java, tantalum-shell/src/main/java/tantalum/shell/boot/TantalumProperties.java, tantalum-shell/src/main/resources/application.properties, tantalum-shell/src/test/java/tantalum/shell/journal/EventCodecTest.java, tantalum-shell/src/test/java/tantalum/shell/boot/TantalumPropertiesTest.java
  - 五个配置项及默认值；非正数启动失败
  - 验证：往返编码；非法配置的错误指出配置项
  - _Requirements: 1.5_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“EventCodec 注册 CompactRequested，配置项”，满足需求 1.5，遵守 design.md | Restrictions: 只做这一件事 | Success: 往返编码；非法配置的错误指出配置项 | 完成后把任务标为 [x] 并用 log-implementation 记录_

## 阶段 2：内核纯函数

- [x] 5. `Units`、`TokenEstimate` 测试和实现
  - File: tantalum-kernel/src/test/java/tantalum/kernel/compaction/UnitsTest.java, TokenEstimateTest.java, tantalum-kernel/src/main/java/tantalum/kernel/compaction/Units.java, TokenEstimate.java
  - 切单元：用户消息；助手加紧随的工具结果消息。估算：UTF-8 字节 / 3 向上取整，含工具调用参数、工具结果、推理块原始 JSON、工具定义
  - 验证：连续用户消息各自成单元；以结果结尾；空视图；中文估算
  - _Requirements: 6.1（规则 1、4）_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“Units、TokenEstimate 测试和实现”，满足需求 6.1，遵守 design.md | Restrictions: 只做这一件事；纯函数；不引用供应商类型 | Success: 连续用户消息各自成单元；以结果结尾；空视图；中文估算 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [x] 6. `Trimming`、`SummaryPrompt` 测试和实现
  - File: tantalum-kernel/src/test/java/tantalum/kernel/compaction/TrimmingTest.java, SummaryPromptTest.java, tantalum-kernel/src/main/java/tantalum/kernel/compaction/Trimming.java, SummaryPrompt.java
  - 去推理块、按 UTF-8 边界截断并注明原长度、空消息丢弃；指令文本（pi 结构、已省略句、手动说明）；四种校验失败
  - 验证：未超长不动；截断不切坏字符；校验每条各有用例
  - _Requirements: 6.1（规则 5、6、7）_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“Trimming、SummaryPrompt 测试和实现”，满足需求 6.1，遵守 design.md | Restrictions: 只做这一件事；纯函数；Trimming 不看 provider | Success: 未超长不动；截断不切坏字符；校验每条各有用例 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [x] 7. `Compactor` 测试和实现
  - File: tantalum-kernel/src/test/java/tantalum/kernel/compaction/CompactorTest.java, tantalum-kernel/src/main/java/tantalum/kernel/compaction/Compactor.java
  - 规则 1（锚点、无锚点、锚点后新增）、规则 2（等于阈值不触发）、规则 4（K 上限 T/4、最新单元超 K 仍保留、头部为空、OVERFLOW 时 K = 0、MANUAL）、请求识别（排除上下文更新和摘要）
  - 验证：每条规则至少一个用例
  - _Requirements: 6.1（规则 1、2、4）_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“Compactor 测试和实现”，满足需求 6.1，遵守 design.md | Restrictions: 只做这一件事；纯函数 | Success: 每条规则至少一个用例 | 完成后把任务标为 [x] 并用 log-implementation 记录_

## 阶段 3：内核步骤

- [ ] 8. 自动压缩步骤测试（先失败）
  - File: tantalum-kernel/src/test/java/tantalum/kernel/step/CompactStepsTest.java, tantalum-kernel/src/test/java/tantalum/kernel/LoopFixture.java
  - 经 `Kernel.step` 逐步断言：触发后发摘要 CallModel（禁用工具、限长、头部加指令）→ 通过后发普通 CallModel 且不再判断；锚点清空、收到回复后重建；摘要被截断/含工具/为空/没变小 → 继续原调用且本运行不再压缩；取消摘要调用视图不变、运行 CANCELLED；`beforeCompact` Stop；无设置的会话不压缩；回放 prompt 相同
  - 验证：编译通过，因缺实现失败
  - _Requirements: 1.1–1.4, 3.1, 3.2, 3.4, 4.1_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“自动压缩步骤测试（先失败）”，满足需求 1.1–1.4, 3.1, 3.2, 3.4, 4.1，遵守 design.md | Restrictions: 只做这一件事；零 mock | Success: 编译通过，因缺实现失败 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 9. `CompactSteps`、`PromptProjection`、`HookPoint.BEFORE_COMPACT` 实现
  - File: tantalum-kernel/src/main/java/tantalum/kernel/step/CompactSteps.java, ModelSteps.java, tantalum-kernel/src/main/java/tantalum/kernel/projection/PromptProjection.java, tantalum-kernel/src/main/java/tantalum/kernel/hook/HookPoint.java, KernelHook.java, HookChain.java, tantalum-kernel/src/main/java/tantalum/kernel/table/BuiltinSteps.java, tantalum-value/src/main/java/tantalum/value/hook/HookContext.java
  - `AwaitingSummary` 两行注册；`freezeContext` 存设置；`modelReplied` 设锚点；`modelInterrupted` 在压缩中不追加文本
  - 验证：上一任务全过；现有 kernel 测试全过
  - _Requirements: 同上_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“CompactSteps、PromptProjection、BEFORE_COMPACT 实现”，遵守 design.md | Restrictions: 只做这一件事；历史只追加；不引用供应商类型 | Success: 上一任务全过；现有 kernel 测试全过 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 10. 超长恢复和截断标记
  - File: tantalum-kernel/src/test/java/tantalum/kernel/step/CompactStepsTest.java, ModelStepsTest.java, tantalum-kernel/src/main/java/tantalum/kernel/step/RetrySteps.java, ModelSteps.java, RunEnd.java
  - `CONTEXT_OVERFLOW` 且未恢复过 → K = 0 压缩并重试一次；无可压缩或再次超长 → FAILED 带原始错误；单个超大工具结果经截断后重试成功；`MAX_TOKENS` 无工具调用 → COMPLETED 带 `reply truncated`
  - 验证：逐步断言全过
  - _Requirements: 2.1–2.3, 4.4_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“超长恢复和截断标记”，满足需求 2.1–2.3, 4.4，遵守 design.md | Restrictions: 只做这一件事；onFailure 钩子仍先于恢复 | Success: 逐步断言全过 | 完成后把任务标为 [x] 并用 log-implementation 记录_

## 阶段 4：shell 接线

- [ ] 11. 设置快照：`ContextHandler`、`Engine`
  - File: tantalum-shell/src/main/java/tantalum/shell/handler/ContextHandler.java, tantalum-shell/src/main/java/tantalum/shell/boot/Engine.java, tantalum-shell/src/test/java/tantalum/shell/handler/ContextHandlerTest.java
  - `window` 取供应商 `context-window`，`reserve = max(reserve-tokens, 供应商 max-tokens)`；窗口为 0 时为 null
  - 验证：Claude 默认配置得到 136000 的阈值；窗口 0 时事件字段为 null
  - _Requirements: 1.5_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“设置快照：ContextHandler、Engine”，满足需求 1.5，遵守 design.md | Restrictions: 只做这一件事 | Success: Claude 默认配置得到 136000 的阈值；窗口 0 时事件字段为 null | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 12. `Transcript`、`TurnQuery`、`Startup`
  - File: tantalum-shell/src/main/java/tantalum/shell/conversation/Transcript.java, TranscriptItem.java, tantalum-shell/src/main/java/tantalum/shell/journal/TurnQuery.java, tantalum-shell/src/test/java/tantalum/shell/conversation/TranscriptTest.java, tantalum-shell/src/test/java/tantalum/shell/journal/TurnQueryTest.java
  - `CompactionMark` 条目（成功与失败）；`Text.truncated`；轨迹条目挂压缩事实；`handlerOf("AwaitingSummary")`
  - 验证：摘要回复不生成 Text；重启后视图逐字节相同；轨迹里摘要调用是一个 turn
  - _Requirements: 4.2–4.4_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“Transcript、TurnQuery、Startup”，满足需求 4.2–4.4，遵守 design.md | Restrictions: 只做这一件事 | Success: 摘要回复不生成 Text；重启后视图逐字节相同；轨迹里摘要调用是一个 turn | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 13. 压测与回放：`CompactionStressTest`、架构规则
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/CompactionStressTest.java, tantalum-shell/src/test/java/tantalum/shell/ArchitectureTest.java
  - 脚本化模型、窗口设小，500 条消息、多次压缩；回放 prompt 与实时相同；重启后视图相同；另一用户同时运行不受影响；`tantalum.kernel.compaction` 不引用 shell
  - 验证：进 `mvn verify`，全过
  - _Requirements: 6.2, NFR_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“压测与回放”，满足需求 6.2，遵守 design.md | Restrictions: 只做这一件事；不用真实模型 | Success: 进 mvn verify，全过 | 完成后把任务标为 [x] 并用 log-implementation 记录_

## 阶段 5：真实模型

- [ ] 14. 暗号与约束场景（Claude 严格校验 + 本地 qwen）
  - File: tantalum-shell/src/test/java/tantalum/shell/smoke/ClaudeScenarioTest.java, OpenAiScenarioTest.java
  - 窗口 32000，依次读若干文件，各带暗号和约束；越过压缩点后报全部暗号并遵守约束；统计重读次数
  - 验证：暗号全对、约束未违反、重读 ≤ 1；严格校验不 400；strata 不可用记为未验证；用量写进 tech.md
  - _Requirements: 6.3_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“暗号与约束场景”，满足需求 6.3，遵守 design.md | Restrictions: 只做这一件事；两个模型同一用例；判定标准机械断言 | Success: 暗号全对、约束未违反、重读 ≤ 1；严格校验不 400；用量写进 tech.md | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 15. 严格校验下的合法性与代价对比
  - File: tantalum-shell/src/test/java/tantalum/shell/smoke/ClaudeScenarioTest.java, .spec-workflow/steering/tech.md
  - 压缩后继续带 thinking 的多轮工具调用；保留段含 MCP 工具调用；压缩后该 MCP 工具已下线；同一长任务开/关压缩的完成情况与总 token
  - 验证：三个场景不 400；对比结论写进 tech.md
  - _Requirements: 6.4, 6.5_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“严格校验下的合法性与代价对比”，满足需求 6.4, 6.5，遵守 design.md | Restrictions: 只做这一件事；每个场景写明判定标准、结论、用量 | Success: 三个场景不 400；对比结论写进 tech.md | 完成后把任务标为 [x] 并用 log-implementation 记录_

## 阶段 6：手动压缩

- [ ] 16. 手动压缩内核步骤
  - File: tantalum-kernel/src/test/java/tantalum/kernel/step/CompactStepsTest.java, tantalum-kernel/src/main/java/tantalum/kernel/step/CompactSteps.java, BuiltinSteps.java
  - `CompactRequested`：空闲会话开特殊运行，不追加用户消息，不发 AssembleContext；头部为空 FAILED；成功 COMPLETED 并取排队提交；有运行时忽略
  - 验证：逐步断言全过
  - _Requirements: 5.1_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“手动压缩内核步骤”，满足需求 5.1，遵守 design.md | Restrictions: 只做这一件事 | Success: 逐步断言全过 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 17. 接口 `POST /api/sessions/{id}/compact`
  - File: tantalum-shell/src/main/java/tantalum/shell/web/ApiController.java, tantalum-shell/src/main/java/tantalum/shell/boot/Engine.java, tantalum-shell/src/test/java/tantalum/shell/web/ApiControllerTest.java
  - 200/404/409（运行中、无可压缩、无设置）/429/503；`Origin` 校验；可带 `instructions`
  - 验证：每个状态码有用例；真实 Claude 手动压缩一次，摘要含用户说明的重点
  - _Requirements: 5.1_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“接口 POST /api/sessions/{id}/compact”，满足需求 5.1，遵守 design.md | Restrictions: 只做这一件事；复用 ExternalGate 和 SubmissionLedger | Success: 每个状态码有用例；真实 Claude 手动压缩一次，摘要含用户说明的重点 | 完成后把任务标为 [x] 并用 log-implementation 记录_

## 阶段 7：控制台

- [ ] 18. 对话页与状态栏
  - File: console/src/conversation/CompactionMark.tsx, MessageList.tsx, AssistantText.tsx, console/src/composer/StatusBar.tsx, status.ts, console/src/api/client.ts, types.ts, console/src/text/zh.ts, 对应 *.test.tsx
  - 分隔条目可展开摘要、失败显示原因；`truncated` 提示；占比 ≥ 80% 提示；压缩按钮（运行中禁用）
  - 验证：vitest 全过
  - _Requirements: 4.2, 4.4, 5.2_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: React 19 / TypeScript 工程师 | Task: 完成“对话页与状态栏”，满足需求 4.2, 4.4, 5.2，遵守 design.md | Restrictions: 只做这一件事；文案进 zh.ts | Success: vitest 全过 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 19. 轨迹页与 Playwright
  - File: console/src/trace/*.tsx, console/e2e/compaction.spec.ts, tantalum-examples/src/test/java/tantalum/examples/E2eModel.java
  - 轨迹里的压缩步骤（耗时、用量、前后估算）；`E2eModel` 按关键词返回摘要；覆盖分隔标记、摘要展开、轨迹步骤、截断提示、手动压缩、500 条消息会话
  - 验证：`pnpm e2e` 全过
  - _Requirements: 4.3, 6.6_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: React 19 / TypeScript 工程师 | Task: 完成“轨迹页与 Playwright”，满足需求 4.3, 6.6，遵守 design.md | Restrictions: 只做这一件事 | Success: pnpm e2e 全过 | 完成后把任务标为 [x] 并用 log-implementation 记录_

## 阶段 8：收尾

- [ ] 20. 文档与全量验证
  - File: README.md, .spec-workflow/steering/tech.md
  - README 能力对照表更新压缩一行；tech.md 记录决定、实测结论和用量
  - 验证：`mvn verify`、vitest、Playwright 全过；提交前密钥扫描
  - _Requirements: 全部_
  - _Prompt: Implement the task for spec context-compaction, first run spec-workflow-guide to get the workflow guide then implement the task: Role: 维护者 | Task: 完成“文档与全量验证”，遵守 design.md | Restrictions: 只做这一件事 | Success: 三套测试全过；文档更新 | 完成后把任务标为 [x] 并用 log-implementation 记录_
