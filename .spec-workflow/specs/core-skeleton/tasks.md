# Tasks Document

工作方式：一次只做一个 task，完成后汇报改了什么、验证结果、是否偏离 spec、下一步，再继续。阶段 0 任何一项失败都停下汇报。提交代码要等 Yi 要求。

## 阶段 0：先验证依赖（失败就停下汇报）

- [x] 1. 建立 Maven 父工程和四个空模块
  - File: pom.xml, tantalum-*/pom.xml
  - 验证：`mvn -q verify` 通过；依赖方向由模块声明体现
  - _Requirements: NFR 架构_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“建立 Maven 父工程和四个空模块”，满足需求 NFR 架构，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: `mvn -q verify` 通过；依赖方向由模块声明体现_

- [x] 2. Disruptor 在 Java 25 上的冒烟测试
  - File: tantalum-shell/src/test/java/tantalum/shell/spike/DisruptorSpikeTest.java
  - 验证：`tryPublishEvent` 满时返回 false；后一消费者依赖前者序列时，只有批尾处理完才看到事件
  - _Requirements: 5.1, 3.1_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“Disruptor 在 Java 25 上的冒烟测试”，满足需求 5.1, 3.1，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: `tryPublishEvent` 满时返回 false；后一消费者依赖前者序列时，只有批尾处理完才看到事件_

- [x] 3. SQLite 在 Java 25 和 macOS arm64 上的冒烟测试
  - File: tantalum-shell/src/test/java/tantalum/shell/spike/SqliteSpikeTest.java
  - 验证：WAL + `synchronous=FULL` 下事务提交后重开可读
  - _Requirements: 3.1_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“SQLite 在 Java 25 和 macOS arm64 上的冒烟测试”，满足需求 3.1，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: WAL + `synchronous=FULL` 下事务提交后重开可读_

- [x] 4. `anthropic-java` 与 Spring Boot 依赖检查
  - File: tantalum-shell/pom.xml, tantalum-shell/src/test/java/tantalum/shell/spike/ClaudeSdkSpikeTest.java
  - 验证：无依赖冲突；SDK 客户端可构造并关闭重试，不发网络请求
  - _Requirements: 9.3_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`anthropic-java` 与 Spring Boot 依赖检查”，满足需求 9.3，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 无依赖冲突；SDK 客户端可构造并关闭重试，不发网络请求_

## 阶段 1：分层守护

- [x] 5. 写分层守护测试
  - File: tantalum-shell/src/test/java/tantalum/shell/ArchitectureTest.java
  - 验证：规则全部生效；各放一个违规样例确认会失败，再删除样例
  - _Requirements: 1.1, 1.2, 1.3, 1.5_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“写分层守护测试”，满足需求 1.1, 1.2, 1.3, 1.5，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 规则全部生效；各放一个违规样例确认会失败，再删除样例_

- [x] 6. 把覆盖率和编译告警接入构建
  - File: pom.xml
  - 验证：JaCoCo 80% 门槛生效；`-Xlint:all` 开启
  - _Requirements: NFR 可靠性_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“把覆盖率和编译告警接入构建”，满足需求 NFR 可靠性，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: JaCoCo 80% 门槛生效；`-Xlint:all` 开启_

## 阶段 2：value 层

- [x] 7. 标识类型
  - File: tantalum-value/src/main/java/tantalum/value/id/
  - 验证：`mvn -pl tantalum-value test`；空值被拒绝
  - _Requirements: 7.1_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“标识类型”，满足需求 7.1，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: `mvn -pl tantalum-value test`；空值被拒绝_

- [x] 8. `Json` 值类型
  - File: tantalum-value/src/main/java/tantalum/value/json/Json.java, tantalum-value/src/test/java/tantalum/value/json/JsonTest.java
  - 验证：相等性和不可变性测试通过
  - _Requirements: 9.9_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Json` 值类型”，满足需求 9.9，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 相等性和不可变性测试通过_

- [x] 9. 消息和内容块
  - File: tantalum-value/src/main/java/tantalum/value/model/Message.java, tantalum-value/src/main/java/tantalum/value/model/ContentBlock.java
  - 验证：record 不可变；`ReasoningBlock` 记录来源供应商
  - _Requirements: 9.8_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“消息和内容块”，满足需求 9.8，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: record 不可变；`ReasoningBlock` 记录来源供应商_

- [x] 10. `Prompt`、`ToolSpec`、用量和原因类型
  - File: tantalum-value/src/main/java/tantalum/value/model/Prompt.java, tantalum-value/src/main/java/tantalum/value/model/ToolSpec.java, tantalum-value/src/main/java/tantalum/value/model/TokenUsage.java
  - 验证：编译通过；含 `StopReason`、`FailureReason`
  - _Requirements: 3.4, 9.4, 9.8_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Prompt`、`ToolSpec`、用量和原因类型”，满足需求 3.4, 9.4, 9.8，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 编译通过；含 `StopReason`、`FailureReason`_

