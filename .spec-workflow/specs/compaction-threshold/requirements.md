# Requirements Document

## Introduction

现在的压缩点用的窗口大小是写死的配置：`tantalum.claude.context-window=200000`，而我们用的 `claude-sonnet-5-5` 实际是 1M（2026-11-04 读 Models 接口：`max_input_tokens` 1000000），所以对话到 13.6 万 token 左右就压缩了，大半个窗口没用上。本 spec 做三件事：

1. 窗口大小不写死，每个模型用它自己的：从模型那边读，读不到才用配置（Yi 2026-11-04：“窗口是满1M还是256k我觉得要取决于模型设定 它是一个变量”）。
2. 每个用户在画面上选压缩点：窗口的 50%、70%、85% 或用满（Yi 2026-11-04：“在画面上给几个百分比选项 然后根据模型自己的上下文长x这个百分比去获得自动压缩的点”）。
3. token 估算按模型分别定系数，并改成宁可多算：现在的“字节数 ÷ 3”对 Claude 是少算的。

参照的是 pi-mono 的 `main`（0096774）和 DSH 的 `main`（ce7073d5）：

- **pi** 的窗口来自构建时生成的模型目录（取自 models.dev，`AI/catalog/sources/models-dev.ts:6,197`），用户可以在 `~/.pi/agent/models.json` 里覆盖（`CA/src/core/provider-composer.ts:199`）；llama.cpp 扩展在运行时读服务器报的 `meta.n_ctx`（`CA/src/extensions/llama/provider.ts:71-79`）。压缩点是 `窗口 − reserveTokens`（默认 16384），没有百分比（`CA/src/core/compaction/compaction.ts:267-270`、`settings-defaults.ts:9-13`）。设置里只有“自动压缩”开关（`modes/interactive/components/settings-selector.ts:486-491`）。
- **DSH** 的窗口由模型适配器给出，DeepSeek 适配器在内置目录之上叠加 `GET /models` 返回的 `context_window`，后台每 10 分钟刷新、超时 5 秒、失败时保留上次的结果（`packages/llm/llm-deepseek/src/listing.ts:179-191`、`listing-cache.ts:169-195`、`listing-config.ts:7,9`）。压缩点是 `min(窗口 × thresholdRatio, 窗口 − 输出预留 − headroomTokens)`，`thresholdRatio` 默认 0.8（`packages/compaction/compaction-basic/src/config.ts:19-23,172-194`），和我们要做的形式一样；但它只能在配置文件里按模型改，画面上没有选项，控制台只有一个上下文占用环（`packages/client/ui-conversation/src/client/skeleton/ContextMeter.tsx:56-180`）。
- 两边的估算都是“字符数 ÷ 4”（pi `compaction.ts:294-349`；DSH `packages/llm/token-meter/src/estimate.ts:12-19`），都把它当作偏保守的估计；按我们的实测，这对 Claude 是明显少算的（见下面“实测数据”）。

路径写法：pi 的 `AI` 指 `packages/ai`，`CA` 指 `packages/coding-agent`；DSH 路径相对于仓库根目录；两边都没有对应做法的标“自定”。本 spec 改的是 `context-compaction` spec 的术语“窗口 W”“阈值 T”和规则 1 的估算，其余规则（怎么切、压缩后模型看到什么、超长恢复）不变。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| 启动时从模型那边读窗口大小，读不到用配置 | 每个会话单独设压缩点（Yi 定的是每个用户自己设） |
| Claude 开了备用模型时，按主模型和备用模型中最小的窗口算 | 在画面上改预留、保留预算等其余压缩参数（照旧在配置文件里） |
| 设置画面的“模型”页选压缩点，每个用户一份 | Claude 服务器端的压缩（`compact_20260112`）：我们自己的压缩已能跨模型，换成服务器端的另开 spec |
| 改了压缩点从下一次提问起生效，同一个会话也是 | 一个服务器同时接多个模型、按会话选模型（现在一个服务器只配一个模型） |
| 估算系数按供应商分别定，实测后改成宁可多算 | 调模型的 token 计数接口做精确计数（每次压缩判断都要多一次请求） |
| 状态栏的占比和提醒按用户选的压缩点算 | |
| 1M 窗口下的真实模型测试 | |

### 实测数据

**窗口大小**（2026-11-04）：

| 模型 | 从哪读 | 结果 |
|---|---|---|
| `claude-sonnet-5-5` | `GET /v1/models/claude-sonnet-5-5` 的 `max_input_tokens` | 1000000（输出上限 `max_tokens` 128000） |
| 它的备用模型 | 同一接口加请求头 `anthropic-beta: server-side-fallback-2026-06-01` 时多出的 `allowed_fallback_models` | `["claude-sonnet-5"]`，1M |
| 本地 qwen（llama.cpp） | `GET http://127.0.0.1:8090/v1/models` 里这个模型的 `meta.n_ctx` | 131072 |

