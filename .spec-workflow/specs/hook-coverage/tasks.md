# Tasks Document

工作方式：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志。每个阶段结束：
- 跑全量回归：`mvn -o clean verify`（含 `JarCrashIT`）、跳号回归、vitest、Playwright；
- 做一次变异检查；
- 提交一次；
- 向 Yi 汇报：做了什么、加了和删了多少行、验证结果、有没有偏离 spec、下一步。

提交前扫描密钥。任务只有在验证项真的跑过之后才标完成。现有测试只因构造参数变化而改，断言不放宽。

路径前缀：`kernel/` = `tantalum-kernel/src/main/java/tantalum/kernel/`，`value/` = `tantalum-value/src/main/java/tantalum/value/`，`shell/` = `tantalum-shell/src/main/java/tantalum/shell/`，`examples/` = `tantalum-examples/src/main/java/tantalum/examples/`，`examples-test/` = `tantalum-examples/src/test/java/tantalum/examples/`。

## 阶段 1：内核

- [ ] 1. `Limits` 取代 `maxParallelTools` 参数
  - File: kernel/Limits.java（新）、kernel/Kernel.java、kernel/table/StepContext.java、shell/boot/{TantalumProperties,Engine}.java、application.properties；`new Kernel(` 的 23 处调用
  - `Limits(maxParallelTools, maxWorkRounds)`，`DEFAULT = (10, 8)`。新增配置项 `tantalum.hooks.max-work-rounds`（默认 8），小于 1 时启动失败
  - 验证：全部模块编译、测试通过；`TantalumPropertiesTest` 覆盖默认值和非法值
  - _Requirements: 2.5_

- [ ] 2. `HookOutcome.decidedBy`
  - File: kernel/hook/{HookOutcome,HookChain}.java；测试 HookChainTest
  - 返回 `Stop` 或 `NeedWork` 的那个钩子名；放行时为 null；抛异常、返回 null 导致拦截时，填出问题的那个钩子名
  - 验证：HookChainTest 覆盖每种情况
  - _Requirements: 2.1_

- [ ] 3. 工作轮数计数和上限（先写测试）
  - File: value/state/RunState.java、kernel/step/{WorkSteps,ModelSteps,ToolSteps}.java；测试 WorkStepsTest
  - 测试先行，并且要在现有代码上失败：四个等待点（模型调用前、工具调用前、结束前、工作结果后）各有一个“每次都要求工作”的钩子，第 9 轮时运行以 FAILED 结束，详情写明钩子、位置和轮数，并报 `HookReported`。清零规则：
    - 模型回复后清零；
    - 换下一个工具时清零；
    - 结束前的计数不清零；
    - 两个工具各要 5 轮，互不影响；
    - 引用核对只要 1 轮，不受影响；
    - `onWorkResult` 接着要的工作计在原来的等待点上；
    - 每个场景 `Replay.run` 的结果相同
  - 实现：`RunState` 加 `workRounds`、`endWorkRounds`；检查只放在 `WorkSteps.start`
  - 验证：新测试通过，kernel 全部测试通过
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [ ] 4. `beforeCompact` 能看到触发方式
  - File: value/hook/HookContext.java、kernel/step/CompactSteps.java；测试 CompactStepsTest
  - `HookContext` 加 `trigger`；`withPlan` 改名为 `withCompaction(plan, trigger)`
  - 测试：自动、超长恢复、手动三种触发方式都传到钩子；摘要重试不再调 `beforeCompact`；回放结果相同
  - 验证：CompactStepsTest 通过
  - _Requirements: 3.1, 3.2, 3.3_

- [ ] 5. 阶段 1 收尾
  - 全量回归、跳号回归。变异检查：去掉 `WorkSteps.start` 里的上限检查，任务 3 的测试必须失败
  - 提交，写实现日志，汇报
  - _Requirements: 非功能“行为不变”_

## 阶段 2：示例插件

- [ ] 6. `HookMessages.isUserText`
  - File: kernel/hook/HookMessages.java（新），转调 kernel/compaction/Compactor.java 的规则
  - 验证：内核子包无循环的 ArchUnit 规则通过；单元测试覆盖用户文字、工具结果、上下文更新、摘要四种消息
  - _Requirements: 1.3_

- [ ] 7. `ProviderBackoffHook` + `ProviderStatusWork`
  - File: examples/{ProviderBackoffHook,ProviderStatusWork}.java；测试 examples-test/ProviderBackoffHookTest.java
  - 三种结果各自的单元测试：
    - 限流且要等太久 → 拦截；
    - 服务端错误、连接错误 → 要求工作；
    - 其他错误（含超长） → 放行
  - `ProviderStatusWork` 用 JDK 的 `HttpServer` 起本地桩，测试：恢复、一直不恢复（超时）、连不上
  - 类注释写明用了哪种能力、参照了什么、产生哪几种结果
  - _Requirements: 1.1, 1.4_