- [x] 11. `Event` 接口和 `EventEnvelope`
  - File: tantalum-value/src/main/java/tantalum/value/event/Event.java, tantalum-value/src/main/java/tantalum/value/event/EventEnvelope.java
  - 验证：事件带类型名和版本
  - _Requirements: 3.2, 3.6_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Event` 接口和 `EventEnvelope`”，满足需求 3.2, 3.6，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 事件带类型名和版本_

- [x] 12. 外部事件
  - File: tantalum-value/src/main/java/tantalum/value/event/UserPrompt.java, tantalum-value/src/main/java/tantalum/value/event/SteerSubmitted.java, tantalum-value/src/main/java/tantalum/value/event/FollowUpSubmitted.java, tantalum-value/src/main/java/tantalum/value/event/Cancel.java
  - 验证：`UserPrompt` 带 `principal`、`owner`、`requestId`
  - _Requirements: 7.5, 10.2, 10.3_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“外部事件”，满足需求 7.5, 10.2, 10.3，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: `UserPrompt` 带 `principal`、`owner`、`requestId`_

- [x] 13. 结果事件（模型）
  - File: tantalum-value/src/main/java/tantalum/value/event/ContextAssembled.java, tantalum-value/src/main/java/tantalum/value/event/ModelReplied.java, tantalum-value/src/main/java/tantalum/value/event/ModelFailed.java, tantalum-value/src/main/java/tantalum/value/event/ModelInterrupted.java
  - 验证：字段与 design 一致
  - _Requirements: 9.2, 9.3, 9.4, 9.8_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“结果事件（模型）”，满足需求 9.2, 9.3, 9.4, 9.8，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 字段与 design 一致_

- [x] 14. 结果事件（其他）
  - File: tantalum-value/src/main/java/tantalum/value/event/ToolFinished.java, tantalum-value/src/main/java/tantalum/value/event/TimerFired.java, tantalum-value/src/main/java/tantalum/value/event/WorkDone.java
  - 验证：都带 `CommandId`
  - _Requirements: 7.2_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“结果事件（其他）”，满足需求 7.2，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 都带 `CommandId`_

- [x] 15. `Command` 和内置命令
  - File: tantalum-value/src/main/java/tantalum/value/command/
  - 验证：六个内置命令，都带 `CommandId`
  - _Requirements: 7.1, 9.1_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Command` 和内置命令”，满足需求 7.1, 9.1，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 六个内置命令，都带 `CommandId`_

- [x] 16. 状态：`RunPhase`、`EndReason`、`OwnerRef`
  - File: tantalum-value/src/main/java/tantalum/value/state/RunPhase.java, tantalum-value/src/main/java/tantalum/value/state/EndReason.java, tantalum-value/src/main/java/tantalum/value/state/OwnerRef.java
  - 验证：三个固定状态
  - _Requirements: 2.2_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“状态：`RunPhase`、`EndReason`、`OwnerRef`”，满足需求 2.2，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 三个固定状态_

- [x] 17. 状态：`ToolRound` 和 `ToolSlot`
  - File: tantalum-value/src/main/java/tantalum/value/state/ToolRound.java, tantalum-value/src/main/java/tantalum/value/state/ToolSlot.java
  - 验证：四种进度状态
  - _Requirements: 5.4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“状态：`ToolRound` 和 `ToolSlot`”，满足需求 5.4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 四种进度状态_

- [x] 18. 状态树：`RunState`、`SessionState`、`PrincipalState`、`KernelState`
  - File: tantalum-value/src/main/java/tantalum/value/state/
  - 验证：更新返回新对象，旧对象不变
  - _Requirements: 2.1, 6.6_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“状态树：`RunState`、`SessionState`、`PrincipalState`、`KernelState`”，满足需求 2.1, 6.6，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 更新返回新对象，旧对象不变_

- [x] 19. `Decision`、`Fact` 和钩子结果
  - File: tantalum-value/src/main/java/tantalum/value/decision/, tantalum-value/src/main/java/tantalum/value/hook/
  - 验证：`Allow` 可带 `PromptPatch`
  - _Requirements: 2.1, 6.1, 6.2_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Decision`、`Fact` 和钩子结果”，满足需求 2.1, 6.1, 6.2，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: `Allow` 可带 `PromptPatch`_

## 阶段 3：kernel 骨架

- [x] 20. 转移表测试
  - File: tantalum-kernel/src/test/java/tantalum/kernel/table/TransitionTableTest.java
  - 验证：先失败：查找、撞行报错、内置行不可覆盖
  - _Requirements: 2.3, 2.4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“转移表测试”，满足需求 2.3, 2.4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 先失败：查找、撞行报错、内置行不可覆盖_

- [x] 21. 实现 `TransitionTable` 和 `StepFunction`
  - File: tantalum-kernel/src/main/java/tantalum/kernel/table/TransitionTable.java, tantalum-kernel/src/main/java/tantalum/kernel/table/StepFunction.java
  - 验证：上一个测试通过
  - _Requirements: 2.3, 2.4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“实现 `TransitionTable` 和 `StepFunction`”，满足需求 2.3, 2.4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 上一个测试通过_

- [x] 22. `Kernel.step` 测试
  - File: tantalum-kernel/src/test/java/tantalum/kernel/KernelTest.java
  - 验证：先失败：确定性、过期 `cmdId` 不改状态、异常只让该运行失败
  - _Requirements: 2.1, 2.6, 2.7_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Kernel.step` 测试”，满足需求 2.1, 2.6, 2.7，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 先失败：确定性、过期 `cmdId` 不改状态、异常只让该运行失败_

- [x] 23. 实现 `Kernel` 和 `CommandIds`
  - File: tantalum-kernel/src/main/java/tantalum/kernel/Kernel.java, tantalum-kernel/src/main/java/tantalum/kernel/step/CommandIds.java
  - 验证：上一个测试通过；ID 可重复算出
  - _Requirements: 2.1, 2.6, 2.7, 7.1_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“实现 `Kernel` 和 `CommandIds`”，满足需求 2.1, 2.6, 2.7, 7.1，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 上一个测试通过；ID 可重复算出_

