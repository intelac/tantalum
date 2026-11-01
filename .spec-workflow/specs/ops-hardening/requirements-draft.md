# Requirements Document

## Introduction

`context-and-ops` 解决两类问题：长会话跑不下去，和长期运行撑不住。

现状的具体缺口：

- 会话一长，模型返回上下文超长，运行以 FAILED 结束，用户只能开新会话。
- 启动时间和内存随 journal 增长；`TurnQuery` 每次请求读完整个 journal。
- 登录过期后，已打开的 SSE 不断开；关闭时不等在途 Handler。
- 同一会话里记忆或 `AGENTS.md` 变了，下一个运行的 system 就变了。这会让旧 thinking 块失效，在强制校验的账号上返回 400。

依据：

- pi `docs/compaction.md`：阈值 `contextWindow - reserveTokens`，默认保留 16384；工具结果之后、下一次模型调用之前检查；溢出时压缩并重试一次；不在工具结果处切开；追加压缩条目，原始条目留在存储。
- DeepSeek Harness 笔记 `after-call-compaction-pressure-and-overflow-recovery`：压力阈值 0.8；用调用后的 usage 计量；溢出恢复必须证明视图确实变小才重试，否则保留原始错误；`compaction-summary-prefix-cache-reuse`：摘要调用回放原 prompt，把指令放在末尾，复用缓存。
- Anthropic 的 preserved thinking 规则：thinking 签名绑定 system、工具集合和它之前的全部消息。保留尾部的压缩、裁剪旧工具结果都会让后面的 thinking 失效；推荐“简单压缩”（摘要加新一轮，不留更早内容）。
- 设计简报 6.10、`core-skeleton` 决定 D8 和 tech.md 决定 10、11 预留的边界。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| 上下文压缩：自动、溢出恢复、手动 | 后台提前压缩（设计简报提到；对 Claude 属于“持续付代价”的一类，先同步） |
| 会话前缀稳定：system 和工具按会话冻结 | Anthropic 服务端压缩和 on-demand 压缩 beta（只有 Claude 能用，见决定 C2） |
| journal 快照，启动从快照恢复 | 裁剪或删除 journal（审计依据，一条不删） |
| 回放和补读不再读整个 journal | `reset(handoff)`、会话分支（单独做） |
| SSE 随登录过期和退出断开 | 两级持久化、遥测（没有真实需求驱动，继续留着） |
| 关闭顺序：停入口 → 等在途 → 排空 → 关存储 | 崩溃后自动续跑（仍只能撤销） |
| 500 条消息长会话的压测 | 记忆自动整理（memory 决定 M5） |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| C1 | 压缩后模型看到什么 | 简单压缩：一条摘要消息，加上当前运行还没回答的用户请求原文。不保留更早的原始消息 | pi 和 DSH 都保留尾部，但它们的主力不是带签名校验的模型。Claude 上保留尾部必须去掉其中的 thinking，工具轮中途还不能去。代价是摘要质量决定后续质量，所以摘要格式要结构化（C5） |
| C2 | 用谁的压缩 | 客户端自己做，所有供应商同一条路径，不用 Anthropic 的压缩 beta | 本地 qwen、以后的 MCP 场景都要能压缩；回放要求摘要进 journal。beta 可以以后作为 `ModelClient` 的可选实现接入 |
| C3 | 什么时候触发 | 三种：上一次调用的实际输入（`promptTotal`）超过 `窗口 − reserve`，在下一次模型调用前同步压缩；模型返回 `CONTEXT_OVERFLOW`，压缩后重试一次；用户手动 | 与 pi 相同。阈值用实测 usage，不估算；内核因此仍是纯函数 |
| C4 | 压缩是什么 | 内核发命令，Handler 调模型，结果写成新事件 `ContextCompacted`（摘要、用量、压缩前大小）。内核据此替换 `SessionState.messages` 这个视图；journal 和对话界面保留全部原文 | 落实 D8 的预留：不改写已有事件；回放不调模型 |
| C5 | 摘要调用怎么发 | 回放当前 prompt（system、工具、消息），末尾追加一条指令消息，禁止调用工具。摘要结构固定：用户目标、已做决定、文件和状态、未完成事项 | DSH 实测换 system 会让缓存全部失效，而压缩恰好发生在对话最大的时候 |
| C6 | 压缩失败怎么办 | 自动压缩失败：记录并继续原调用。溢出恢复：只有视图确实变小才重试，且只试一次，否则报原始错误 | 来自 DSH 的“有进展才重试”，防止无限循环和掩盖真实错误 |
| C7 | 裁剪旧工具结果 | 不做 | DSH 有这一步，但 Anthropic 把它列为无法补救的改动：编辑点之后的 thinking 全部失效 |
| C8 | 钩子 | 新增第六个位置 `beforeCompact`，可以 `Stop` 拒绝；提供自定义摘要留待以后 | 设计简报要求 1.0 前锁定钩子集合 |
| C9 | 会话前缀稳定 | 会话第一个运行的 system 和工具固定用到下一次压缩。之后记忆或 `AGENTS.md` 的变化，在下一个运行开始时追加为一条消息。压缩时才重新组装 | Anthropic 只承认“新会话、用户重置、完整压缩之后”是合法边界。**这改变了 memory 决定 M4、M7 的实现方式**：索引只在会话开始和压缩后全文进 system |
| C10 | 快照存什么 | 内核状态、对话投影、提交账本和序号，写进 journal 同库的新表。快照只是缓存，读不了或版本不符就全量回放 | journal 仍是唯一事实来源，快照坏了不丢数据 |
| C11 | 回放接口的范围 | `/turns`、`/trace` 和 SSE 补读只读该用户的事件（给 `principal` 和 `run_id` 加索引），从空状态重放 | 钩子只能依赖该用户自己的状态（如每日额度），所以结果不变。**新增一条插件约束**，写进 README |

