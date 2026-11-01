# Tasks Document

工作方式：一次一个任务；每个缺陷先写失败的测试，确认失败后再修；完成后标记、记录实现日志；每个阶段结束提交一次，并汇报做了什么、验证结果、是否偏离 spec、下一步。提交前扫描密钥。任务只有在验证项真的跑过后才标完成。

## 阶段 1：压缩，内核

- [x] 1. 截断幂等
  - File: tantalum-kernel/src/main/java/tantalum/kernel/compaction/Trimming.java, tantalum-kernel/src/test/java/tantalum/kernel/compaction/TrimmingTest.java
  - 标记算进上限；再次截断不嵌套，保留最初的原长度
  - 验证：截断两次标记只有一个、原长度不变、总长不超上限、不切坏字符
  - _Requirements: 1.7_

- [x] 2. 手动压缩 K = 0
  - File: tantalum-kernel/src/main/java/tantalum/kernel/compaction/Compactor.java, tantalum-kernel/src/test/java/tantalum/kernel/compaction/CompactorTest.java, tantalum-shell/src/test/java/tantalum/shell/web/CompactApiTest.java
  - 验证：两个单元的短会话也有头部；只有一个单元时才 409
  - _Requirements: 1.8_

- [x] 3. 旧摘要不丢
  - File: Compactor.java, CompactorTest.java, tantalum-kernel/src/main/java/tantalum/kernel/step/CompactSteps.java, CompactStepsTest.java
  - 丢单元时跳过旧摘要；除旧摘要外全部丢光时不发摘要调用，按失败处理
  - 验证：头部放不下时旧摘要仍在请求里；丢光时没有 CallModel
  - _Requirements: 1.3_

- [x] 4. 文件列表
  - File: Compactor.java, tantalum-kernel/src/main/java/tantalum/kernel/compaction/SummaryPrompt.java, CompactorTest.java, SummaryPromptTest.java
  - 从工具调用的 `path` 参数提取，并入旧摘要的 `<files-touched>`，最多 200 条
  - 验证：两次压缩累积且不重复；模型的摘要里没写也有；超上限截断
  - _Requirements: 1.4_

- [x] 5. 摘要指令
  - File: SummaryPrompt.java, SummaryPromptTest.java
  - 增加 Files and code、Errors and fixes、保留用户纠正；前缀要求直接继续；有旧摘要时要求更新进度
  - 验证：断言各段存在
  - _Requirements: 1.6_

- [x] 6. 只裁剪压缩
  - File: tantalum-value/src/main/java/tantalum/value/decision/ContextCompacted.java, Compactor.java, CompactSteps.java, RetrySteps.java, CompactStepsTest.java
  - 头部不足 `MIN_SUMMARY_GAIN` 时直接裁剪；自动和超长恢复路径上摘要失败时回退到裁剪；都不能变小按规则 8
  - 验证：先用复现测试看到现有失败（头部只有首条请求 + 超大工具结果）；修后运行继续；超长恢复同样通过；手动仍报失败
  - _Requirements: 1.1, 1.2_

- [x] 7. 摘要调用重试
  - File: tantalum-value/src/main/java/tantalum/value/state/Compacting.java, CompactSteps.java, tantalum-kernel/src/main/java/tantalum/kernel/table/BuiltinSteps.java, CompactStepsTest.java
  - `AwaitingSummaryRetry`，退避后重发相同 prompt；用完再按规则 8；成功后 `retries` 清零
  - 验证：限流后重试成功；连续失败到上限；重试等待中取消；回放 prompt 相同
  - _Requirements: 1.5_

- [x] 8. 压缩后重新组装
  - File: tantalum-value/src/main/java/tantalum/value/state/RunState.java, SessionState.java, CompactSteps.java, tantalum-kernel/src/main/java/tantalum/kernel/step/ModelSteps.java, CompactStepsTest.java
  - 自动和超长恢复成功后发 `AssembleContext`，重新冻结，之后那次调用不再判断
  - 验证：新记忆索引和新工具出现在压缩后的 system；不追加更新消息；不连续压缩；回放相同
  - _Requirements: 设计补 1.9_

## 阶段 2：压缩，shell 和界面

- [x] 9. 对话条目、轨迹和控制台
  - File: tantalum-shell/src/main/java/tantalum/shell/conversation/Transcript.java, TranscriptItem.java, tantalum-shell/src/main/java/tantalum/shell/journal/TurnQuery.java, console/src/conversation/CompactionMark.tsx, console/src/text/zh.ts, 对应测试
  - “已裁剪”标记；重试等待不记为运行失败；`AwaitingSummaryRetry` 归 TimerHandler
  - 验证：TranscriptTest、TurnQueryTest、vitest 全过；重启后视图逐字节相同
  - _Requirements: 1.1, 1.5_

## 阶段 3：MCP