- [x] 24. 钩子链测试
  - File: tantalum-kernel/src/test/java/tantalum/kernel/hook/HookChainTest.java
  - 验证：先失败：顺序、`Stop` 中断、`mandatory` 异常、可选异常上报、`beforeToolCall` 异常拦截
  - _Requirements: 6.1–6.5_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“钩子链测试”，满足需求 6.1–6.5，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 先失败：顺序、`Stop` 中断、`mandatory` 异常、可选异常上报、`beforeToolCall` 异常拦截_

- [x] 25. 实现 `KernelHook` 和 `HookChain`
  - File: tantalum-kernel/src/main/java/tantalum/kernel/hook/KernelHook.java, tantalum-kernel/src/main/java/tantalum/kernel/hook/HookChain.java
  - 验证：上一个测试通过
  - _Requirements: 6.1–6.5, D3, D4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“实现 `KernelHook` 和 `HookChain`”，满足需求 6.1–6.5, D3, D4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 上一个测试通过_

- [x] 26. `PromptProjection` 测试和实现
  - File: tantalum-kernel/src/test/java/tantalum/kernel/projection/PromptProjectionTest.java, tantalum-kernel/src/main/java/tantalum/kernel/projection/PromptProjection.java
  - 验证：含 `PromptPatch`、推理内容按原样回传
  - _Requirements: 3.4, 9.8_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`PromptProjection` 测试和实现”，满足需求 3.4, 9.8，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 含 `PromptPatch`、推理内容按原样回传_

- [x] 27. `UsageProjection` 测试和实现
  - File: tantalum-kernel/src/test/java/tantalum/kernel/projection/UsageProjectionTest.java, tantalum-kernel/src/main/java/tantalum/kernel/projection/UsageProjection.java
  - 验证：按用户、按事件日期累加；用量为空时不累加
  - _Requirements: 6.6, 9.8_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`UsageProjection` 测试和实现”，满足需求 6.6, 9.8，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 按用户、按事件日期累加；用量为空时不累加_

## 阶段 4：kernel 转移

- [x] 28. 模型步骤测试
  - File: tantalum-kernel/src/test/java/tantalum/kernel/step/ModelStepsTest.java
  - 验证：先失败：提问、组装、有或无工具、`steer`、结束钩子
  - _Requirements: 2.5_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“模型步骤测试”，满足需求 2.5，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 先失败：提问、组装、有或无工具、`steer`、结束钩子_

- [x] 29. 实现 `ModelSteps`
  - File: tantalum-kernel/src/main/java/tantalum/kernel/step/ModelSteps.java
  - 验证：上一个测试通过
  - _Requirements: 2.5, 6.3_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“实现 `ModelSteps`”，满足需求 2.5, 6.3，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 上一个测试通过_

- [x] 30. `ToolScheduler` 测试
  - File: tantalum-kernel/src/test/java/tantalum/kernel/tool/ToolSchedulerTest.java
  - 验证：先失败：并行组、独占屏障、上限、声明异常按独占
  - _Requirements: 5.4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ToolScheduler` 测试”，满足需求 5.4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 先失败：并行组、独占屏障、上限、声明异常按独占_

- [x] 31. 实现 `ToolScheduler` 和 `ToolSafety`
  - File: tantalum-kernel/src/main/java/tantalum/kernel/tool/ToolScheduler.java, tantalum-kernel/src/main/java/tantalum/kernel/tool/ToolSafety.java
  - 验证：上一个测试通过
  - _Requirements: 5.4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“实现 `ToolScheduler` 和 `ToolSafety`”，满足需求 5.4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 上一个测试通过_

- [x] 32. 工具步骤测试
  - File: tantalum-kernel/src/test/java/tantalum/kernel/step/ToolStepsTest.java
  - 验证：先失败：结果按模型顺序、拦截、无效参数、工具错误不算 `ModelFailed`
  - _Requirements: 2.5, 6.3, 8.3, 9.9_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“工具步骤测试”，满足需求 2.5, 6.3, 8.3, 9.9，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 先失败：结果按模型顺序、拦截、无效参数、工具错误不算 `ModelFailed`_

- [x] 33. 实现 `ToolSteps`
  - File: tantalum-kernel/src/main/java/tantalum/kernel/step/ToolSteps.java
  - 验证：上一个测试通过
  - _Requirements: 2.5, 8.3, 9.9_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“实现 `ToolSteps`”，满足需求 2.5, 8.3, 9.9，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 上一个测试通过_

- [x] 34. 重试步骤测试和实现
  - File: tantalum-kernel/src/test/java/tantalum/kernel/step/RetryStepsTest.java, tantalum-kernel/src/main/java/tantalum/kernel/step/RetrySteps.java
  - 验证：最多 3 次；`retryAfter` 下限；`context_overflow`、`refusal` 不重试；`NeedWork` 进 `AwaitingWork`
  - _Requirements: 2.5, 9.4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“重试步骤测试和实现”，满足需求 2.5, 9.4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 最多 3 次；`retryAfter` 下限；`context_overflow`、`refusal` 不重试；`NeedWork` 进 `AwaitingWork`_

- [x] 35. 工作步骤测试和实现
  - File: tantalum-kernel/src/test/java/tantalum/kernel/step/WorkStepsTest.java, tantalum-kernel/src/main/java/tantalum/kernel/step/WorkSteps.java
  - 验证：`WorkDone` 按 `resumeAt` 回到原处；回到 `beforeRunEnd`
  - _Requirements: 2.5, 6.4, A2_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“工作步骤测试和实现”，满足需求 2.5, 6.4, A2，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: `WorkDone` 按 `resumeAt` 回到原处；回到 `beforeRunEnd`_

- [x] 36. `ToolPairing` 测试和实现
  - File: tantalum-kernel/src/test/java/tantalum/kernel/tool/ToolPairingTest.java, tantalum-kernel/src/main/java/tantalum/kernel/tool/ToolPairing.java
  - 验证：两种合成错误码；迟到结果不改历史
  - _Requirements: 2.6_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ToolPairing` 测试和实现”，满足需求 2.6，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 两种合成错误码；迟到结果不改历史_

