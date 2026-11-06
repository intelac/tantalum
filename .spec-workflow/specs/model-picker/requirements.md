# Requirements Document

## Introduction

现在模型和思考强度是整个服务器一份配置：`tantalum.provider` 选 Claude 或本地服务，`tantalum.claude.model`、`tantalum.claude.effort`、`tantalum.openai.model` 各一个值（`tantalum-shell/src/main/java/tantalum/shell/boot/TantalumProperties.java:131-146`），服务器只建一个模型客户端（`boot/TantalumApplication.java:124-148`），所有用户、所有会话都用它。输入框旁只显示模型名（`console/src/composer/Composer.tsx:190-193`），本地服务根本不发思考参数（`model/OpenAiClient.java:96-102`）。

本 spec 做到“像 dsh 一样在画面上可以选择模型和 thinking”（Yi 2026-11-06，BACKLOG“画面上选模型和思考强度”）：

- **管理员配几条模型路线。** Claude 一条，OpenAI 兼容的接口要几条配几条（托管的 Kimi、GLM、Qwen、MiniMax 这类接口，和本机的 llama.cpp、vLLM）。每条路线写名字、地址、存密钥的环境变量名；密钥不进配置文件、日志、画面。
- **可选的模型从接口读。** Claude 读 Models 接口，OpenAI 兼容的读 `/v1/models`；管理员可以用允许列表限定。
- **每个会话自己选。** 在输入框旁选模型和思考档位；新会话从这个用户上次的选择开始，没选过的用管理员的默认。
- **思考档位按模型给。** 只列这个模型支持的档位，不支持的模型不显示档位。
- **中途换从下一次提问生效。** 历史里的思考块、新模型的窗口、工具、回放都要照顾到。

例子：

| 时间 | 发生了什么 | 结果 |
|---|---|---|
| 10:00 | Yi 打开新会话 | 输入框旁显示“Claude Sonnet 5.5 · 中”（他上次的选择） |
| 10:01 | 问一个问题，模型调了两次工具 | 用 Sonnet 5.5、`effort: medium` |
| 10:05 | 点开选择器，换成“本地 · qwen3.8”，档位“开” | 选择器写“下一次提问生效”；正在跑的运行不受影响 |
| 10:06 | 再问一个问题 | 先按 qwen 的 131072 窗口判断要不要压缩；请求里没有 Claude 的思考块；对话页那一轮上面有一行“换成 本地 · qwen3.8” |
| 10:10 | 换回 Sonnet 5.5，档位“高” | 下一次提问用 `effort: high`；之前 Sonnet 5.5 的思考块原样发回 |
| 10:30 | 重启服务器后打开这个会话 | 历史、每一轮用的模型和画面都照记录还原，不读模型列表 |
| 11:00 | 另一个用户新开会话 | 用他自己上次的选择，和 Yi 无关 |

### 调研

参照 pi-mono `main`（7f47a2e，2026-11-05）、DSH `main`（fcc0ad4621，2026-11-04），以及 Anthropic 官方文档（claude-api skill 2.1.314，文档缓存 2026-10-06，下面写作“Claude 文档”加文件名）。路径写法：pi 的 `AI` 指 `packages/ai`，`CA` 指 `packages/coding-agent`；DSH 路径相对于仓库根目录；两边都没有对应做法的标“自定”。

