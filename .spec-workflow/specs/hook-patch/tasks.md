# Tasks Document

每个任务做完都跑该模块的测试并汇报；全量回归只在最后跑一次。

- [x] 1. 值类型：`HookTextBlock` 和 `SessionContext.HookState`
  - File: `tantalum-value/src/main/java/tantalum/value/model/HookTextBlock.java`（新）、`ContentBlock.java`、`tantalum-value/src/main/java/tantalum/value/state/SessionContext.java`、`tantalum-shell/src/main/java/tantalum/shell/journal/EventCodec.java`
  - 新块 `HookTextBlock(hook, text)` 加入 `permits`；`SessionContext` 第 8 个字段 `hooks`（可空），`withHooks`，其余 `withX` 保留它；`EventCodec` 注册子类型 `hookText`
  - 所有对 `ContentBlock` 的穷尽 switch 补 `case HookTextBlock`（编译器指路：`TranscriptPass`、`ClaudeRequest.param`、`OpenAiClient`、`Compactor` 估算等）
  - 测试：`RecordCodecTest` 加 `hooks` 的编解码和 delta；旧 journal 期望文件逐字节不变（现有测试）
  - _Requirements: 1.6, 非功能“代码架构”_

- [x] 2. 内核：`HookPatches`
  - File: `tantalum-kernel/src/main/java/tantalum/kernel/hook/HookPatches.java`（新）、`HookPatchesTest.java`（新）
  - 按设计第 2 节实现 `apply`：切段、比文字、比工具、位置检查、追加消息和 `withHooks`
  - 测试覆盖设计“测试策略”里列的 11 种情况
  - _Requirements: 1.1–1.5, 2.1–2.5, 非功能“可靠性”_

- [x] 3. 内核：接进 `ModelSteps`，投影不再收 patch
  - File: `ModelSteps.java`、`CompactSteps.java`、`RetrySteps.java`、`PromptProjection.java`、`Compacting.java`
  - `callModel` 放行分支调用 `HookPatches.apply`；`PromptProjection.project(session)`；`placeToolChanges` → `placeSystemUpdates`（带文字块的合并）；`tryStart(out, run, cause)`；`Compacting.patch` 注释
  - 测试：`PromptProjectionTest`、`ToolChangeProjectionTest`、`ModelStepsTest`（补丁变成 system 消息、下一圈不重复）、`HookChainTest` 不动；kernel 全量
  - _Requirements: 1.1–1.5, 2.1, 2.4_

- [x] 4. shell：Claude 和 OpenAI 路线发文字块
  - File: `tantalum-shell/src/main/java/tantalum/shell/model/ClaudeRequest.java`、`HookTexts.java`（新，Sonnet 5 降级）、`OpenAiClient.java`
  - 支持的模型：文字块进 `role: system`；不支持：并进前一条用户消息（带 `ToolChanges.PREFIX`），工具变化照旧丢；OpenAI：文字块按用户消息带前缀
  - 测试：`ClaudeRequestTest` 新增三段期望（用 `-Dclaude.record=true` 只生成新段，旧段逐字节不变，提交里说明）；`OpenAiClientTest` 一项；`ContextHandlerTest:346` 改成无 patch 投影
  - _Requirements: 3.1–3.4_

- [x] 5. shell：轨迹
  - File: `TraceSystem.java`、`TraceController.java`（请求详情 `additions`）、相关 record、`TracePassTest.java`、`WireFormatTest.java`
  - `split` 简化；请求详情扫 `HookTextBlock`；`TracePassTest` 的补丁用例改成断言 system 行只在初始出现、`additions` 有钩子名和文字
  - _Requirements: 4.1–4.4_

- [x] 6. 控制台：请求详情显示追加的说明
  - File: `console/src/trace/inspector/RequestTabs.tsx`、`console/src/api/types.ts`、`console/src/text/{zh,en,ja}.ts`、`console/src/trace/*.test.tsx`、`console/e2e/trace.spec.ts`
  - `CallSystem` 多一节；vitest 一项；Playwright 断言改掉
  - _Requirements: 4.1_

- [x] 7. examples：矩阵测试和真实 Claude 验证
  - File: `tantalum-examples/src/test/java/tantalum/examples/hooks/MatrixFixture.java`、`CoreToolsClaudeTest.java`
  - `MatrixFixture` PATCH 判定改成看消息；`CoreToolsClaudeTest` 新增需求 5 的场景并真跑一次，报用量
  - _Requirements: 5.1, 5.2_

- [x] 8. 文档、回归、部署
  - File: `README.md`、`README.en.md`、`.spec-workflow/steering/tech.md`、`.spec-workflow/BACKLOG.md`
  - README 钩子表“补丁”列和补丁段落改新语义；tech.md 记一节；BACKLOG 第 0 节标完成
  - 全量回归（`regress.sh`）、8091 部署、提交推送
  - _Requirements: 6.1, 6.2_