- [x] 37. 取消步骤测试和实现
  - File: tantalum-kernel/src/test/java/tantalum/kernel/step/CancelStepsTest.java, tantalum-kernel/src/main/java/tantalum/kernel/step/CancelSteps.java
  - 验证：发 `AbortCommands`；`ModelInterrupted` 只改会话消息
  - _Requirements: 2.5, 10.3, A1_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“取消步骤测试和实现”，满足需求 2.5, 10.3, A1，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 发 `AbortCommands`；`ModelInterrupted` 只改会话消息_

- [x] 38. 注册内置行：`BuiltinSteps`
  - File: tantalum-kernel/src/main/java/tantalum/kernel/table/BuiltinSteps.java, tantalum-kernel/src/test/java/tantalum/kernel/table/BuiltinStepsTest.java
  - 验证：需求 2 表中每一行都有处理函数
  - _Requirements: 2.5_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“注册内置行：`BuiltinSteps`”，满足需求 2.5，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 需求 2 表中每一行都有处理函数_

- [x] 39. `follow-up` 开新运行
  - File: tantalum-kernel/src/test/java/tantalum/kernel/step/FollowUpTest.java, tantalum-kernel/src/main/java/tantalum/kernel/step/ModelSteps.java
  - 验证：结束后取一条排队提交开新运行
  - _Requirements: 2.5, 10.2_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`follow-up` 开新运行”，满足需求 2.5, 10.2，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 结束后取一条排队提交开新运行_

- [x] 40. 写 `TheLoopTest`
  - File: tantalum-kernel/src/test/java/tantalum/kernel/TheLoopTest.java
  - 验证：十圈逐圈断言通过；改坏回边后第 4 圈失败，再改回
  - _Requirements: 8.1, 8.2_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“写 `TheLoopTest`”，满足需求 8.1, 8.2，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 十圈逐圈断言通过；改坏回边后第 4 圈失败，再改回_

- [x] 41. `Replay` 测试和实现
  - File: tantalum-kernel/src/test/java/tantalum/kernel/ReplayTest.java, tantalum-kernel/src/main/java/tantalum/kernel/Replay.java
  - 验证：无线程、无 I/O；状态和每圈 prompt 与原运行一致；命令不执行
  - _Requirements: 3.3, 3.4, 3.5_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Replay` 测试和实现”，满足需求 3.3, 3.4, 3.5，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 无线程、无 I/O；状态和每圈 prompt 与原运行一致；命令不执行_

- [x] 42. `Kernel.step` 基准记录
  - File: tantalum-kernel/src/test/java/tantalum/kernel/KernelStepBenchmarkTest.java
  - 验证：记录 P99；超过 5 ms 时汇报，不放宽门槛
  - _Requirements: NFR 性能_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Kernel.step` 基准记录”，满足需求 NFR 性能，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 记录 P99；超过 5 ms 时汇报，不放宽门槛_

## 阶段 5：journal

- [x] 43. `EventCodec` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/journal/EventCodecTest.java, tantalum-shell/src/main/java/tantalum/shell/journal/EventCodec.java
  - 验证：全部内置事件往返不变；未知类型或版本报错
  - _Requirements: 3.6, 3.7_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`EventCodec` 测试和实现”，满足需求 3.6, 3.7，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 全部内置事件往返不变；未知类型或版本报错_

- [x] 44. `StoreLock` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/journal/StoreLockTest.java, tantalum-shell/src/main/java/tantalum/shell/journal/StoreLock.java
  - 验证：第二次取锁失败
  - _Requirements: 4.3_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`StoreLock` 测试和实现”，满足需求 4.3，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 第二次取锁失败_

- [x] 45. `Journal` 测试
  - File: tantalum-shell/src/test/java/tantalum/shell/journal/JournalTest.java
  - 验证：先失败：批量提交、序号连续、损坏时指出位置、文件权限 600
  - _Requirements: 3.1, 3.7, NFR 安全_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Journal` 测试”，满足需求 3.1, 3.7, NFR 安全，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 先失败：批量提交、序号连续、损坏时指出位置、文件权限 600_

- [x] 46. 实现 `Journal`
  - File: tantalum-shell/src/main/java/tantalum/shell/journal/Journal.java
  - 验证：上一个测试通过
  - _Requirements: 3.1, 3.7, NFR 安全_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“实现 `Journal`”，满足需求 3.1, 3.7, NFR 安全，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 上一个测试通过_

## 阶段 6：环

- [x] 47. 容量校验测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/boot/TantalumPropertiesTest.java, tantalum-shell/src/main/java/tantalum/shell/boot/TantalumProperties.java
  - 验证：不满足公式或不是 2 的幂时启动失败
  - _Requirements: 5.3_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“容量校验测试和实现”，满足需求 5.3，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 不满足公式或不是 2 的幂时启动失败_

- [x] 48. `InputRing`、`OutputRing`
  - File: tantalum-shell/src/main/java/tantalum/shell/loop/InputRing.java, tantalum-shell/src/main/java/tantalum/shell/loop/OutputRing.java, tantalum-shell/src/test/java/tantalum/shell/loop/RingTest.java
  - 验证：满时返回 false，绝不等待
  - _Requirements: 5.1, 5.3_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`InputRing`、`OutputRing`”，满足需求 5.1, 5.3，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 满时返回 false，绝不等待_

- [x] 49. `JournalWriter` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/JournalWriterTest.java, tantalum-shell/src/main/java/tantalum/shell/loop/JournalWriter.java
  - 验证：批尾提交；写入失败时停止处理
  - _Requirements: 3.1, 3.2, 3.8_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`JournalWriter` 测试和实现”，满足需求 3.1, 3.2, 3.8，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 批尾提交；写入失败时停止处理_

- [x] 50. `Cleanroom` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/CleanroomTest.java, tantalum-shell/src/main/java/tantalum/shell/loop/Cleanroom.java, tantalum-shell/src/main/java/tantalum/shell/loop/StateView.java
  - 验证：按序调内核；发布失败时运行进入 `Ended(Failed)`
  - _Requirements: 3.1, 5.2_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Cleanroom` 测试和实现”，满足需求 3.1, 5.2，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 按序调内核；发布失败时运行进入 `Ended(Failed)`_