## Alignment with Product Vision

面向自托管多用户部署：长任务要能跑完，服务要能长期运行、正常重启。压缩后 journal 仍保留全部原文，所以“模型当时看到了什么”依然能回答，这是底座相对同类的主要优势，不能因为压缩丢掉。

## Requirements

### Requirement 1：自动压缩

**User Story:** 作为用户，我希望长任务不会因为上下文满了而失败。

#### Acceptance Criteria

1. WHEN 上一次模型调用的 `promptTotal` 超过 `窗口 − reserve` THEN 内核 SHALL 在下一次模型调用前发出压缩命令，运行进入等待压缩的状态。
2. WHEN 正在工具轮中 THEN 压缩 SHALL 在本轮全部工具结果写入之后才发生，模型看到的历史里 SHALL NOT 出现没有配对结果的工具调用。
3. WHEN 压缩完成 THEN 下一次 prompt 的消息 SHALL 是摘要消息加未回答的用户请求原文，运行继续，不需要用户操作。
4. WHEN 配置窗口和保留量 THEN 系统 SHALL 按供应商配置 `context-window`、`reserve-tokens`；窗口为 0 或未配置时不自动压缩，只做溢出恢复。
5. WHEN 自动压缩失败 THEN 运行 SHALL 继续原来的调用，失败写入轨迹。

### Requirement 2：溢出恢复

**User Story:** 作为用户，我希望估算不准导致超长时系统能自己恢复。

#### Acceptance Criteria

1. WHEN 模型返回 `CONTEXT_OVERFLOW` 且本次调用还没恢复过 THEN 系统 SHALL 压缩并重试一次。
2. WHEN 压缩后视图没有变小，或压缩本身失败 THEN 运行 SHALL 以 FAILED 结束，详情是原始错误。
3. WHEN 重试成功 THEN 恢复计数 SHALL 清零，以后再溢出可以再恢复。
4. WHEN 摘要请求本身超长 THEN 系统 SHALL 按消息边界去掉最早的内容后再试，最多一次；因为这次已经是压缩，改动前缀不再有影响。

### Requirement 3：手动压缩

**User Story:** 作为用户，我希望自己决定什么时候压缩。

#### Acceptance Criteria

1. WHEN 调用 `POST /api/sessions/{id}/compact`（可带 `instructions`）且会话空闲 THEN 系统 SHALL 压缩该会话；运行中返回 409；别人的会话返回 404。
2. WHEN 使用控制台 THEN 状态栏 SHALL 提供压缩入口，并在上下文占比超过 80% 时提示。

### Requirement 4：压缩的持久化和可见性

**User Story:** 作为排查问题的人，我希望压缩不影响回放和审计。

#### Acceptance Criteria

1. WHEN 压缩完成 THEN journal SHALL 追加 `ContextCompacted`，已有事件不变；回放 SHALL 不调用模型，得到的 prompt 与实时逐个相同。
2. WHEN 打开对话页 THEN 全部原始条目 SHALL 仍然显示，压缩处有一条分隔标记，可展开查看摘要。
3. WHEN 打开轨迹页 THEN 压缩 SHALL 显示为一步，带耗时、用量、压缩前后大小；用量 SHALL 计入每日统计。
4. WHEN 钩子 `beforeCompact` 返回 `Stop` THEN 不压缩，并按 C6 处理。

### Requirement 5：会话前缀稳定

**User Story:** 作为部署者，我希望更换账号或模型后不会出现 thinking 校验失败。

#### Acceptance Criteria

