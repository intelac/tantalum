# 扩展检验（core-skeleton 结束时）

依据简报第 9 节和需求的非功能部分：写明每项能力“加什么、改什么”，且 `tantalum-kernel` 目录的改动为零。这是对现有代码的书面推演，这四项都还没有实现。子 agent 和按对话激活留给实现它们的 spec。

| 能力 | 加什么 | 改什么 | kernel 目录 |
|---|---|---|---|
| 人工审批 | `ApprovalHook`（`beforeToolCall` 返回 `NeedWork`）；`ApprovalWork`（`WorkType`）；审批接口和页面 | 无 | 零 |
| 多模型路由 | `RoutingModelClient`（实现 `ModelClient`，内部选 Claude 或 OpenAI 兼容客户端） | 装配时换成它 | 零 |
| 流式输出 | 已实现 | 无 | 零 |
| memory 插件 | 记忆工具（`Tool`）；可选的召回钩子（`beforeModelCall` 用 `Allow` 带 `PromptPatch`） | 无 | 零 |

## 逐项说明

**人工审批。** `WorkSteps` 已经能在 `beforeToolCall` 的 `NeedWork` 之后回到工具轮，`onWorkResult` 返回 `Stop` 时拦截这个调用。

有一处要注意：`WorkType.run` 现在是等待时占着虚拟线程，也不跨重启。等几小时的审批要等 `durable-semantics` 的 park 能力才算完整。流程本身不需要改内核。

**多模型路由。** `ModelClient` 与供应商无关；`ReasoningBlock` 记录来源，换模型时不会回传别家的推理内容；`ModelReplied` 记录实际模型。

有一处要注意：`CallModel` 没有路由字段，所以路由依据只能来自 `Prompt`。按运行或按用户路由需要新增命令字段，那属于 value 层的新增，而不是 kernel 目录的改动。这一点到实现时要再确认。

**流式输出。** `ModelHandler` 把分片经 `SseHub` 推给用户，不进 journal 和内核。

**memory 插件。** 走工具不碰内核。验收标准是：在会话 A 写入，在新会话 B 召回并使用，不同 principal 之间不可见。

有一处要注意：`Tool.run` 现在只收到参数，拿不到 principal。多用户隔离需要给 `Tool` 增加调用上下文，这是对 `shell.plugin` 接口的改动，不碰 kernel，但属于破坏性变更，应当在对外发布插件接口前做。

## 结论

kernel 目录的改动为零，这四项都成立。但检验发现了两个缺口，都在 kernel 之外：`CallModel` 没有路由依据，`Tool` 拿不到 principal。