- [x] 51. `InFlightCommands` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/InFlightCommandsTest.java, tantalum-shell/src/main/java/tantalum/shell/loop/InFlightCommands.java
  - 验证：中断指定命令；完成后移除，不泄漏
  - _Requirements: 9.6_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`InFlightCommands` 测试和实现”，满足需求 9.6，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 中断指定命令；完成后移除，不泄漏_

- [x] 52. `Dispatcher` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/DispatcherTest.java, tantalum-shell/src/main/java/tantalum/shell/loop/Dispatcher.java, tantalum-shell/src/main/java/tantalum/shell/handler/Handler.java
  - 验证：挂起的 Handler 不占消费者线程；Handler 异常转成失败事件
  - _Requirements: 5.3, 9.1, 9.5_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Dispatcher` 测试和实现”，满足需求 5.3, 9.1, 9.5，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 挂起的 Handler 不占消费者线程；Handler 异常转成失败事件_

- [x] 53. `ResultPublisher` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/ResultPublisherTest.java, tantalum-shell/src/main/java/tantalum/shell/loop/ResultPublisher.java
  - 验证：迟到结果在容量不足时丢弃并记录错误
  - _Requirements: 5.1, 5.2, A3_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ResultPublisher` 测试和实现”，满足需求 5.1, 5.2, A3，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 迟到结果在容量不足时丢弃并记录错误_

- [x] 54. 用假 Handler 打通整条环
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/RingIntegrationTest.java
  - 验证：事件先落盘再处理；内外事件全局有序
  - _Requirements: 3.1, 5.5_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“用假 Handler 打通整条环”，满足需求 3.1, 5.5，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 事件先落盘再处理；内外事件全局有序_

## 阶段 7：Handler

- [x] 55. `CallLimiter` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/handler/CallLimiterTest.java, tantalum-shell/src/main/java/tantalum/shell/handler/CallLimiter.java
  - 验证：不超上限；等待可超时、可中断；异常后名额归还
  - _Requirements: 9.10, 9.12_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`CallLimiter` 测试和实现”，满足需求 9.10, 9.12，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 不超上限；等待可超时、可中断；异常后名额归还_

- [x] 56. `TimerHandler`
  - File: tantalum-shell/src/test/java/tantalum/shell/handler/TimerHandlerTest.java, tantalum-shell/src/main/java/tantalum/shell/handler/TimerHandler.java
  - 验证：到时发 `TimerFired`；可中断
  - _Requirements: 9.1, 9.4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`TimerHandler`”，满足需求 9.1, 9.4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 到时发 `TimerFired`；可中断_

- [x] 57. `ContextHandler`
  - File: tantalum-shell/src/test/java/tantalum/shell/handler/ContextHandlerTest.java, tantalum-shell/src/main/java/tantalum/shell/handler/ContextHandler.java
  - 验证：读 `AGENTS.md`；system prompt 和工具列表进事件
  - _Requirements: 9.2_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ContextHandler`”，满足需求 9.2，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 读 `AGENTS.md`；system prompt 和工具列表进事件_

- [x] 58. 插件接口
  - File: tantalum-shell/src/main/java/tantalum/shell/plugin/Tool.java, tantalum-shell/src/main/java/tantalum/shell/plugin/WorkType.java, tantalum-shell/src/main/java/tantalum/shell/plugin/AfterToolHook.java
  - 验证：未声明上限时为 1
  - _Requirements: 6.7, 9.11_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“插件接口”，满足需求 6.7, 9.11，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 未声明上限时为 1_

- [x] 59. `Workspace` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/tool/WorkspaceTest.java, tantalum-shell/src/main/java/tantalum/shell/tool/Workspace.java
  - 验证：拒绝 `..` 和符号链接越界
  - _Requirements: 9.7, NFR 安全_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Workspace` 测试和实现”，满足需求 9.7, NFR 安全，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 拒绝 `..` 和符号链接越界_