- **pi** 的模型来自构建时生成的目录，运行时从 pi.dev 刷新（`CA/src/core/remote-catalog-provider.ts:14-16`）；自己加的接口写在 `~/.pi/agent/models.json`，密钥可以写成 `$变量名`（`CA/src/core/model-config.ts:49-125,95-99`），自定义接口不读 `/v1/models`。档位是 `off、minimal、low、medium、high、xhigh、max`（`AI/src/providers/model-schema.ts:16-17`），按模型去掉不支持的、选了不支持的就就近换一档（`AI/src/models.ts:1274-1306`）。`/model` 选的只管本会话，Ctrl+S 才存成默认（`CA/src/modes/interactive/components/model-selector.ts:405-422`），新会话用存下的默认而不是上次用的（`CA/docs/models.md:28-35`）。换模型写进会话文件（`model_change`、`thinking_level_change`，`CA/src/core/session-manager.ts:69-78`），下一次请求就生效（`CA/src/core/agent-session.ts:825-850`）；别的模型的思考块整块丢掉（`AI/src/api/transform-messages.ts:122-132`）。
- **DSH** 按路线注册模型适配器，一个路线一个适配器（`.agents/notes/implemented/architecture/2026-07-14-provider-routed-llm-adapters.md:21-27`），每条路线写 `apiKeyEnv`（`packages/llm/llm-pi-ai/src/config.ts:104-208`、`README.md:40-75`）；没目录的路线读 `GET {baseURL}/models` 或 Anthropic 的 `/v1/models`（`packages/llm/llm-pi-ai/src/discovery.ts:1-46`）。档位由适配器按模型给出，没有档位的模型不显示“推理等级”一行（`packages/client/ui-model-selection/src/client/ModelSelect.tsx:138-149`），给了不支持的档位直接报错不就近换（`packages/llm/llm/src/index.ts:888-946`）。输入框旁一个下拉，显示“模型 · 档位”（`ModelSelect.tsx:395-407`）。选择按会话记（`model/selection` 事件，`packages/core/agent/src/model-selection.ts:112-115`），同时存成全局默认，新会话从上次的选择开始（`packages/api/session-controller/src/commands.ts:166-195`）。换模型从下一步生效（`packages/core/agent-loop/src/agent.ts:283-312`），告诉模型“上面的回答是另一个模型写的”（`model-selection.ts:129-174`），按新模型的窗口先压缩（`packages/compaction/compaction-basic/src/index.ts:181-203`），跨模型不回传签名（`packages/llm/llm-deepseek/src/replay.ts:33-38`）。
- **Claude 文档**：current 模型用 `thinking: {type: "adaptive"}`，深浅由 `output_config.effort` 控制，档位 `low`、`medium`、`high`、`xhigh`、`max`；Opus 5.5 不能关思考，Sonnet 5.5 关思考要用 `between_tools`（SKILL“Thinking & Effort”表）。Models 接口给 `max_input_tokens`、`max_tokens` 和 `capabilities`（`thinking.types.adaptive`、`effort.<档位>`、`image_input`，`shared/models.md:9-30`）。换模型时思考块原样发回，接收的模型读不了的由服务端丢掉、不计费，自己去掉反而可能 400（`shared/model-migration.md:1617`）；只换 `model` 不算改历史（`shared/preserved-thinking-migration/causes.md:9-11`），但 system 或工具重新渲染了算（`causes.md:40`）。缓存按模型分，换模型就没有缓存命中（`shared/prompt-caching.md:219,238`）。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| 一条 Claude 路线加任意条 OpenAI 兼容路线，管理员在配置文件里配 | 管理员在画面上配路线、填密钥（没有管理员界面，密钥只从环境变量读；DSH 的模型设置页 `packages/client/ui-settings-models` 以后再说） |
| 模型列表从接口读，允许列表限定，定时刷新 | Bedrock、Vertex 上的 Claude，Gemini 等别的协议（现在只有 Claude 和 OpenAI 兼容两种客户端） |
| 输入框旁选模型和思考档位，每个会话自己的选择 | 一次运行里途中换模型（运行里每一步都用开始时的模型，见 M8） |
| 新会话从用户上次的选择开始 | 子任务、摘要单独选模型（摘要用会话当时的模型，换窗口变小时用上一个，见 M10） |
| 中途换：思考块、窗口和压缩、工具、图片、回放 | Claude 每条消息单独设档位（`mid-conversation-output-config` 测试版，换档位不丢缓存；以后单独做） |
| 换模型告诉模型、对话页显示一行 | Claude 关掉思考（见 M5） |
| 配置迁移：`tantalum.openai.*` 改成路线 | 控制台多语言（下一个 spec；本 spec 的文字都放 `console/src/text/zh.ts`） |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 |
|---|---|---|
| M1 | 有哪些路线 | 一条 Claude 路线（名字固定 `claude`，沿用 `tantalum.claude.*`），加上 `tantalum.openai-routes.{名字}.*` 下任意条 OpenAI 兼容路线。每条写：显示名、地址（`base-url`）、存密钥的环境变量名（`api-key-env`，Claude 默认 `ANTHROPIC_API_KEY`，OpenAI 兼容的不写就不发认证头，给本机服务用）、默认模型、并发、超时、输出上限。名字只用小写字母、数字、`-`，最长 32。密钥只从这个环境变量读，不进配置文件、日志、journal、画面和接口返回。照 DSH 每条路线一个 `apiKeyEnv`（`packages/llm/llm-pi-ai/src/config.ts:104-208`）；pi 允许把密钥直接写进 `models.json`（`CA/src/core/model-config.ts:95-99`），我们不允许 |
| M2 | 模型列表从哪来 | Claude 读 `GET /v1/models`（翻完所有页）；OpenAI 兼容的读 `GET {base-url}/models` 的 `data[].id`。管理员写了允许列表（`models`）就只留列表里的。接口不提供列表的服务设 `list-models=false`，这时就用允许列表本身。启动时并行读，总共最多等 5 秒，之后每 10 分钟后台再读，失败时保留上次的结果（照 compaction-threshold T2 和 DSH `.agents/notes/implemented/architecture/2026-10-22-deepseek-official-model-listing.md:13-15`）。一次也没读到的路线在选择器里显示成一组灰色的“暂时连不上”，不能选（照 DSH `packages/client/ui-model-selection/src/client/index.ts:68-74`）。密钥变量没设的路线不出现，启动日志写明；默认路线的密钥变量没设时启动失败（列表读不到不算，照上面显示“暂时连不上”）。pi 自定义接口不读 `/v1/models`（`CA/src/core/provider-composer.ts` 里没有），DSH 读（`packages/llm/llm-pi-ai/src/discovery.ts:1-46`），照 DSH |
| M3 | Claude 哪些模型能选 | 只列 Models 接口说支持 `adaptive` 思考的（`capabilities.thinking.types.adaptive.supported`，Claude 文档 `shared/models.md:19`）。我们的请求总是发 `adaptive`（`model/ClaudeRequest.java:93-94`），不支持的老模型（如 Haiku 4.5 只收 `budget_tokens`）发出去就 400。允许列表里写了这种模型，启动日志写明“不支持 adaptive 思考，不提供”。自定 |
| M4 | 每个模型知道些什么 | 窗口（Claude `max_input_tokens`；OpenAI 兼容照 compaction-threshold T1 读 `meta.n_ctx`、`max_model_len`、`context_length`；都没有用路线配置的 `context-window`，写明“来自配置”）、输出上限（取模型报的和路线配置的较小者）、收不收图片（Claude 读 `capabilities.image_input`；OpenAI 兼容按路线的 `vision`）、思考档位（M5）。一次读列表顺带拿到窗口，不再单独读。照 DSH 由适配器给出模型信息（`packages/llm/llm/src/types.ts:387-465`）；pi 来自目录（`AI/src/types.ts:850-897`） |
| M5 | 思考档位 | **Claude：** 档位就是 `effort`，列 Models 接口里 `capabilities.effort.<档位>.supported` 为真的，按 `low`、`medium`、`high`、`xhigh`、`max` 排；默认取路线配置的 `effort`（现在是 `medium`），这个模型不支持就取它支持的最接近 `medium` 的一档。不提供“关”：Opus 5.5 关思考是 400，Sonnet 5.5 要换成 `between_tools` 而且只能在 `high` 以下（Claude 文档 SKILL“Thinking & Effort”表），文档建议用 `low` 代替关。**OpenAI 兼容：** 接口不报这个能力（pi 靠目录 `AI/catalog/reasoning.ts:11-23`，DSH 靠配置 `packages/llm/llm-pi-ai/README.md:107-111`，都不探测），由管理员按路线写 `thinking`：`none`（默认，不显示档位）、`reasoning-effort`（发 `reasoning_effort`，档位取 `efforts`，默认 `low,medium,high`）、`enable-thinking`（发 `enable_thinking: true/false`）、`chat-template`（发 `chat_template_kwargs.enable_thinking`）、`thinking-type`（发 `thinking: {type: enabled/disabled}`）；后三种的档位是“关”“开”。同一个接口里各模型不一样时，用 `overrides.{模型}` 单独写。写法照 pi 的 `thinkingFormat`（`AI/src/providers/compat-schema.ts:257`、`AI/src/api/openai-completions.ts:872-967`）。**没有档位的模型不显示档位**（照 DSH `ModelSelect.tsx:138-149`、pi `CA/src/modes/interactive/components/footer.ts:233-250`）。服务器只收这个模型列出来的档位，别的直接拒绝，不就近换（照 DSH `packages/llm/llm/src/index.ts:888-946`；pi 就近换，`AI/src/models.ts:1274-1306`）。画面上换模型时，新模型也有当前档位就留着，没有就换成新模型的默认档位（照 DSH `packages/client/ui-model-selection/src/client/index.ts:85-101`） |
| M6 | 按会话还是按用户 | 按会话。选择是“路线 + 模型 + 档位”三样。输入框旁的选择器显示和修改这个会话的选择；改了先留在画面上，随下一次开始新运行的提交发给服务器（`prompt`，以及排队后开始新运行的 `follow-up`）。照 DSH 按会话记（`packages/core/agent/src/model-selection.ts:112-115`）和 pi 的会话文件（`CA/src/core/session-manager.ts:69-78`） |
| M7 | 新会话用什么 | 用这个用户上次在选择器里选的（存在 `users.db` 的 `user_settings`，键 `model_choice`，和压缩点同一张表，不进 journal）；没选过，或者上次选的现在不可用，就用管理员的默认（`tantalum.provider` 指的路线和它的默认模型、默认档位）。每个用户一份，只改自己的。照 DSH“上次用的就是默认”（`packages/api/session-controller/src/commands.ts:166-195`），但 DSH 是整个程序一份，我们按用户。pi 新会话用存下的默认、不用上次的（`CA/docs/models.md:28-35`），不照它：Yi 要“像 dsh 一样” |
| M8 | 什么时候生效 | 从下一次提问开始，也就是下一个运行。正在跑的运行一直用它开始时的模型和档位，包括这个运行里后面的工具轮和重试。插话（`steer`）并进当前运行，不换模型；选择器保留新的选择，等下一次提问。选择器旁写“下一次提问生效”。DSH 和 pi 都是下一次模型请求就换（DSH `agent-loop/src/agent.ts:283-312`，pi `CA/src/core/agent-session.ts:825-850`），会在一个运行中途换；我们按运行换，一个运行只有一个模型，压缩、摘要和用量都按运行算清楚，自定 |
| M9 | 历史里的思考块 | **发给 Claude：** 历史里所有 Claude 模型的思考块都原样发回，不管是哪个 Claude 模型写的；读不了的由 Claude 服务端丢掉，不计费，换回原来的模型时还能读到（Claude 文档 `shared/model-migration.md:1617`：自己去掉可能引起 400）。这和 pi（`AI/src/api/transform-messages.ts:122-132`）、DSH（`packages/llm/llm-deepseek/src/replay.ts:33-38`）跨模型就丢不一样，按 Claude 文档做。**发给 OpenAI 兼容：** `reasoning_content` 只发回同一条路线、同一个模型写的（照 pi 的“同一模型”判断 `transform-messages.ts:122-125`）。**跨协议：** 照旧不发（`ClaudeRequest.java:367-378`、`OpenAiClient.java:318`）。为此每个推理块记下是哪条路线、哪个模型写的。旧会话里没记的：Claude 的照样发回（本来就不看是哪个 Claude 模型），OpenAI 兼容的不再发回（判断不了是不是同一个模型；只影响升级前用本地模型的旧会话，自定） |
| M10 | 窗口和压缩 | 每次提问按这次用的模型的窗口和用户的压缩点重算压缩设置（compaction-threshold T6 已经这样做，现在窗口按模型取）。换到窗口更小的模型、历史超过新的压缩点时，在第一次调新模型之前先压缩；这次摘要用上一个模型写（它装得下现在的历史），照 DSH（`packages/compaction/compaction-basic/src/index.ts:181-203`）。摘要失败时这次运行失败，写明“换到 X 之前压缩失败，请重试或换窗口更大的模型”，会话的模型留在上一个（照 DSH 同一处 `MODEL_SWITCH_COMPACTION_FAILED`）。Claude 开着备用模型时，窗口照 compaction-threshold T3 取这个模型和它的备用模型中最小的 |
| M11 | 工具和历史的样子 | 换模型不改会话的工具全集，工具开关照 core-tools C14 走。system 和工具定义的文字不随模型变，换模型前后发出去的一样，思考签名才继续有效（Claude 文档 `causes.md:40`）。Claude 的会话中途工具变化要测试版，不是每个模型都支持（`ClaudeRequest.java:60-66`，如 Sonnet 5 不支持）；不支持的模型看到的历史和支持的不一样，换回来时签名会失效。所以**会话里已经有工具变化**（中途开关过工具，或者一开始就有关着的工具）时，不支持的 Claude 模型在选择器里灰掉，写明“这个会话中途改过工具，这个模型不支持”；没有工具变化的会话照常能选。DSH 每个模型声明 `toolUpdate`（`packages/llm/llm-deepseek/src/models.ts:4-23`），自定 |
| M12 | 告诉模型、告诉用户 | 路线或模型变了（只换档位不算），在这次提问前面追加一条说明给模型：“[Model changed: earlier assistant turns were written by {旧}; you are now {新}.]”，只追加、不改已有内容。对话页在那一轮上面显示一行“换成 {显示名} · {档位}”。照 DSH 的提示（`packages/core/agent/src/model-selection.ts:129-174`，只换档位不提示）；pi 不告诉模型 |
| M13 | 记进 journal、回放 | 提交里带的选择记在 `UserPrompt` 里（“要用什么”）；每次提问组装上下文时把实际用的选择记进 `ContextAssembled`（“配上了什么”，README“配上了什么，只在变化时记”），只在变化时写；调模型的命令带着它。回放和恢复只读这些记录，不读模型列表、不调接口，模型下线了旧会话照样打开。旧会话没有这一项：画面上显示这个用户的默认；继续提问时第一次记下的选择不算“换模型”，不追加 M12 的说明（升级前用的是哪个模型只在每次调用的 `RequestOptions` 里，只供展示，内核不读）。照 pi（`model_change` 进会话文件，`CA/src/core/session-manager.ts:1228-1253`）和 DSH（`model/selection` 事件，`packages/core/agent/src/types.ts:117-121`） |
| M14 | 选的模型不可用 | 提交时检查：路线不在、模型不在列表、档位不是这个模型的，返回 400 并写明原因，不接受这次提交（照 DSH `packages/api/session-controller/src/commands.ts:415-420`“先选一个可用的模型”）。提交里没带选择（直接调接口的客户端）时，用会话上一次的选择；那个也不可用了，就用这个用户的默认，并照 M12 记成一次换模型 |
| M15 | 图片 | 收不收图片按这次选的模型（M4）。提交带图片而选的模型不收时返回 400“当前选的模型不支持图片”（现在按整个服务器判断，`web/ApiController.java:174-176`）；历史里的图片发给不收图片的模型时换成一行文字（现有做法，`OpenAiClient.java:321-333`）。照 DSH（`commands.ts:349-360`）和 pi（`AI/src/api/transform-messages.ts:62-84`） |
| M16 | 并发和缓存 | 每条路线自己的并发上限（`concurrency`），一条路线忙不影响别的。换模型、换档位都会让下一次请求没有缓存命中（缓存按模型分；Claude 的 `effort` 改了也一样），README 写明。自定 |
| M17 | 配置迁移 | `tantalum.provider` 改成“默认路线的名字”（`claude` 或某条 OpenAI 兼容路线）；`tantalum.claude.model`、`tantalum.claude.effort` 改成 Claude 路线的默认模型和默认档位；`tantalum.openai.*` 搬到 `tantalum.openai-routes.{名字}.*`。配置里还有 `tantalum.openai.*` 时启动失败，写明搬到哪里，不悄悄忽略。自定 |
| M18 | 画面文字 | 选择器、提示、错误的中文都放 `console/src/text/zh.ts`；服务器给画面的错误消息照现有做法写中文。多语言是下一个 spec |

