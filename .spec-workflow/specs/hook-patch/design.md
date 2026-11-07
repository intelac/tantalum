# Design Document

## Overview

钩子补丁从“拼进 system 和工具列表”改成“追加一条 `Role.SYSTEM` 消息”。system 字符串和会话开始时的工具列表从此真正冻结；钩子的说明是消息里的文字块，钩子加的工具是追加在列表末尾的 deferred 定义加一条工具变化（现有 `ToolChangeBlock`）。这条路 core-tools C14 已经铺好（工具变化消息、`placeToolChanges`、`tool_addition` 请求、OpenAI 路线折算），这次只是让钩子也走它。

```
beforeModelCall ──Allow(patch)──▶ HookChain 合并 ──▶ HookPatches.apply(session, facts, patch)
                                                        │ 比较会话记下的“上次钩子说明 / 钩子工具”
                                                        ├─ 没变：什么都不做
                                                        └─ 变了：session.append(SYSTEM[HookTextBlock…, ToolChangeBlock])
                                                                 context.hooks 更新（文字、工具定义、现在开着的钩子工具）
                                           ──▶ PromptProjection.project(session)（不再收 patch）
                                           ──▶ CallModel
```

## Steering Document Alignment

### Technical Standards (tech.md)

- 内核纯函数、历史只追加：追加的消息和 `SessionContext` 的变化都是这一步的结果，随结果行落盘；回放不重跑钩子。
- 一行一步、旧行逐字节不变：新字段可空，空时不写出（`EventCodec` 默认 `NON_NULL`）；`Compacting.patch` 字段留着（旧行有它），新行里永远是 `NONE`，写出来和现在一样。
- 请求前缀稳定（prompt cache、思考签名）：system、工具列表、已发出的消息一个字不动。

### Project Structure (structure.md)

- 内容块在 `tantalum.value.model`；会话上下文在 `tantalum.value.state`；纯逻辑在 `tantalum.kernel.hook`（新类 `HookPatches`）；供应商适配在 `tantalum.shell.model`；轨迹在 `tantalum.shell.trace`。

## Code Reuse Analysis

### Existing Components to Leverage

- **`ToolChangeBlock` + `ContextSteps` 的工具变化消息**：钩子加的工具复用它，`ClaudeRequest.systemMessage` 的 `tool_addition`/`tool_removal`、`OpenAiClient` 的 `ToolChanges.active/describe` 都不用改。
- **`PromptProjection.placeToolChanges`**：改名 `placeSystemUpdates`，把“后面没有助手回复的 system 消息并到下一处合法位置”推广到带文字块的消息。
- **`ModelChanges.fold`**（shell）：换模型说明并进用户消息的做法，Sonnet 5 的降级照它写。
- **`HookDecided` 事实**：每个钩子的补丁长度按注册顺序已经在事实里，用它把合并后的字符串切回每个钩子的那一段，不改 `HookChain` 的返回值。
- **`TOOL_CHANGE_MODELS`**：官方文档说中途 system 消息和工具变化是同一批模型，直接复用这个名单。

### Integration Points

- `ModelSteps.callModel`：放行后、判断压缩前调用 `HookPatches.apply`。
- `CompactSteps.tryStart/…`、`RetrySteps`：`PromptProjection.project(session)` 不再收 patch；`Compacting` 构造时传 `PromptPatch.NONE`。
- `EventCodec`：注册新内容块子类型 `hookText`。
- `TraceSystem`、`TraceController` 请求详情：见“轨迹”。

## Architecture

### 1. 值类型

**`HookTextBlock(String hook, String text)`**（`tantalum.value.model`，加入 `ContentBlock` 的 `permits`）：钩子追加的说明，只出现在 `Role.SYSTEM` 消息里。`hook` 给轨迹页标来源，发给模型的只有 `text`。两者非空。

**`SessionContext.hooks`**（第 8 个字段，可空）：

```java
/** 钩子补丁的状态（hook-patch）。null 表示还没有钩子追加过东西（旧会话、压缩后重新冻结）。 */
public record HookState(String text, List<HookTool> tools, List<String> active) {
    /** 钩子加的一个工具：定义冻结，谁加的记下来给轨迹页。 */
    public record HookTool(String hook, ToolSpec tool) {}
}
```

- `text`：上次发给模型的合并说明（各钩子的 `systemAppend` 按注册顺序连起来，和 `HookChain` 的合并结果一致），用来判断变没变。
- `tools`：钩子加过的所有工具定义，按第一次出现的顺序，只增不减，发请求时接在 `context.tools()` 后面、永远 deferred。
- `active`：现在由钩子开着的工具名，用来算 `tool_addition`/`tool_removal`。

`SessionContext.withHooks(HookState)`；`withSections`、`withCompaction`、`withActive` 保留 `hooks`。`toolActive` 不看 `active`（需求 2.5）。