- [x] 60. `ReadFileTool`
  - File: tantalum-shell/src/test/java/tantalum/shell/tool/ReadFileToolTest.java, tantalum-shell/src/main/java/tantalum/shell/tool/ReadFileTool.java
  - 验证：限制在工作目录内
  - _Requirements: 9.7_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ReadFileTool`”，满足需求 9.7，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 限制在工作目录内_

- [x] 61. `GrepTool`
  - File: tantalum-shell/src/test/java/tantalum/shell/tool/GrepToolTest.java, tantalum-shell/src/main/java/tantalum/shell/tool/GrepTool.java
  - 验证：不经过 shell；含特殊字符的参数安全
  - _Requirements: 9.7, NFR 安全_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`GrepTool`”，满足需求 9.7, NFR 安全，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 不经过 shell；含特殊字符的参数安全_

- [x] 62. `ToolCatalog`
  - File: tantalum-shell/src/test/java/tantalum/shell/tool/ToolCatalogTest.java, tantalum-shell/src/main/java/tantalum/shell/tool/ToolCatalog.java
  - 验证：重名报错；提供 `ToolSpec` 和 `ToolSafety`
  - _Requirements: 5.4, 9.11_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ToolCatalog`”，满足需求 5.4, 9.11，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 重名报错；提供 `ToolSpec` 和 `ToolSafety`_

- [x] 63. `ToolHandler` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/handler/ToolHandlerTest.java, tantalum-shell/src/main/java/tantalum/shell/handler/ToolHandler.java
  - 验证：超时和失败变成错误结果；不重试；`afterToolExecution` 运行
  - _Requirements: 6.7, 9.11–9.13_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ToolHandler` 测试和实现”，满足需求 6.7, 9.11–9.13，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 超时和失败变成错误结果；不重试；`afterToolExecution` 运行_

- [x] 64. 工具服务端并发测试
  - File: tantalum-shell/src/test/java/tantalum/shell/handler/SingleRequestServer.java, tantalum-shell/src/test/java/tantalum/shell/handler/ToolServerConcurrencyTest.java
  - 验证：不超上限、顺序正确、其他运行不受影响、多圈无名额泄漏
  - _Requirements: 9.14_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“工具服务端并发测试”，满足需求 9.14，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 不超上限、顺序正确、其他运行不受影响、多圈无名额泄漏_

- [x] 65. `WorkHandler`
  - File: tantalum-shell/src/test/java/tantalum/shell/handler/WorkHandlerTest.java, tantalum-shell/src/main/java/tantalum/shell/handler/WorkHandler.java
  - 验证：按类型分发；未知类型产生失败结果
  - _Requirements: 6.4, 9.1_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`WorkHandler`”，满足需求 6.4, 9.1，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 按类型分发；未知类型产生失败结果_

## 阶段 8：模型接入

- [x] 66. `ModelClient`、`ChunkListener`、`ModelOutcome`
  - File: tantalum-shell/src/main/java/tantalum/shell/model/ModelClient.java, tantalum-shell/src/main/java/tantalum/shell/model/ChunkListener.java, tantalum-shell/src/main/java/tantalum/shell/model/ModelOutcome.java
  - 验证：编译通过
  - _Requirements: 9.3_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ModelClient`、`ChunkListener`、`ModelOutcome`”，满足需求 9.3，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 编译通过_

- [x] 67. `StreamWatchdog`
  - File: tantalum-shell/src/test/java/tantalum/shell/model/StreamWatchdogTest.java, tantalum-shell/src/main/java/tantalum/shell/model/StreamWatchdog.java
  - 验证：首块和块间超时分别生效；活动信号重置计时
  - _Requirements: 9.3_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`StreamWatchdog`”，满足需求 9.3，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 首块和块间超时分别生效；活动信号重置计时_

- [x] 68. `OverflowRules`
  - File: tantalum-shell/src/test/java/tantalum/shell/model/OverflowRulesTest.java, tantalum-shell/src/main/java/tantalum/shell/model/OverflowRules.java
  - 验证：匹配 strata 和 Claude 的文本；限流错误不误判
  - _Requirements: 9.4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`OverflowRules`”，满足需求 9.4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 匹配 strata 和 Claude 的文本；限流错误不误判_

- [x] 69. `OpenAiClient` 解析测试
  - File: tantalum-shell/src/test/java/tantalum/shell/model/OpenAiClientTest.java
  - 验证：先失败，用本地替身 HTTP 服务：`keep-alive`、工具分片、`null` 内容、用量、错误
  - _Requirements: 9.3, 9.8, 9.9_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`OpenAiClient` 解析测试”，满足需求 9.3, 9.8, 9.9，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 先失败，用本地替身 HTTP 服务：`keep-alive`、工具分片、`null` 内容、用量、错误_

- [x] 70. 实现 `OpenAiClient`
  - File: tantalum-shell/src/main/java/tantalum/shell/model/OpenAiClient.java
  - 验证：上一个测试通过；断开时能取消
  - _Requirements: 9.3, 9.8, 9.9_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“实现 `OpenAiClient`”，满足需求 9.3, 9.8, 9.9，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 上一个测试通过；断开时能取消_

- [x] 71. `ClaudeClient` 解析测试
  - File: tantalum-shell/src/test/java/tantalum/shell/model/ClaudeClientTest.java
  - 验证：先失败，用本地替身 HTTP 服务：`thinking` 签名、工具输入校验、`refusal`、`retry-after`
  - _Requirements: 9.3, 9.4, 9.8_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ClaudeClient` 解析测试”，满足需求 9.3, 9.4, 9.8，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 先失败，用本地替身 HTTP 服务：`thinking` 签名、工具输入校验、`refusal`、`retry-after`_

- [x] 72. 实现 `ClaudeClient`
  - File: tantalum-shell/src/main/java/tantalum/shell/model/ClaudeClient.java
  - 验证：上一个测试通过；按官方文档核对 SDK 用法；重试为 0
  - _Requirements: 9.3, 9.8_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“实现 `ClaudeClient`”，满足需求 9.3, 9.8，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 上一个测试通过；按官方文档核对 SDK 用法；重试为 0_

- [x] 73. `ModelHandler` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/handler/ModelHandlerTest.java, tantalum-shell/src/main/java/tantalum/shell/handler/ModelHandler.java
  - 验证：分片只进 SSE；取消时发 `ModelInterrupted`；日志不含 Key
  - _Requirements: 9.3, 9.4, 9.10_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ModelHandler` 测试和实现”，满足需求 9.3, 9.4, 9.10，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 分片只进 SSE；取消时发 `ModelInterrupted`；日志不含 Key_