- [x] 10. 普通失败不拆连接
  - File: tantalum-shell/src/main/java/tantalum/shell/mcp/McpConnection.java, McpConnectionTest.java, McpMultiUserTest.java
  - 验证：服务器对一个调用返回 500 时，另一用户的慢调用完成，连接状态仍 READY，失败次数不变；真下线时探活发现
  - _Requirements: 2.1, 2.3_

- [x] 11. 会话失效和重试
  - File: McpConnection.java, McpTool.java, McpConnectionTest.java
  - 只认 `McpTransportSessionNotFoundException`；换客户端，旧客户端延迟关闭；所有工具重试一次；去掉 `retrySafe`
  - 验证：写工具在 404 后只执行一次；旧客户端的在途调用走完；服务关闭时一并关闭
  - _Requirements: 2.2_

- [x] 12. 回调代次、能力和分页
  - File: McpConnection.java, McpTools.java, McpConnectionTest.java
  - 验证：旧客户端回调被忽略；无 tools 能力时不调 `tools/list`；分页超 100 页时停止并告警
  - _Requirements: 2.7, 2.8, 2.9_

- [x] 13. 工具名
  - File: McpToolFilter.java, McpRegistry.java, McpTool.java, McpToolFilterTest.java
  - 验证：含点、超长的工具可调用，服务器收到原名；名称重启后不变；长度不超 64
  - _Requirements: 2.4_

- [x] 14. 启动等待
  - File: McpRegistry.java, tantalum-shell/src/main/java/tantalum/shell/tool/ToolCatalog.java, tantalum-shell/src/main/java/tantalum/shell/handler/ContextHandler.java, tantalum-shell/src/main/java/tantalum/shell/boot/TantalumProperties.java, 对应测试
  - 验证：刚启动的会话有工具；服务器不可用时最多等配置时间后继续并记日志；不配 MCP 时不等待
  - _Requirements: 2.5_

- [x] 15. 结果处理和回环判断
  - File: McpResults.java, McpResultsTest.java, McpSettings.java, McpSettingsTest.java
  - 验证：超长保留首尾；`resource_link` 有名称和 URI；空内容回退 `structuredContent`；空错误有说明；回环判断不做 DNS
  - _Requirements: 2.6, 2.10_

## 阶段 4：记忆

- [x] 16. 坏行、上限和临时文件
  - File: tantalum-shell/src/main/java/tantalum/shell/memory/MemoryStore.java, KnowledgeBase.java, ContextHandler.java, 对应测试
  - 验证：坏行被跳过并记日志；`AGENTS.md`、索引、共享知识超限时 prompt 里有说明；临时文件随机命名，已存在路径被拒
  - _Requirements: 3.3, 3.4, 3.6_

- [x] 17. 凭据规则
  - File: CredentialGuard.java, MemoryToolsTest.java
  - 验证：每个新前缀各一例被拒；不含数字的长 `sk-` 名称通过；拒绝原因不回显内容
  - _Requirements: 3.5_

- [x] 18. 分段、边界和只发变化
  - File: tantalum-value/src/main/java/tantalum/value/state/ContextSection.java, SessionContext.java, tantalum-value/src/main/java/tantalum/value/event/ContextAssembled.java, tantalum-kernel/src/main/java/tantalum/kernel/projection/ContextDiff.java, ModelSteps.java, ContextHandler.java, EventCodec 测试
  - 验证：一条记忆变化时追加的消息只有那一行；知识变化重发该段；整段消失有移除记录；闭合标签跳不出包裹；旧事件仍能回放；真实 Claude 严格校验下改记忆不失败
  - _Requirements: 3.1, 3.2_

## 阶段 5：真实场景和收尾

- [ ] 19. 真实模型
  - File: tantalum-examples/src/test/java/tantalum/examples/ClaudeScenarioTest.java, StrataScenarioTest.java, CompactionScenario.java
  - Claude 严格校验和 qwen：首条请求后读超大文件，答对首尾暗号；窗口小到压缩两次的长任务，第一次压缩前的约束在结尾仍被遵守。Claude 额外跑：保留段含已下线工具的调用
  - 验证：判定全部机械断言；strata 不可用记为未验证；用量写进 tech.md。如果已下线工具的调用被拒，先修再过
  - _Requirements: 5.2, 5.3, 设计补 1.9_

- [ ] 20. 文档
  - File: .spec-workflow/specs/mcp-client/requirements.md, .spec-workflow/specs/memory/requirements.md, .spec-workflow/specs/context-compaction/requirements.md, .spec-workflow/specs/context-compaction/design.md, README.md, .spec-workflow/steering/tech.md
  - 更正错误说法并注明日期；写明删除记忆不清除 journal、记忆边界不是保证；已知限制
  - _Requirements: 4.1, 4.2_

- [ ] 21. 全量验证
  - `mvn verify`、vitest、Playwright 全过；对照需求逐条核对，每条都能指出对应的测试
  - 验证：列出核对结果，任何没做的如实写出
  - _Requirements: 5.5_