## Requirements

### Requirement 1：管理员配置路线

**User Story:** 作为管理员，我想配几条模型路线，这样用户能在 Claude、托管的 OpenAI 兼容模型和本机模型之间选。

出处：DSH `packages/llm/llm-pi-ai/src/config.ts:104-208,250-258`；pi `CA/src/core/model-config.ts:49-125`。

#### Acceptance Criteria

1. WHEN 配置里有 `tantalum.claude.*` 和若干 `tantalum.openai-routes.{名字}.*` THEN 服务器 SHALL 为每条路线建一个客户端，每条路线用自己的地址、密钥变量、超时和并发上限（M1、M16）。
2. WHEN 一条路线的 `api-key-env` 指的环境变量没设或为空 THEN 这条路线 SHALL NOT 出现在模型列表里，启动日志 SHALL 写明路线名和变量名，SHALL NOT 写变量的值（M2）。
3. WHEN 默认路线（`tantalum.provider`）的密钥变量没设 THEN 启动 SHALL 失败并写明原因；只是列表读不到时 SHALL 照常启动（M2）。
4. WHEN 配置里还有 `tantalum.openai.*` THEN 启动 SHALL 失败，消息写明改成 `tantalum.openai-routes.{名字}.*`（M17）。
5. 路线名不合规（M1）或重复 SHALL 启动失败。
6. 密钥 SHALL NOT 出现在日志、journal、接口返回和画面上；路线的地址 SHALL NOT 出现在给普通用户的接口返回里。