## 阶段 9：Web、SSE、启动

- [x] 74. `SseHub` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/sse/SseHubTest.java, tantalum-shell/src/main/java/tantalum/shell/sse/SseHub.java
  - 验证：用户隔离；慢订阅者不阻塞；可补读
  - _Requirements: 10.4–10.6_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`SseHub` 测试和实现”，满足需求 10.4–10.6，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 用户隔离；慢订阅者不阻塞；可补读_

- [x] 75. `SubmissionLedger` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/SubmissionLedgerTest.java, tantalum-shell/src/main/java/tantalum/shell/loop/SubmissionLedger.java
  - 验证：并发重复只有一次生效
  - _Requirements: 7.3_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`SubmissionLedger` 测试和实现”，满足需求 7.3，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 并发重复只有一次生效_

- [x] 76. `ExternalGate` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/web/ExternalGateTest.java, tantalum-shell/src/main/java/tantalum/shell/web/ExternalGate.java
  - 验证：超限 429；不影响其他用户；停止状态返回 503
  - _Requirements: 5.5, 5.6_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ExternalGate` 测试和实现”，满足需求 5.5, 5.6，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 超限 429；不影响其他用户；停止状态返回 503_

- [x] 77. `TurnQuery` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/journal/TurnQueryTest.java, tantalum-shell/src/main/java/tantalum/shell/journal/TurnQuery.java
  - 验证：返回某一圈的完整 prompt 和回复
  - _Requirements: 3.4, 11.3_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`TurnQuery` 测试和实现”，满足需求 3.4, 11.3，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 返回某一圈的完整 prompt 和回复_

- [x] 78. `ApiController`：提交和撤销
  - File: tantalum-shell/src/test/java/tantalum/shell/web/SubmitApiTest.java, tantalum-shell/src/main/java/tantalum/shell/web/ApiController.java
  - 验证：缺 `requestId` 返回 400；不等待模型
  - _Requirements: 7.3, 7.4, 10.1–10.3, 10.7_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ApiController`：提交和撤销”，满足需求 7.3, 7.4, 10.1–10.3, 10.7，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 缺 `requestId` 返回 400；不等待模型_

