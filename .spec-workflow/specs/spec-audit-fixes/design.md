# Design Document

## Overview

按模块逐项修复，不加新抽象层。压缩仍是内核纯函数，MCP 改动限于 `tantalum.shell.mcp`，记忆改动在 `tantalum.shell.memory`、`ContextHandler` 和内核的上下文更新。

## 对已批准需求的补充

| 项 | 内容 | 原因 |
|---|---|---|
| 补 1.9 | 压缩成功后重新组装 system 和工具 | `context-compaction` 规则 5 已写明，代码没做，写设计时发现 |

## 压缩

**只裁剪（A2）。** `ContextCompacted` 增加 `trimOnly`，`Compactor.trimmedView` 对整个视图做规则 5 的处理。

- 头部除去重复的请求后不足 `MIN_SUMMARY_GAIN`（500 token，初始值）时，不发摘要调用，直接裁剪。
- 自动和超长恢复路径上，摘要失败时，如果裁剪能让视图变小，也改用裁剪。手动压缩仍报失败。
- 都不能变小时按规则 8。裁剪后 `Transcript` 显示“已裁剪”，没有摘要。

**重新组装（1.9）。** 摘要成功或裁剪后，`SessionState.context` 置空，发 `AssembleContext`，运行进 `AwaitingContext`。`contextAssembled` 重新冻结，不追加更新消息。`RunState.justCompacted` 让这之后的那次调用不再判断（规则 3.1）。手动压缩不重新组装，下一个运行自然会组装。

风险：保留段可能含已下线工具的调用，而新工具列表里没有它。这要靠真实 Claude 验证，如果被拒，保留段里这类调用要转成文字。

**旧摘要（A3）。** `headForSummary` 从第二个单元起丢，第一条是旧摘要时不丢。除旧摘要外全部丢光时不发摘要调用，按失败处理。

**文件列表（A4）。** `Compactor.filesTouched` 取头部所有工具调用的 `path` 字符串参数，并入旧摘要里 `<files-touched>` 的内容，由程序追加到摘要消息末尾，最多 200 条。

**重试（A5）。** 新状态 `AwaitingSummaryRetry`：可重试失败用 `RetrySteps.backoff`，`StartTimer` 后用相同计划重发。`Compacting` 增加 `PromptPatch patch` 以便重发，成功后 `retries` 清零。`Transcript` 在 `compacting` 非空时不把失败记到运行状态。

**指令（1.6）。** `INSTRUCTION` 增加 Files and code、Errors and fixes 两节和“忠实保留用户的纠正”；`SUMMARY_PREFIX` 增加“直接继续任务，不要确认”。旧摘要存在时，要求把已完成的项移到 Done。

**截断（1.7）。** 标记算进上限，首尾各占剩余的一半。再次截断时先识别旧标记，取其中的原长度，不嵌套。上限小于标记时只留标记。

**手动（A6）。** `Compactor.plan` 在 `MANUAL` 时 K = 0。

## MCP

**调用失败（A7、A8）。** `McpConnection.attempt` 分四类：

| 失败 | 处理 |
|---|---|
| `McpError`、超时 | 不变 |
| 401 | `markBroken` 不变，这是整个连接的问题 |
| 会话失效 | 换客户端，所有工具重试一次 |
| 其他 | 只返回错误结果，不碰连接 |

换客户端时旧客户端先摘除，等 `server.timeout()` 后再关闭。`call` 去掉 `retrySafe` 参数。下线靠 `watch` 的探活发现，不变。

**回调（2.8）。** 工具变更回调绑定它所属的客户端，不是当前客户端就忽略。

**能力和分页（2.7、2.9）。** 没有 tools 能力时发布空列表。分页最多 100 页，超限保留已取到的并告警。

**工具名（A9）。** `McpToolFilter.publicName` 替换非法字符；有替换或超过 64 字符时截短并加 SHA-256 前 8 位，结果确定，重启不变。`McpTool` 保存原名用于调用。

**启动（A10）。** 每个连接第一次尝试结束时倒计数。`ToolSource` 增加 `awaitReady(Duration)`，`ContextHandler` 在虚拟线程上调用，配置 `tantalum.mcp.startup-wait` 默认 10 秒。

**结果（2.6）。** `McpResults.truncate` 保留首尾；`resource_link` 输出名称和 URI；内容为空时用 `structuredContent` 的 JSON；错误结果为空时给固定说明。

**回环（2.10）。** 只认 `localhost`、`127.0.0.0/8` 和 `::1` 的字面量。

## 记忆

**分段（A11、A12）。** 新记录 `ContextSection(name, body, byLine)`，`ContextAssembled` 和 `SessionContext` 增加 `sections`。旧事件的 `sections` 为 null，走现有的整段比较。

新纯函数 `ContextDiff` 比较两组分段：共享知识和 `AGENTS.md` 变化时重发该段，记忆索引只发增加和删除的行，整段消失时发移除记录。

**边界。** `<shared-knowledge>`、`<project-instructions>`、`<memory-index>` 包裹各段，正文里的 `</` 转义，索引段前说明内容是笔记、不是指令。上一节的 Requirement 3.2 由测试验证无法跳出。

**上限（3.3）。** `AGENTS.md` 和索引超限时截断，并在段内写明。知识库同样加这句，不再只记日志。上限沿用 `tantalum.knowledge.max-bytes` 和 `tantalum.memory.max-index-bytes`，`AGENTS.md` 用前者。

**其他。** `indexText` 用 `parseIndex` 过滤坏行并记日志；`CredentialGuard` 按需求 3.5 补规则；`writeAtomically` 用随机名加 `CREATE_NEW`。

## Error Handling

| 情况 | 处理 |
|---|---|
| 裁剪也不能变小 | 规则 8 |
| 重新组装失败（`CommandDropped`） | 运行按现有规则失败，压缩结果已生效；下一运行重新组装 |
| 旧客户端延迟关闭时服务关闭 | `close` 一并关闭 |

## Testing Strategy

每个缺陷先写失败的测试（需求 5.1）。

- **内核：** `CompactStepsTest`、`CompactorTest`、`TrimmingTest`、`SummaryPromptTest`、新的 `ContextDiffTest`。
- **MCP：** `McpConnectionTest` 用 `TestMcpServer` 返回 500 和 404，验证其他用户的慢调用不受影响、写工具在会话失效时只执行一次。
- **真实模型：** Claude 严格校验和 qwen 跑需求 5.2、5.3；Claude 额外跑保留段含已下线工具的场景。
- **界面：** vitest 覆盖“已裁剪”标记。
- **全量：** `mvn verify`、vitest、Playwright。