### Requirement 2：模型列表和模型信息

**User Story:** 作为用户，我想看到服务器上真正能用的模型，以及每个模型的窗口和档位，这样不会选到用不了的。

出处：DSH `packages/llm/llm-pi-ai/src/discovery.ts:1-46`、`packages/llm/llm-deepseek/src/listing.ts:172-199`；Claude 文档 `shared/models.md:9-30`。

#### Acceptance Criteria

1. WHEN 服务器启动 THEN 它 SHALL 并行读每条路线的模型列表，总共最多等 5 秒；之后 SHALL 每 10 分钟后台再读（M2）。
2. WHEN 管理员写了允许列表 THEN 列表里 SHALL 只有允许列表和接口都有的模型；`list-models=false` 时 SHALL 用允许列表本身（M2）。
3. WHEN Claude 模型不支持 `adaptive` 思考 THEN 它 SHALL NOT 出现在列表里（M3）。
4. WHEN 一次读取失败 THEN 服务器 SHALL 保留上次读到的列表；一次也没读到的路线 SHALL 在列表里标成“暂时连不上”，里面没有可选的模型（M2）。
5. 每个模型 SHALL 带窗口和来源、输出上限、收不收图片、档位列表和默认档位（M4、M5）。
6. Claude 模型的档位 SHALL 等于 Models 接口里支持的 `effort` 档位，SHALL NOT 有“关”（M5）。
7. OpenAI 兼容模型的档位 SHALL 按路线或 `overrides` 的 `thinking` 设置给出；`thinking=none` 时 SHALL 没有档位（M5）。
8. 用户取列表 SHALL 从服务器内存里拿，SHALL NOT 每次去调模型接口。

