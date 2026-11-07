# Requirements Document

## Introduction

BACKLOG 第 0 节“离 0.1 还差什么”的第三项，一个 bug。

钩子在 `beforeModelCall` 放行时可以带一个补丁（`Allow(PromptPatch(systemAppend, addTools))`）。现在补丁的生效方式是**拼**：`HookChain` 把各钩子的 `systemAppend` 连起来，`PromptProjection.project` 把它接在会话冻结的 system 后面、把 `addTools` 接在工具列表后面（`PromptProjection.java:25-33`）。

问题：Claude 现在的模型把每段思考内容和当时的 system、工具列表绑在一起（core-tools 设计里的实测：改工具列表直接 400 `Invalid signature in thinking block`）。补丁一变——比如每日额度插件说“用了一半”变成“用了九成”，或者某个插件被用户关掉、补丁消失——这个会话历史里所有思考块的签名全部失效。我们的账号目前不强制校验，所以没炸；新账号（2026-08-31 之后建的）和以后都会强制。示例插件都没用会变的补丁，所以还没踩到，但插件 SDK 在 0.1 前要锁定，这个口子必须先改掉。

例子：

| 时间 | 发生了什么 | 现在 | 做完后 |
|---|---|---|---|
| 第 1 问 | 额度插件在模型调用前追加“今天还剩 80%” | 拼进 system | 追加一条 `role: system` 消息在提问后面 |
| 第 1 问第 3 圈 | 插件还是“80%” | 每圈都拼，system 不变，没事 | 没变化，不追加 |
| 第 2 问 | 变成“还剩 40%” | system 变了，第 1 问的思考块签名全失效，严格模式下 400 | 再追加一条说明，前面的一个字不动 |
| 第 3 问 | 用户在插件页关掉额度插件 | system 缩回去，又失效一次 | 什么都不追加（上一条说明留在历史里） |
| 任意一问 | 某插件要加一个工具 | 拼进工具列表，签名失效 | 工具定义以 deferred 追加在列表末尾，再追加一条 `tool_addition` |

### 调研

参照 pi-mono `main`（7f47a2e）、DSH `main`（fcc0ad4621）、Claude 官方文档（claude-api skill 的 `shared/prompt-caching.md`、`shared/preserved-thinking-migration.md`、`shared/platform-availability.md`，2026-11 版）。

- **Claude 文档**：“Keep the system prompt frozen … Inject dynamic context later in `messages` instead - as a `{"role": "system", ...}` message where supported”（`prompt-caching.md` § Architectural guidance）；会话中途的 `role: system` 消息文字内容不需要 beta 头，支持的模型是 Opus 5/5.5/4.8、Fable 5/5.1、Mythos 5/5.1、Sonnet 5.5，**不含 Sonnet 5**（`platform-availability.md:48`）；放置规则：必须跟在 `user` 消息后面，后面要么是 `assistant` 要么是最后一条，不能是第一条，内容只能是文字（`prompt-caching.md` § Mid-conversation system messages）。加工具：“append it to `tools` with `defer_loading: true` (a deferred tool nothing has referenced yet is outside the compared prefix, so appending it is safe) and announce it with a `tool_addition` block”（`preserved-thinking-migration.md:197`）。已经发出的中途系统消息要永远留在历史里（“persist it with the transcript and replay it”，同文 201 行）。
- **pi**：扩展的 `before_agent_start` 可以返回 `systemPrompt`（整段替换，`coding-agent/src/core/extensions/types.ts:1517-1521`），也可以返回 `message`（追加一条自定义消息进历史）。Anthropic 路线把历史里的 system 消息按原生 `role: system` 发，工具变化用 `tool_addition`/`tool_removal`（`ai/src/api/anthropic-messages.ts:1641-1663`），还用 `inline-tools` beta 把工具定义直接写在消息里（同文 1434-1436）。pi 用整段替换是因为它的系统提示词本来就是历史里的第一条消息，每轮可以重算；我们的 system 是冻结的，不能照抄。
- **DSH**：历史里有 `developer` 角色的消息（“Incremental agent session changes in conversation order, currently tool additions and removals”，`llm/llm/src/message.ts:156-159`），工具注册表变了就追加一条（`core/agent-loop/src/agent.ts:694-705`），发给模型时变成 pi-ai 的 system 消息，内容是文字加 `toolsAdded`/`toolsRemoved`（`llm/llm-pi-ai/src/context.ts:170-203`）；模型不支持中途 system 时降级成用户消息里的 `<system-reminder>`（同文 229-238）。DSH 的 `tool-addition` 按名字引用请求头里已经声明的工具，和我们 core-tools 的做法一样。
- 我们已有同样的机制：core-tools C14 的工具变化消息（`ToolChangeBlock`，`Role.SYSTEM`）、model-picker 的换模型说明。这个 spec 就是把钩子补丁也改到这条路上，不发明新东西。