### 2. `HookPatches`（`tantalum.kernel.hook`，纯函数）

```java
record Applied(SessionState session, List<Fact> facts) {}

/** 把这次放行的补丁变成会话里的追加消息和上下文变化；没变化时原样返回。 */
static Applied apply(SessionState session, List<Fact> facts, PromptPatch patch, RunRef run)
```

步骤：

1. **切文字**：按 `facts` 里 `kind == PATCH`、`point == BEFORE_MODEL_CALL` 的 `HookDecided`（已是注册顺序）的 `systemChars` 依次从 `patch.systemAppend()` 切出每个钩子的一段，`systemChars == 0` 的跳过。长度对不上（理论上不会）就整段记成 `hook = "?"`，不丢。
2. **比文字**：`patch.systemAppend()` 和 `hooks.text()`（null 当 ""）相同就不追加文字块；不同且非空就生成文字块；不同且为空不追加（需求 1.3），但 `text` 更新为 ""。
3. **比工具**：`desired` = `patch.addTools()` 的名字（去重保序）；`current` = `hooks.active()`；`added = desired − current`，`removed = current − desired`。新名字的定义追加到 `tools`（同名已有则忽略新定义，需求 2.2）。有增减就生成 `ToolChangeBlock(added, removed)`（`unavailable` 为空）。
4. **位置检查**：会话最后一条消息是 `ASSISTANT` 时不追加，报 `HookReported(run, "hook-patch", "patch skipped: last message is from the assistant")`，上下文也不改。正常情况下模型调用前最后一条总是用户消息（提问、工具结果、续写）或已有的 system 更新。
5. 有块就 `session.append(new Message(Role.SYSTEM, blocks, false))`；`hooks` 按新值 `withHooks`。

`ModelSteps.callModel` 的放行分支：

```java
case Allow allow -> {
    HookPatches.Applied applied = HookPatches.apply(out.session(run.ref()), hook.facts(), allow.patch(), run.ref());
    if (applied.session() != out.session(run.ref())) { out.put(applied.session()); }
    out.report(applied.facts());
    if (compactionAllowed && CompactSteps.tryStart(out, run, new Compacting.Auto()) == TOOK_OVER) { return; }
    …
    Prompt prompt = PromptProjection.project(out.session(run.ref())).withModel(model(out, run));
```

追加在判断压缩之前：压缩看到的历史已经包含这条说明，它在尾部一定保留；摘要调用也从同一份会话投影，不再需要 `Compacting.patch`。

### 3. 投影

`PromptProjection.project(SessionState)`：

```java
tools = context.tools() 各自按 initialTools 标 deferred
      + hooks.tools() 每个 .deferred(true)
return new Prompt(context.system(), tools, session.view());
```

`placeToolChanges` → `placeSystemUpdates`：“system 更新”= `Role.SYSTEM` 且块全是 `HookTextBlock` 或 `ToolChangeBlock`（换模型的 `ModelChangeBlock` 消息照旧原样放行，由 `ModelChanges.fold` 处理）。后面不是助手回复的更新往后并：文字块按顺序累积，工具变化算净值；到合法位置时写成一条消息，文字块在前、净工具变化在后。被并的消息后面没有回复，没有签名绑定过它，和现在一样安全。

### 4. 供应商

**`ClaudeRequest`**：

- `systemMessages = TOOL_CHANGE_MODELS.contains(model)`。支持时 system 消息原样发：`HookTextBlock` → `{"type":"text","text":…}`，`ToolChangeBlock` → 现有引用块。beta 头 `mid-conversation-tool-changes` 只在历史里有 `ToolChangeBlock` 或 deferred 工具时带（现有 `ToolChanges.present` 不变：它只看工具变化和 deferred 工具，不看文字）。
- 不支持时：现有做法是过滤掉所有 `Role.SYSTEM` 消息。改成：带文字块的 system 消息把文字（每块一段，前面加 `ToolChanges.PREFIX`）并到前一条用户消息末尾（新类 `HookTexts.fold`，照 `ModelChanges.fold` 写）；`ToolChangeBlock` 照旧丢弃。`fallbacks`：文字不影响；工具变化照旧关掉备用模型。
- 缓存断点：仍在最后一条非 system 消息的最后一个块（现有 `cachedMessage` 规则）。

**`OpenAiClient.addMessage`**：`case HookTextBlock note -> append(text, ToolChanges.PREFIX + note.text())`（system 消息已经按用户角色发）。

### 5. 轨迹