### Requirement 3：输入框旁选模型和档位

**User Story:** 作为用户，我想在输入框旁直接换模型和思考档位，就像 DSH 那样。

出处：DSH `packages/client/ui-model-selection/src/client/ModelSelect.tsx:115,138-149,395-407,486-503`、`index.ts:85-101,124-166`；pi `CA/src/modes/interactive/components/footer.ts:233-250`。

#### Acceptance Criteria

1. 输入框旁 SHALL 显示这个会话的选择，写成“{模型显示名} · {档位}”；没有档位的模型只写模型名（M5、M6）。
2. WHEN 用户点开选择器 THEN 它 SHALL 按路线分组列出模型；模型多于 8 个时 SHALL 有搜索框；“暂时连不上”的路线 SHALL 显示成灰色的一组（M2）。
3. WHEN 选中的模型有档位 THEN 选择器 SHALL 列出这个模型的档位；没有档位 SHALL NOT 显示档位那一栏（M5）。
4. WHEN 用户换了模型 THEN 新模型也有当前档位就留着，没有就换成新模型的默认档位（M5）。
5. WHEN 当前会话有工具变化 THEN 不支持会话中途工具变化的 Claude 模型 SHALL 灰掉并写明原因（M11）。
6. 运行进行中选择器 SHALL 照样能改，旁边 SHALL 写“下一次提问生效”（M8）。
7. 选择器、提示、错误的文字 SHALL 全部来自 `console/src/text/zh.ts`（M18）。