- [x] 79. `ApiController`：查询和 SSE
  - File: tantalum-shell/src/test/java/tantalum/shell/web/QueryApiTest.java, tantalum-shell/src/main/java/tantalum/shell/web/ApiController.java
  - 验证：他人的运行返回 404
  - _Requirements: 4.2, 10.4–10.6_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ApiController`：查询和 SSE”，满足需求 4.2, 10.4–10.6，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 他人的运行返回 404_

- [x] 80. `Startup` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/boot/StartupTest.java, tantalum-shell/src/main/java/tantalum/shell/boot/Startup.java
  - 验证：回放后才开放接口；未结束运行标记“待恢复”，不重发命令，可取消
  - _Requirements: 3.7, 4.1–4.3_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`Startup` 测试和实现”，满足需求 3.7, 4.1–4.3，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 回放后才开放接口；未结束运行标记“待恢复”，不重发命令，可取消_

- [x] 81. `TantalumApplication` 和装配
  - File: tantalum-shell/src/main/java/tantalum/shell/boot/TantalumApplication.java
  - 验证：一条命令启动；默认绑定 127.0.0.1；缺 Key 时给出明确错误；启动日志提示外发
  - _Requirements: NFR 易用性_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`TantalumApplication` 和装配”，满足需求 NFR 易用性，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 一条命令启动；默认绑定 127.0.0.1；缺 Key 时给出明确错误；启动日志提示外发_

## 阶段 10：端到端

- [x] 82. 示例钩子：额度和只读
  - File: tantalum-examples/src/main/java/tantalum/examples/DailyQuotaHook.java, tantalum-examples/src/main/java/tantalum/examples/ReadOnlyHook.java, tantalum-examples/src/test/java/tantalum/examples/PolicyHooksTest.java
  - 验证：`kernel` 目录无改动
  - _Requirements: 6.6, 6.8_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“示例钩子：额度和只读”，满足需求 6.6, 6.8，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: `kernel` 目录无改动_

- [x] 83. 示例：引用核对
  - File: tantalum-examples/src/main/java/tantalum/examples/CitationCheckHook.java, tantalum-examples/src/main/java/tantalum/examples/CitationWork.java, tantalum-examples/src/test/java/tantalum/examples/CitationCheckTest.java
  - 验证：`NeedWork` 后按结果结束或再次核对
  - _Requirements: 6.8_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“示例：引用核对”，满足需求 6.8，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: `NeedWork` 后按结果结束或再次核对_

- [x] 84. 示例：输出截断
  - File: tantalum-examples/src/main/java/tantalum/examples/TruncateOutputHook.java, tantalum-examples/src/test/java/tantalum/examples/TruncateOutputHookTest.java
  - 验证：只改数据
  - _Requirements: 6.7, 6.8_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“示例：输出截断”，满足需求 6.7, 6.8，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 只改数据_

- [x] 85. `ScriptedModelClient`
  - File: tantalum-examples/src/test/java/tantalum/examples/ScriptedModelClient.java
  - 验证：可按脚本返回回复和失败
  - _Requirements: 8.4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“`ScriptedModelClient`”，满足需求 8.4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 可按脚本返回回复和失败_

- [x] 86. 十圈集成测试
  - File: tantalum-examples/src/test/java/tantalum/examples/TenLoopIntegrationTest.java
  - 验证：经真实的环、journal、Web、SSE 跑完；回放的状态和逐圈 prompt 相同
  - _Requirements: 8.4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“十圈集成测试”，满足需求 8.4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 经真实的环、journal、Web、SSE 跑完；回放的状态和逐圈 prompt 相同_

- [x] 87. 背压集成测试
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/BackpressureTest.java
  - 验证：Output 满、Input 满、外部超限三种情况
  - _Requirements: 5.7_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“背压集成测试”，满足需求 5.7，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: Output 满、Input 满、外部超限三种情况_

- [x] 88. 杀进程重启测试
  - File: tantalum-examples/src/test/java/tantalum/examples/CrashRestartTest.java
  - 验证：第 K 圈杀进程，状态与最后已落盘事件一致
  - _Requirements: 4.4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“杀进程重启测试”，满足需求 4.4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 第 K 圈杀进程，状态与最后已落盘事件一致_

- [x] 89. 挂起工具隔离测试
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/HungToolTest.java
  - 验证：其他运行的处理不被拖慢
  - _Requirements: NFR 性能_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“挂起工具隔离测试”，满足需求 NFR 性能，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 其他运行的处理不被拖慢_

- [x] 90. 真实模型冒烟测试：strata
  - File: tantalum-examples/src/test/java/tantalum/examples/StrataSmokeTest.java
  - 验证：未配置时跳过；记录耗时；核对断开后是否停止生成、推理内容回传
  - _Requirements: 8.5_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“真实模型冒烟测试：strata”，满足需求 8.5，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 未配置时跳过；记录耗时；核对断开后是否停止生成、推理内容回传_

- [x] 91. 真实模型冒烟测试：Claude
  - File: tantalum-examples/src/test/java/tantalum/examples/ClaudeSmokeTest.java
  - 验证：未配置时跳过；核对 `thinking` 回传、超长错误；费用很小，运行前告知
  - _Requirements: 8.5_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“真实模型冒烟测试：Claude”，满足需求 8.5，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 未配置时跳过；核对 `thinking` 回传、超长错误；费用很小，运行前告知_

## 阶段 11：控制台

- [x] 92. 建立前端工程并由后端托管
  - File: console/package.json, console/vite.config.ts, tantalum-shell/pom.xml
  - 验证：jar 内含静态文件
  - _Requirements: 11.5_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“建立前端工程并由后端托管”，满足需求 11.5，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: jar 内含静态文件_

- [x] 93. API 和 SSE 客户端
  - File: console/src/api/
  - 验证：断线后从最后序号补读
  - _Requirements: 11.1, 11.4_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“API 和 SSE 客户端”，满足需求 11.1, 11.4，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 断线后从最后序号补读_

- [x] 94. 提问和操作界面
  - File: console/src/run/
  - 验证：内容按纯文本显示
  - _Requirements: 11.1, 11.6_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“提问和操作界面”，满足需求 11.1, 11.6，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 内容按纯文本显示_

- [x] 95. 时间轴和流式输出
  - File: console/src/timeline/
  - 验证：结算后用 journal 内容替换分片
  - _Requirements: 11.2_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“时间轴和流式输出”，满足需求 11.2，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 结算后用 journal 内容替换分片_

- [x] 96. 每圈详情
  - File: console/src/turn/
  - 验证：显示回放得出的 prompt 和回复
  - _Requirements: 11.3, 11.6_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“每圈详情”，满足需求 11.3, 11.6，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 显示回放得出的 prompt 和回复_

- [x] 97. 浏览器走查
  - File: （不改文件）
  - 验证：用脚本化模型在浏览器走一遍，发截图汇报
  - _Requirements: 11.1–11.6_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“浏览器走查”，满足需求 11.1–11.6，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 用脚本化模型在浏览器走一遍，发截图汇报_

## 阶段 12：收尾

- [x] 98. README
  - File: README.md
  - 验证：按文档从零运行十圈和回放
  - _Requirements: NFR 易用性_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“README”，满足需求 NFR 易用性，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 按文档从零运行十圈和回放_

- [x] 99. 扩展检验书面记录
  - File: docs/extension-check.md
  - 验证：人工审批、多模型路由、流式、memory 四项写明加什么、改什么，`kernel` 改动为零
  - _Requirements: NFR 架构_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“扩展检验书面记录”，满足需求 NFR 架构，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: 人工审批、多模型路由、流式、memory 四项写明加什么、改什么，`kernel` 改动为零_

- [x] 100. 完整验证
  - File: （不改文件）
  - 验证：`mvn verify` 全绿，覆盖率不低于 80%，逐条核对需求
  - _Requirements: NFR 可靠性_
  - _Prompt: Role: Java 25 工程师 | Task: 完成“完整验证”，满足需求 NFR 可靠性，遵守 design.md 的包和类 | Restrictions: 只做这一件事，不改无关文件；kernel 不做 I/O | Success: `mvn verify` 全绿，覆盖率不低于 80%，逐条核对需求_