1. WHEN 同一会话的后续运行开始 THEN system 和工具定义 SHALL 与上一次发送的逐字节相同（工具顺序按名称固定），除非刚刚压缩过。
2. WHEN 记忆索引、共享知识或 `AGENTS.md` 相对上次有变化 THEN 变化 SHALL 作为一条追加消息告诉模型，历史不改动。
3. WHEN 插件工具集合在重启后变化 THEN 已有会话 SHALL 继续发送它冻结的定义；已不存在的工具被调用时返回错误结果。
4. WHEN 钩子通过 `PromptPatch` 追加内容 THEN 文档 SHALL 说明它必须每轮不变，并在轨迹里提示检测到的变化。
5. WHEN 跑真实模型测试 THEN 请求 SHALL 带 `prefix_mismatch_behavior: "error"`（经 beta 头）跑长对话、改记忆和压缩三种场景，不出现校验失败。这样才能在未强制校验的账号上发现问题。

### Requirement 6：journal 快照

**User Story:** 作为运维，我希望重启时间不随历史无限增长。

#### Acceptance Criteria

1. WHEN 距上次快照超过配置的事件数（默认 10000）或正常关闭 THEN 系统 SHALL 写入快照，且不阻塞内核线程；写快照失败只记日志。
2. WHEN 启动且快照可用 THEN 系统 SHALL 从快照加快照之后的事件恢复；结果 SHALL 与全量回放逐字节相同，由测试保证。
3. WHEN 快照损坏或版本不符 THEN 系统 SHALL 记警告并全量回放。
4. WHEN 有 50 万个事件 THEN 启动恢复 SHALL 在 5 秒内完成（数字在设计阶段实测后确认）。
5. WHEN 运行结束很久 THEN 内核状态 SHALL 能释放它的细节，运行查询改由 journal 回答，使内存不随历史总量增长。

### Requirement 7：回放接口

**User Story:** 作为用户，我希望别人的历史再多，我的轨迹页也不会变慢。

#### Acceptance Criteria

1. WHEN 请求 `/turns`、`/trace` 或 SSE 超出内存范围的补读 THEN 系统 SHALL 只读该用户的事件，不读全表。
2. WHEN 回放单用户事件 THEN 结果 SHALL 与全量回放中该用户的部分相同，由测试保证。
3. WHEN 其他用户有 100 万个事件 THEN 接口耗时 SHALL 不随之增长。

### Requirement 8：连接和关闭

**User Story:** 作为运维，我希望连接随身份失效而断开，重启不丢已接受的提交。

#### Acceptance Criteria

1. WHEN 会话过期、用户退出或被停用 THEN 该会话的 SSE SHALL 在一个心跳周期内断开；同一用户的其他有效会话不受影响（现在退出会断开该用户全部连接，要改）。
2. WHEN 收到关闭信号 THEN 系统 SHALL 依次：拒绝新提交（503）→ 通知在途 Handler 并等待配置时间 → 排空两个环 → 写快照 → 关闭 journal 和锁。
3. WHEN 已返回 200 的提交遇到正常关闭 THEN 它 SHALL 已经写入 journal，重启后可见。

### Requirement 9：长会话压测

**User Story:** 作为维护者，我需要知道长会话真的可用。

#### Acceptance Criteria

1. WHEN 跑不用真实模型的压测 THEN 一个 500 条消息的会话 SHALL 经历多次压缩，回放的 prompt 与实时相同，重启后视图逐字节相同。
2. WHEN 在浏览器打开 500 条消息的会话 THEN 流式更新 SHALL 只重绘变化的条目，保持可用（`console-ui` 需求 4.10 至今没有实测）。
3. WHEN 跑真实模型场景 THEN SHALL 有一个长任务真的越过压缩点：用很小的窗口配置，压缩前读到的事实在压缩后还能答对。写明判定标准、结论和用量。

## Non-Functional Requirements

### Code Architecture and Modularity
- value 新增 `ContextCompacted` 事件和压缩命令；kernel 新增压缩步骤和第六个钩子位置；只有 shell 调模型和读写快照。
- 内核不估算 token，只比较事件里的实测值，保持纯函数。

### Performance
- 压缩同步进行时，运行显示“正在压缩”，其他运行不受影响（Handler 在虚拟线程上，占一个模型并发名额）。
- 快照序列化不在内核线程上做。

### Security
- 摘要进入 journal，权限与其他事件相同；摘要调用沿用该用户的身份和额度。
- 手动压缩接口受 `Origin` 校验，只能操作自己的会话。

### Reliability
- 快照损坏、压缩失败都不能让服务或运行进入不可恢复状态。
- 新增事件后，旧 journal 必须能继续回放。

### Usability
- 用户能看到压缩发生过，并能看到摘要；文案集中在 `zh.ts`。