**每个 token 平均多少字节**。Claude 用 `count_tokens` 接口（不按 token 收费），本地 qwen 用两次请求的 `prompt_tokens` 之差。字节数越小，“字节数 ÷ 3”少算得越多：

| 内容 | `claude-sonnet-5-5` | 本地 qwen |
|---|---|---|
| Java 源码 | 2.67 | 4.28 |
| 中文 | 2.25 | 4.84 |
| 英文 | 1.82 | 3.36 |
| JSON | 1.80 | 2.21 |
| sha256 十六进制 | 1.52 | 1.13 |
| id 类字符串 | 1.48 | — |
| base64 | 1.05 | 1.46 |
| 混合样本合计 | 估算 ÷ 真实 = 0.76（少算四分之一） | 2.49 |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 |
|---|---|---|
| T1 | 窗口从哪读 | Claude：`GET /v1/models/{模型}` 的 `max_input_tokens`。OpenAI 兼容服务：`GET {base-url}/models` 里 `id` 等于配置模型名的那一项，依次找 `meta.n_ctx`（llama.cpp）、`max_model_len`（vLLM）、`context_length`（LM Studio 等）。都没有或请求失败时用配置的 `context-window`，并在日志和设置页写明“来自配置”。pi 的 llama.cpp 扩展读 `meta.n_ctx`（`CA/src/extensions/llama/provider.ts:71-79`）；DSH 读 `/models` 的 `context_window`（`llm-deepseek/src/listing.ts:179-191`），它的通用发现另认 `context_length`、`max_input_tokens`（`llm/llm-pi-ai/src/discovery.ts:78-87`）。vLLM 的 `max_model_len` 两边都没有，自定 |
| T2 | 什么时候读 | 启动时读，最多等 5 秒，不让服务因为模型那边慢而起不来；没读到就先用配置值。之后每 10 分钟在后台再读一次，失败时保留上次读到的值（同 DSH `listing-cache.ts:169-195`、`listing-config.ts:7,9`）。模型返回“上下文超长”时再读一次：本地服务可能换了更小的 `n_ctx` 重启过。读到的新值从下一次提问起生效，自定 |
| T3 | 备用模型 | Claude 开着备用模型（`tantalum.claude.fallbacks=true`）时，服务器可能把请求转给备用模型，所以窗口取主模型和 `allowed_fallback_models` 里各模型的最小值。读不到备用模型的窗口时只用主模型的，靠超长恢复兜底。现在两者都是 1M。DSH 在换模型前先按新模型的窗口压缩（`compaction-basic/src/index.ts:181-203`），我们的备用模型是 Claude 服务器自己换的，事先不知道，所以只能按最小的算，自定 |
| T4 | 压缩点的选项 | 50%、70%、85%、用满，默认用满（Yi 2026-11-04）。压缩点 `T = min(W × 百分比, W − R)`，`R` 是给回复留的预算（`max(reserve-tokens, 模型输出上限)`，不变）。用满就是现在的 `W − R`。例：`claude-sonnet-5-5` W = 1000000、R = 64000，四档是 500000、700000、850000、936000；qwen W = 131072、R = 16384，四档是 65536、91750、111411、114688 |
| T5 | 存在哪、谁能改 | 每个用户一份，存在 `users.db` 的新表 `user_settings` 里，和插件开关同一个文件，不进 journal；只改自己的。没设过的用户用默认（用满），自定 |
| T6 | 什么时候生效 | 从下一次提问开始，同一个会话也是，和插件开关一样（core-tools C14）。现在压缩设置在会话第一次组装上下文时定下，之后不变；改成每次提问组装上下文时按用户当前的选择和当前的窗口重算，记进 `ContextAssembled` 事件，回放读这份记录，结果不变。压缩点只决定什么时候压缩，不改发给模型的内容，所以不影响 prompt 缓存，也不影响历史里的思考签名。改小以后，已经超过新压缩点的会话在下一次提问调模型前就会压缩，自定 |
| T7 | 估算系数 | 规则 1 的估算改成“UTF-8 字节数 ÷ 系数”，系数按供应商配置：Claude 默认 1.5，OpenAI 兼容默认 2.0，记进压缩设置，回放用同一个值。按上面的实测，Claude 的 1.5 对 base64 和 id 以外的内容都是多算，qwen 的 2.0 对哈希和 base64 以外的内容都是多算。估算只用在“上次回复之后新增的部分”和压缩后第一次调用前，其余用模型报的实测用量（规则 1 不变），所以多算的代价只是早一点压缩，自定 |
| T8 | 画面上怎么选 | 设置画面的“模型”页：显示窗口大小和来源（“模型报告”或“来自配置”），下面四个单选项，每项写出对应的 token 数（如“85%（约 85 万）”）。选了立即保存，写明“从下一次提问开始生效”。pi 和 DSH 画面上都没有压缩点选项（见上），这是 Yi 定的新功能 |
| T9 | 状态栏 | 占比照旧是“最近一次调用的输入 ÷ 窗口”。提醒改成按压缩点算：到压缩点的 90% 时变色（现在是固定的窗口 80%），提示里写明“到 X% 时自动压缩”，自定 |