- [ ] 8. `ToolApprovalHook` + `ApprovalWork`
  - File: examples/{ToolApprovalHook,ApprovalWork}.java；测试 examples-test/ToolApprovalHookTest.java
  - 单元测试覆盖：
    - 列表里的工具要求审批，列表外的放行；
    - 审批结果同意 → 放行；
    - 拒绝、工作失败、次数用完 → 拦截；
    - 待定 → 再要一轮，查询次数加一；
    - 别的类型的工作 → 不管；
    - 钩子是强制钩子
  - `ApprovalWork` 用本地桩测试，服务返回无法识别的内容时按拒绝处理
  - _Requirements: 1.2, 1.4_

- [ ] 9. `PinnedContextHook`
  - File: examples/PinnedContextHook.java；测试 examples-test/PinnedContextHookTest.java
  - 单元测试：
    - 头部有钉住的消息且触发方式是自动 → 拦截；手动 → 拦截；超长恢复 → 放行；
    - 钉住的消息只在保留段里 → 放行；
    - 标记只出现在摘要或工具结果里 → 放行
  - _Requirements: 1.3, 1.4_

- [ ] 10. 文档
  - File: kernel/hook/KernelHook.java（类注释）、README.md“写插件”
  - 7 个钩子各写明：能返回什么、补丁是否生效、对应哪个示例插件。另写明需求 5.4 的五点
  - _Requirements: 1.5, 5.4_

- [ ] 11. 阶段 2 收尾
  - 全量回归。变异检查：让 `PinnedContextHook` 也拒绝超长恢复，任务 9 的测试必须失败
  - 提交，写实现日志，汇报
  - _Requirements: 非功能“测试”_

## 阶段 3：测试矩阵

- [ ] 12. 测试用的钩子和工作
  - File: examples-test/hooks/{MatrixHook,MatrixWork,MatrixFixture}.java
  - `MatrixHook(point, result, mandatory)`；`MatrixWork` 支持成功、失败、超时、卡在 latch 上四种模式。`MatrixFixture` 负责起 `TestServer`、驱动 `ScriptedModelClient`、读 journal、做“重启后视图逐字节相同”的比较
  - 验证：一个最小场景（模型调用前 + 放行）跑通
  - _Requirements: 4.1, 4.5_

- [ ] 13. 内核钩子矩阵
  - File: examples-test/hooks/HookMatrixTest.java
  - 6 个位置 × 适用的结果（放行、带补丁放行、拦截、要求工作后成功 / 失败 / 超时、抛异常时分强制和非强制、返回 null），表驱动
  - 每格检查：运行结果、journal 事件序列、对话条目、发给模型的 prompt；最后检查重启后视图逐字节相同
  - _Requirements: 4.1, 4.5_

- [ ] 14. 处理器钩子
  - File: examples-test/hooks/HookMatrixTest.java（另一张表）
  - `afterToolExecution` 三种情况：修改结果、原样返回、抛异常
  - _Requirements: 4.3_

- [ ] 15. 工作中途取消和重启
  - File: examples-test/hooks/HookWorkInterruptTest.java
  - 会要求工作的 5 个位置，每个位置测两件事：
    - 取消：运行 CANCELLED，迟到的结果被丢弃；
    - 重启：用很短的 `shutdown-wait` 关服务，再用同一个数据目录重开，运行按“被重启中断”收尾，工作只执行过 1 次
  - _Requirements: 4.2_

- [ ] 16. 工作轮数上限走真实管线
  - File: examples-test/hooks/WorkRoundLimitTest.java
  - 四个等待点各验证一次“到上限就失败”；引用核对插件和审批插件的正常场景不受影响
  - _Requirements: 4.4_

- [ ] 17. 三个新示例插件走真实管线
  - File: examples-test/hooks/ExampleHooksPipelineTest.java
  - 每个插件的每种结果各跑一次（共约 9 个场景），外部服务用本地桩
  - _Requirements: 4.6_

- [ ] 18. 钩子不做 I/O 的 ArchUnit 规则
  - File: examples-test/PluginBoundaryTest.java
  - 实现 `KernelHook` 的类只依赖 value、`kernel.hook`、`java.lang`、`java.util`、`java.time`。另写一条规则禁止取当前时间、`Thread`、`ProcessBuilder`、`Runtime`、`System.getenv`。范围包括 examples 的主代码和测试代码
  - 变异检查：让 `DailyQuotaHook` 读一个文件，规则必须失败
  - _Requirements: 5.1, 5.2, 5.3_

- [ ] 19. 阶段 3 收尾和端到端
  - 全量端到端：`mvn -o clean verify`（含 `JarCrashIT`）、跳号回归（任务 12–17 也要过）、vitest、Playwright。这组测试的耗时目标是 2 分钟以内，超了就按设计改成共用服务
  - 变异检查：回到 `BEFORE_TOOL_CALL` 时忽略 `onWorkResult` 的拦截，矩阵必须失败
  - 更新 BACKLOG 和 tech.md，提交，写实现日志，汇报
  - _Requirements: 4.7，非功能“测试”_