### Requirement 4：新会话从上次的选择开始

**User Story:** 作为用户，我想新开的会话用我上次选的模型，这样不用每次都重新选。

出处：DSH `packages/api/session-controller/src/commands.ts:166-195`、`packages/core/agent-default-model/src/index.ts:82-94`。

#### Acceptance Criteria

1. WHEN 用户在选择器里选了模型和档位 THEN 服务器 SHALL 把它存成这个用户的“上次的选择”（M7）。
2. WHEN 用户新开会话 THEN 选择器 SHALL 显示他上次的选择；没选过 SHALL 显示管理员的默认（M7）。
3. WHEN 上次的选择现在不可用（路线没了、模型不在列表、档位不支持） THEN 新会话 SHALL 用管理员的默认（M7）。
4. 一个用户的选择 SHALL NOT 影响别的用户（M7）。

### Requirement 5：会话中途换模型

**User Story:** 作为用户，我想在同一个会话里换模型接着聊，而且不会因为换模型出错。

出处：DSH `packages/core/agent-loop/src/agent.ts:283-312`、`packages/compaction/compaction-basic/src/index.ts:181-203`、`packages/core/agent/src/model-selection.ts:129-174`；pi `CA/src/core/agent-session.ts:825-850,3179-3213`。

#### Acceptance Criteria