## Requirements

### Requirement 1：窗口大小从模型那边读

**User Story:** 作为部署的人，我希望服务器自己知道模型的窗口有多大，以便换模型时不用记着改配置，也不会因为配置写小了而白白提前压缩。

出处：pi `CA/src/extensions/llama/provider.ts:71-79`、`AI/src/models.ts:546-590`；DSH `packages/llm/llm-deepseek/src/listing.ts:179-191`、`listing-cache.ts:169-195`、`packages/llm/llm/src/index.ts:760-800`。

#### Acceptance Criteria

1. WHEN 供应商是 Claude 且 Models 接口返回 `max_input_tokens` THEN 服务器 SHALL 用它做窗口 W，设置页显示来源“模型报告”。
2. WHEN 供应商是 OpenAI 兼容服务且 `{base-url}/models` 里配置的模型带 `meta.n_ctx`、`max_model_len` 或 `context_length` 之一 THEN 服务器 SHALL 按这个顺序取第一个正数做 W。
3. WHEN 读取失败、超时（5 秒）或返回里没有窗口大小 THEN 服务器 SHALL 照常启动，用配置的 `context-window`，日志写一条警告（不含 API Key 和请求头），设置页显示来源“来自配置”；配置也是 0 时照旧不做自动压缩，只做超长恢复。
4. 服务器 SHALL 每 10 分钟在后台再读一次，读到的值和原来不同时从下一次提问起用新值；读取失败时保留上次读到的值。
5. WHEN 模型返回上下文超长 THEN 服务器 SHALL 在后台再读一次窗口；新值比原来小时从下一次提问起用新值。
6. WHEN Claude 开着备用模型且读得到 `allowed_fallback_models` 里各模型的窗口 THEN W SHALL 取主模型和它们的最小值；读不到时只用主模型的。
7. WHEN 读到的窗口不大于预留 R THEN 服务器 SHALL 当成读取失败处理（同第 3 条），不让压缩设置无效。
8. `/api/config` SHALL 返回读到的窗口和来源，前端不再只看配置值。

### Requirement 2：每个用户选压缩点

**User Story:** 作为用户，我希望自己选在窗口用到多少时压缩，以便在“少花钱、回复快”和“让模型记得更多原文”之间按自己的用法取舍。

出处：Yi 2026-11-04；DSH 的 `thresholdRatio`（`packages/compaction/compaction-basic/src/config.ts:172-194`）只能在配置文件里改，pi 没有百分比。

#### Acceptance Criteria

1. WHEN 用户打开设置画面的“模型”页 THEN 页面 SHALL 显示窗口大小、来源和四个选项（50%、70%、85%、用满），每项写出对应的压缩点 token 数，当前选择被选中；没选过时选中“用满”。
2. WHEN 用户选另一项 THEN 前端 SHALL 立即保存，成功后显示“从下一次提问开始生效”；保存失败时退回原来的选择并提示。
3. WHEN 保存 THEN 服务器 SHALL 只改当前登录用户的设置，存进 `users.db`，重启后还在；别的用户看不到、改不了。
4. WHEN 请求的值不是四个选项之一 THEN 服务器 SHALL 返回 400，不保存。
5. WHEN 窗口未知（W = 0） THEN 页面 SHALL 写明“不知道模型的窗口大小，不做自动压缩”，选项不可选。

### Requirement 3：压缩点生效

**User Story:** 作为用户，我希望改了压缩点后在当前对话里也马上起作用，以便不用为了换设置去开新会话。

出处：core-tools C14（开关从下一次提问起生效）；context-compaction 规则 2；pi 换模型后下一次检查用新模型的窗口（`CA/src/core/agent-session.ts:3044-3046`）；DSH 每次调用都重新解析模型信息（`packages/llm/llm/src/index.ts:902-908`）。

#### Acceptance Criteria