### 范围

做：

- 钩子的 `systemAppend` 改成追加 `role: system` 消息；`addTools` 改成 deferred 工具定义加 `tool_addition`。插件 SDK（`PromptPatch`、`Allow`）不改。
- 变化才追加；压缩后重新告知。
- 不支持中途 system 消息的模型（Sonnet 5、OpenAI 兼容路线的本地模型）有降级方案。
- 轨迹页的请求详情能看到钩子追加了什么、谁追加的。
- 真实 Claude 严格模式验证一次。

不做：

- 对话页不显示钩子追加的说明（轨迹页看得到；有需要再加）。
- 撤回说明：说明只能追加，不提供“撤回上一条”的机制（和官方“never deleted”一致）。插件要改口就再发一句。
- 钩子加的工具的撤回只在钩子不再返回它时发 `tool_removal`，不提供显式 `removeTools`。
- `clear_at`（只对这一轮有效的说明）和 `inline-tools` 两个 beta 不用：前者是另一种语义（每轮提醒），后者没必要——工具定义可以追加在列表末尾。

## Alignment with Product Vision

企业底座的插件 SDK 要在 0.1 锁定，补丁语义必须是对现代模型安全的。事件驱动、历史只追加的原则（tech.md）在这里就是“说明以消息追加，发出去的不改”。

## Requirements

### Requirement 1：钩子的说明以 system 消息追加

**User Story:** 作为插件作者，我在 `beforeModelCall` 返回的 `systemAppend` 要能被模型当作系统指令看到，又不能让会话里已有的思考签名失效。

#### Acceptance Criteria

1. WHEN `beforeModelCall` 的放行带非空 `systemAppend` 且和会话上一次发给模型的钩子说明不同 THEN 内核 SHALL 在这次调用的消息末尾追加一条 `Role.SYSTEM` 消息，内容是每个钩子一块（块里记钩子名和它那段文字，按注册顺序），会话的 system 字符串一个字不变。（出处：Claude `prompt-caching.md` § Mid-conversation system messages；DSH `developer` 消息。）
2. WHEN 合并后的说明和上一次相同 THEN SHALL 不追加（一次提问里的多圈、下一次提问都如此）。
3. WHEN 说明变成空（钩子不再返回、插件被关掉） THEN SHALL 不追加任何东西，历史里已有的说明保持原样。
4. WHEN 会话压缩之后第一次调用模型 THEN 钩子当前的说明 SHALL 重新追加一次（压缩清掉了冻结的上下文，之前的说明可能已进摘要）。（出处：同 core-tools J7 对工具变化的处理。）
5. WHEN 追加的 system 消息后面还没有助手回复、下一次调用又要追加 THEN 组请求时 SHALL 合并成一条（文字按顺序接在一起、工具变化算净变化），和 `PromptProjection.placeToolChanges` 的规则一致。（出处：Claude 放置规则——system 消息后面只能是 assistant 或结尾。）
6. 追加过的说明 SHALL 随会话历史记进 journal，恢复、回放时照样还原；`check` 不重跑钩子。

### Requirement 2：钩子加的工具以 deferred 定义加 tool_addition 表达

**User Story:** 作为插件作者，我在 `beforeModelCall` 返回的 `addTools` 要能被模型调用，又不改会话已经发出的工具列表。

#### Acceptance Criteria