1. WHEN 用户换了选择后提问 THEN 这次运行的每一次模型调用 SHALL 用新的路线、模型和档位（M8）。
2. WHEN 运行进行中换了选择 THEN 这个运行剩下的调用 SHALL 仍用它开始时的选择；插话 SHALL NOT 换模型（M8）。
3. WHEN 路线或模型变了 THEN 这次提问前面 SHALL 有 M12 那句说明，已发出的内容 SHALL NOT 改动（M12）。
4. WHEN 新模型的压缩点小于当前历史的估算 THEN 系统 SHALL 在第一次调新模型前先压缩，摘要 SHALL 用上一个模型写（M10）。
5. WHEN 这次压缩失败 THEN 运行 SHALL 失败并写明 M10 那句话，会话的模型 SHALL 留在上一个（M10）。
6. WHEN 提交里的选择不可用 THEN 提交 SHALL 被拒绝（400），消息写明是路线、模型还是档位的问题（M14）。
7. WHEN 提交带图片而选的模型不收图片 THEN 提交 SHALL 被拒绝（400）（M15）。
8. 每条路线 SHALL 有自己的并发上限（M16）。

### Requirement 6：思考块和历史

**User Story:** 作为用户，我想换来换去之后模型还能用上之前的推理，而且请求不会因为历史里别的模型的内容被拒。

出处：Claude 文档 `shared/model-migration.md:1617`、`shared/preserved-thinking-migration/causes.md:9-11,40`；pi `AI/src/api/transform-messages.ts:122-137`；DSH `packages/llm/llm-pi-ai/README.md:196`。

#### Acceptance Criteria

1. WHEN 发给 Claude THEN 历史里所有 Claude 模型写的思考块 SHALL 原样发回（M9）。
2. WHEN 发给 OpenAI 兼容路线 THEN `reasoning_content` SHALL 只发回同一条路线、同一个模型写的（M9）。
3. 发给一种协议的请求里 SHALL NOT 有另一种协议的推理内容（M9）。
4. 换模型前后 system 和工具定义 SHALL 逐字节相同（M11）。
5. WHEN 用 Claude 严格校验（`prefix_mismatch_behavior: error`）在一个会话里换 Claude 模型、换到 OpenAI 兼容路线再换回来 THEN 请求 SHALL NOT 因为历史被拒（M9、M11）。