- `TraceSystem.split`：system 段只剩 `CONTEXT` 一段（system 等于冻结值就是 `[CONTEXT]`，否则 `[UNKNOWN]`）；工具来源：`context.tools()` 为 `CONTEXT`，`hooks.tools()` 为 `Source.plugin(hook)`，对不上整体 `UNKNOWN`。`SystemPrompt` 记录的字段不变。
- 请求详情 `GET /api/sessions/{s}/trace/calls/{c}` 多一个字段 `additions: [{hook, text}]`：这次请求消息里所有 `HookTextBlock`（含被并进去之前的原块——直接扫 `prompt.messages()`，投影后的消息里块是原样搬过去的）。
- 控制台 `RequestTabs` 的 `CallSystem`：有 `additions` 时在 system 下面一节“插件追加的说明”，每条“插件 · 名字”加文字；三种语言。`SystemTabs` 不变。
- `trace.spec.ts`：原断言“系统提示词里有补丁、分段标出来源”改成断言这一节。

### 6. 压缩

`adopt` 现在把 `context` 置 null，下一次组装重新冻结；`hooks` 随之为 null，下一次 `beforeModelCall` 的说明和工具自然重新追加（需求 1.4、同 J7）。不用改 `CompactSteps` 的逻辑，只去掉 `patch` 参数：`tryStart(out, run, cause)`，`new Compacting(cause, plan, PromptPatch.NONE)`。`Compacting.patch` 加注释“hook-patch 后总是 NONE，留着是为了读旧行”。

### 7. 不变的

`HookChain`（仍合并、仍每钩子报 `HookDecided`）、`PromptPatch`、`Allow`、`HookDecided`、`TraceHooks`、对话页。

## Components and Interfaces

| 组件 | 职责 | 依赖 |
|---|---|---|
| `value.model.HookTextBlock` | 钩子说明块 | — |
| `value.state.SessionContext.HookState` | 钩子补丁状态 | `ToolSpec` |
| `kernel.hook.HookPatches` | 补丁 → 追加消息 + 上下文变化 | `HookDecided`、`SessionState` |
| `kernel.projection.PromptProjection` | 投影（无 patch）、`placeSystemUpdates` | — |
| `shell.model.ClaudeRequest` / `HookTexts` | 文字块的发送与降级 | `ToolChanges.PREFIX` |
| `shell.model.OpenAiClient` | 文字块按用户消息发 | 同上 |
| `shell.trace.TraceSystem` / `TraceController` | 来源切分、请求详情 `additions` | — |
| `console/trace/inspector/RequestTabs` | 显示追加的说明 | 词典 |

## Data Models

- journal：`Message.content` 里新块 `{"kind":"hookText","hook":"…","text":"…"}`；`SessionContext` 多一个可空字段 `hooks: {text, tools:[{hook, tool}], active:[…]}`，上下文 delta 机制照旧按字段写。
- 请求详情响应多 `additions`。

## Error Handling

- 切段长度对不上：整段记 `hook="?"`，不丢文字。
- 最后一条是助手消息：不追加，报 `HookReported`。
- Sonnet 5 收到 system 消息会 400（文档）：我们不会发，降级并进用户消息。

## Testing Strategy

### Unit Testing

- `HookPatchesTest`：首次追加、相同不追加、变化再追加、变空不追加、压缩后（`hooks` 为 null）重追加、加工具（deferred 定义 + `tool_addition`）、同名沿用旧定义、撤回（`tool_removal`）、文字和工具同一条消息、最后一条是助手消息时跳过并上报、按事实切段。
- `PromptProjectionTest`：无 patch 投影、钩子工具 deferred 接在末尾、`placeSystemUpdates` 合并带文字块的未回复更新。
- `ModelStepsTest`：补丁变成 system 消息、下一圈不重复。
- `ClaudeRequestTest`：新期望段——支持的模型发 `role: system` 文字块（无 beta 头）、文字加工具变化同一条、Sonnet 5 并进用户消息；现有期望文件逐字节不变。
- `OpenAiClientTest`：文字块按用户消息带前缀发。
- `TracePassTest`：system 行只在初始/压缩后出现；请求详情 `additions`。
- `RecordCodecTest`：`hooks` 字段的 delta 编解码；旧行不变。
- 控制台 vitest：`RequestTabs` 显示追加的说明。

### Integration Testing

- `MatrixFixture`：PATCH 判定改成“消息里有说明块和工具变化”。
- Playwright `trace.spec.ts` 改断言。
- 全量回归一次。

### End-to-End / Real Model

- `CoreToolsClaudeTest` 新增一项（需求 5），严格模式。

## Risks

- **Sonnet 5 降级的前缀稳定性**：并进前一条用户消息依赖“钩子消息紧跟它所并入的用户消息”，这由“模型调用前最后一条是用户消息”保证；重试时说明变了会并两段，但那条用户消息没有回复绑定。
- **钩子每圈变文字**（比如把时间戳写进说明）：每圈追加一条，历史变长，不坏但费 token；README 提醒。