1. WHEN 一次提问开始组装上下文 THEN 服务器 SHALL 按这个用户当前的选择和当前的 W 算出压缩设置（`T = min(W × 百分比, W − R)`），记进 `ContextAssembled`。
2. WHEN 会话已有压缩设置且新算出的不同 THEN 内核 SHALL 换成新的；不在会话里追加任何消息，发给模型的请求前缀不变。
3. WHEN 运行进行到一半时用户改了设置 THEN 这个运行 SHALL 继续用开始时的设置，下一次提问才换。
4. WHEN 回放 journal THEN 每个运行 SHALL 用它自己的 `ContextAssembled` 里记的设置，结果和当时一样；没有新字段的旧事件照旧解码，按当时的规则（窗口 − 预留、系数 3）处理。
5. WHEN 新的压缩点比会话当前大小小 THEN 下一次调模型前 SHALL 按规则 2 自动压缩，规则 3 的防循环照旧。
6. WHEN 用户改了选择 THEN 保留预算 SHALL 照旧是 `min(keep-recent-tokens, T / 4)`，随新的 T 变。

### Requirement 4：估算宁可多算

**User Story:** 作为用户，我希望服务器估算对话长度时宁可多算，以便窗口用得再满也不会因为少算而撞上“上下文超长”，白白多一次失败的调用。

出处：上面的实测；context-compaction 规则 1；pi `CA/src/core/compaction/compaction.ts:294-349`；DSH `packages/llm/token-meter/src/estimate.ts:12-19`。

#### Acceptance Criteria

1. 规则 1 的估算 SHALL 改成 `ceil(UTF-8 字节数 ÷ 系数)`，系数来自压缩设置；图片按像素、PDF 按页的算法不变。
2. WHEN 配置 THEN SHALL 提供 `tantalum.claude.bytes-per-token`（默认 1.5）和 `tantalum.openai.bytes-per-token`（默认 2.0），不是正数时启动失败。
3. 系数 SHALL 随压缩设置记进 `ContextAssembled`，回放用记下的值。
4. WHEN 用实测样本（Java、中文、英文、JSON，各取仓库里的真实文件）测 Claude 和本地 qwen THEN 新估算 SHALL 不小于真实 token 数。

### Requirement 5：状态栏

**User Story:** 作为用户，我希望状态栏告诉我离自动压缩还有多远，以便在压缩前自己决定要不要先手动压缩或开新会话。

出处：pi 页脚显示“占比/窗口”，70% 以上警告色、90% 以上错误色（`CA/src/modes/interactive/components/footer.ts:160-212`）；DSH 输入框下的占用环（`ContextMeter.tsx:56-180`）。

#### Acceptance Criteria

1. 状态栏的占比 SHALL 照旧按“最近一次调用的输入（含缓存读写）÷ W”算，W 用服务器读到的窗口。
2. WHEN 占比达到压缩点百分比的 90%（用满时按 `(W − R) ÷ W` 算） THEN 占比 SHALL 变成提醒色，悬停说明写“到 X% 时自动压缩”。
3. WHEN 用户改了压缩点 THEN 状态栏 SHALL 不刷新页面就按新的压缩点显示。

## Non-Functional Requirements

### 安全

- 读窗口的请求照旧从环境变量取 API Key，日志、journal、SSE、设置页都不出现 Key 和请求头。
- `user_settings` 和 `users.db` 同样只有服务进程能读（600）。

### 性能

- 读窗口不阻塞启动超过 5 秒，不阻塞任何一次提问。
- 每次提问多查一次用户设置，走内存缓存，不读库。

### 兼容

- 旧 journal 不加迁移就能回放（Requirement 3 第 4 条）。
- 配置里的 `context-window` 保留，意思变成“读不到时用的值”。

### 测试

- 单元：读窗口的各种返回（有值、缺字段、超时、比预留还小）；最小窗口；四档压缩点的算术；设置变化的内核步骤和回放；系数。
- 端到端（Playwright）：选压缩点、刷新后还在、两个用户互不影响、手机宽度；状态栏提醒按压缩点变化。
- 真实模型（有判定标准和结论，记在实现日志里）：
  1. Claude：启动时读到 1M；选 50%，把会话撑过 50 万 token，确认在调模型前自动压缩、给 50 万 token 的头部做摘要的调用成功、运行接着完成。这是 1M 窗口下唯一新的风险：头部比现在大五倍。预计花 3 美元左右。
  2. 本地 qwen：读到 131072；选 50%，确认在约 65536 时压缩。不花钱。
  3. 两个模型各用实测样本验证估算不小于真实值（Requirement 4 第 4 条），用 `count_tokens`，不花钱。