### Requirement 7：记录和回放

**User Story:** 作为 Yi，我想从 journal 里看出每一轮用的是哪个模型，回放时结果不变，模型下线了旧会话也能打开。

出处：README“配上了什么，只在变化时记”（`README.md:119`）；pi `CA/src/core/session-manager.ts:1228-1253`；DSH `packages/core/agent/src/types.ts:117-121`。

#### Acceptance Criteria

1. 提交里的选择 SHALL 记在 `UserPrompt` 里；每次提问实际用的选择 SHALL 记进 `ContextAssembled`，和上一次一样时 SHALL NOT 重复写（M13）。
2. WHEN 回放或重启后打开会话 THEN 系统 SHALL 只用 journal 里的记录还原每次调用用的模型，SHALL NOT 读模型列表、调模型接口；回放出的 prompt SHALL 和实时发出的逐个相同（M13）。
3. WHEN 一个模型或路线已经下线 THEN 用过它的会话 SHALL 照样能打开、看轨迹（M13）。
4. WHEN 打开本 spec 之前的会话 THEN 它的历史和画面 SHALL 不变；选择器 SHALL 显示这个用户的默认；继续提问时第一次记下的选择 SHALL NOT 追加 M12 的说明（M13）。
5. journal 里 SHALL NOT 有密钥和路线地址。

### Requirement 8：设置页和对话页

**User Story:** 作为用户，我想知道每个模型的窗口多大、会话里什么时候换过模型。

出处：DSH `packages/core/agent-loop/src/agent.ts:709-723`（换模型时记窗口）；compaction-threshold T8。

#### Acceptance Criteria

1. 设置页“模型”一栏 SHALL 按路线列出能选的模型，每个写窗口和来源（“模型报告”或“来自配置”）、收不收图片；不再只显示一个模型（M4）。
2. 压缩点的选项 SHALL 照旧，对应的 token 数按当前会话选的模型算（M10）。
3. 状态栏的占比 SHALL 按当前会话的模型的窗口算（M10）。
4. 对话页 SHALL 在换了模型的那一轮上面显示“换成 {显示名} · {档位}”（M12）。

### Requirement 9：迁移、文档和 8091

**User Story:** 作为 Yi，我想照着文档在 8091 上配好 Claude 和本地模型，在同一个会话里换着用。

#### Acceptance Criteria

1. `application.properties` SHALL 改成新的写法（M17），默认只开 Claude 路线，本地路线写成注释示例。
2. README SHALL 写明：路线怎么配、密钥变量、允许列表、各种 `thinking` 写法对应哪类服务、换模型的影响（缓存、窗口、思考块）、M3 和 M11 不提供或灰掉的模型。
3. 8091 SHALL 同时开 Claude 和本地 qwen 两条路线。

## Non-Functional Requirements

### Performance

- `GET /api/models` 从内存返回，200 个模型以内 ≤ 50 ms（服务器处理时间）。
- 提问时不为选模型多调任何上游接口；换模型不增加提交的处理时间（提交接口 P95 变化 ≤ 5 ms）。
- 启动时读模型列表总共最多等 5 秒，几条路线并行。
- 选择器打开到能点 ≤ 100 ms（200 个模型）。

### Security

- 密钥只从环境变量读，不进配置文件、日志、journal、接口返回和画面。
- 给普通用户的接口只返回路线名、显示名和模型信息，不返回地址。
- 每个用户只能读、改自己的“上次的选择”。

### Reliability

- 一条路线连不上、列表读失败，只影响这条路线；别的路线照常能选、能用。
- 模型列表刷新失败保留上次的结果。
- 换模型失败（压缩失败、模型报错）只让这次运行失败，会话可以继续。

### Usability

- 选择器写明“下一次提问生效”；灰掉的模型写明原因；错误消息写明换哪一样。