1. WHEN 放行带 `addTools` 里会话没见过的工具 THEN 它的定义 SHALL 追加到会话工具列表的末尾、发给模型时标 `defer_loading`，并追加一条 `tool_addition`（现有 `ToolChangeBlock`）让模型看见它。（出处：Claude `preserved-thinking-migration.md:197`。）
2. WHEN 同名工具再次出现 THEN SHALL 沿用第一次的定义（定义冻结，和官方“Freeze each tool's description and schema text for the life of the conversation”一致）。
3. WHEN 上一次由钩子加的工具这次不在 `addTools` 里 THEN SHALL 追加 `tool_removal`；模型仍调用时按现有规则在执行前拒绝（`PluginGate`）。
4. 说明和工具变化同时有 SHALL 放在同一条 system 消息里（文字块在前，工具变化在后）。（出处：pi `anthropic-messages.ts:1645-1663` 一条消息里既有文字也有工具块。）
5. 钩子加的工具 SHALL 不进 `activeTools`、`toolsSeen`：用户在插件页开关工具的那套逻辑（`ContextSteps.freezeContext`）看不到它们，不会把它们当用户关掉的工具。

### Requirement 3：不支持中途 system 消息的模型有降级

**User Story:** 作为管理员，我配了 Sonnet 5 或本地模型，钩子的说明也要能到模型。

#### Acceptance Criteria

1. WHEN Claude 路线的模型支持中途 system 消息（现有 `TOOL_CHANGE_MODELS` 名单） THEN 文字块 SHALL 按 `{"type":"text"}` 放进 `role: system` 消息；只有文字、没有工具变化时 SHALL 不带 `mid-conversation-tool-changes` beta 头（文字不需要 beta）。
2. WHEN Claude 路线的模型不支持（Sonnet 5） THEN 钩子说明 SHALL 并进它前面那条用户消息的末尾，前缀是现有的“[Context update, not written by the user. …]”；工具变化照现有规则不发（这类模型在会话有工具变化后本来就不能选）。（出处：Claude 文档“fall back to a `<system-reminder>` text block in the user turn”；DSH `context.ts:229-238`。）
3. WHEN OpenAI 兼容路线 THEN 说明 SHALL 按现有“中途系统消息按用户消息发”的规则发，带同样的前缀；工具变化照 `ToolChanges.active` 折算。
4. 并进用户消息是纯投影：同一段历史每次投影结果相同，请求前缀不变。

### Requirement 4：轨迹页看得到谁追加了什么

**User Story:** 作为排查问题的人，我要在轨迹页看到这次请求里钩子追加了哪些说明。

#### Acceptance Criteria

1. 请求详情的“系统提示词”页签 SHALL 在冻结的 system 下面列出这次请求消息里钩子追加的说明：钩子名和文字。
2. “消息”页签 SHALL 原样显示追加的 system 消息（现有 JSON 树）。
3. `HookDecided` 事实和钩子决定行 SHALL 不变（仍报长度和工具名）。
4. system 变化行（`SystemPrompt` 记录）SHALL 只在真正变化时出现：会话第一次请求和压缩之后；`split` 不再按钩子切 system 字符串。

### Requirement 5：真实模型验证

#### Acceptance Criteria

1. 真实 Claude（`claude-sonnet-5-5`，严格思考校验 `prefix_mismatch_behavior: error`）上 SHALL 跑一个场景：第 1 问钩子追加说明；第 2 问说明变了并加一个工具；第 3 问工具被撤回。判定：没有 `INVALID_REQUEST`，第 2 问模型调用了钩子加的工具，三问的回复都带思考块。
2. 用量 SHALL 报给 Yi。

### Requirement 6：文档

#### Acceptance Criteria

1. README“写插件”的钩子表和补丁说明 SHALL 改成新语义，写明“只追加、不撤回、变化才发、压缩后重发”。
2. tech.md SHALL 记一节结果；BACKLOG 第 0 节 SHALL 标完成。

## Non-Functional Requirements

### Code Architecture and Design

- 内核仍是纯函数：追加的消息和上下文变化是步骤结果的一部分，随结果行记进 journal。
- 新内容块一个（钩子说明），新的 `SessionContext` 字段一个（钩子相关的状态，整体可空）；旧 journal 行逐字节不变（空字段不写出）。
- 插件 SDK 不改：`PromptPatch`、`Allow`、`HookDecided` 形状不变。

### Performance

- 变化才追加，常量说明只发一次；请求前缀稳定，prompt cache 继续命中。

### Security

- 说明走 `role: system` 是“operator channel”，用户消息里伪造不了；降级到用户消息时带不是用户写的前缀。

### Reliability

- 追加消息的位置规则有单元测试：最后一条是助手消息时（理论上不会发生）不追加，报一条 `HookReported`。
