# Requirements Document

## Introduction

目前的“轨迹”页只把模型调用看全了。工具调用、插件工作、插件钩子都看不清，DSH 轨迹页的大部分功能我们也没有。本 spec 让轨迹页和 DSH 功能对等，另外加上 DSH 没有、但我们的插件需要的“钩子决定”。

参照的是 DSH 2026-11-03 的 `main`（ce7073d5）。下文的“DSH”指：
- 轨迹页包 `packages/client/ui-trajectory`：说明 `README.zh.md`，文案 `src/client/locales.ts`，组件 `src/client/*.tsx`。
- 统计页签包 `packages/experimental/client-ui-stats`（`README.zh.md`）。

每条需求标出 DSH 的出处；DSH 没有对应的标“自定”。

### 范围

DSH 有的都做，只有下面两样例外。它们依赖我们还没有的底层功能，现在没有可显示的数据。这两样会记进 BACKLOG，底层功能做出来时，轨迹跟着一起做：

| 不做 | DSH 出处 | 缺的底层功能 |
|---|---|---|
| 嵌套子工具：子调用的折叠和摘要 | `kind.subtool`、`toolbar.subCalls` | 让模型写程序、在程序里调用工具的 `run_code` 类工具（依赖以后的 sandbox spec） |
| `run_code` 的源码视图和语法高亮 | README“检查记录”第 2 段，`code.*` | 同上 |

图片和附件（缩略图、灯箱、文件数量，DSH README“检查记录”第 4、5 段，`attachment.*`、`layout.imageCount`）要做，但依赖 `attachments` spec（提问时上传图片和文件）。轨迹页里显示附件的需求写在 `attachments` spec 里，在它之后做。

DSH 的工具原始结果只存在活跃会话的内存里，重启或冷读后就没有了（README“理解实现”）。这个限制我们不照搬：所有内容都从 journal 回放，重启后照样能看到。

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 |
|---|---|---|
| T1 | 钩子的决定从哪里来 | 内核运行钩子时，只要结果不是“什么都不做”，就报一个新的事实 `HookDecided`，内容是钩子名、位置、结果、理由或补丁摘要。“不是什么都不做”指拦截、要求工作、带补丁的放行。事实不写 journal，回放时重新算出，和现在的 `HookReported` 一样。自定 |
| T2 | 只放行、没加内容的钩子要不要显示 | 不显示。每一步每个钩子都报一次，会淹没真正的决定 |
| T3 | 首 token 延迟、吞吐量、请求选项从哪里来 | 模型的结果事件（`ModelReplied`、`ModelFailed`、`ModelInterrupted`）新增两个可空字段：首 token 到达的时刻，以及实际发出的请求选项（模型、最大 token、思考和 effort 设置等）。这是 journal 格式的扩展：旧事件没有这两个字段，显示“未记录”，同 DSH `timing.notRecorded`、`options.notRecorded`。内核不读这两个字段，所以发给模型的 prompt 和回放结果不变，内核语义版本不升 |
| T4 | 页面形式 | 照 DSH 做：整个会话一张按轮次分组的记录表，上方是时间概览，选中一条记录后在右侧打开检查器。替换掉现在“一次看一个运行”的页面。旧链接 `/s/<会话>/trace/<运行>` 仍然可用，打开后定位到那个运行 |
| T5 | 进行中的记录怎么画 | 照 DSH：只画开始标记，标“进行中”，不画随时间变长的条（README“已知限制”） |
| T6 | 统计页签 | 做。它在 DSH 里是实验包，但 DSH 有的我们都做 |
| T7 | 怎么做 | 分四个阶段，每个阶段结束跑全量回归、提交一次、向 Yi 汇报：① 数据（`HookDecided`、T3 的两个字段、轨迹接口）；② 记录表和检查器；③ 时间概览；④ 统计页签 |

### 名词

- **轮**：我们的一个运行，对应 DSH 的 turn。
- **步**：轮里的一次模型调用，加上它引出的工具调用和插件工作，对应 DSH 的 step。
- **请求**：发给模型的一次调用，在会话里连续编号，即 DSH `request.label` 的“请求 #n”。

## Requirements

### Requirement 1：会话记录表

**User Story:** 作为底座开发者，我希望按轮次看到一个会话里发生的所有事，以便从整体上理解这个会话，不用一个运行一个运行地翻。

出处：DSH README“概述”“使用本包”，`TrajectoryTable.tsx`，`kind.*`、`group.*`、`turn.label`、`section.betweenTurns`、`history.*`。

#### Acceptance Criteria

1. WHEN 打开轨迹页 THEN 页面 SHALL 显示这个会话的记录表，按“轮 → 步”分组。每轮有一行标题“第 n 轮”和摘要（几个步骤、几个工具调用）。
2. WHEN 一轮里发生了事 THEN 记录表 SHALL 按 journal 顺序每件事一行。记录类型有：
   - 系统：system prompt 和工具列表
   - 用户：提问、排队提交、插话
   - 上下文：变化的段
   - 已压缩
   - 助手：模型回复
   - 工具
   - 插件工作
   - 钩子决定（自定）
3. WHEN 一步开始 THEN 这一步的用户消息、插话、上下文输入和提示词变化 SHALL 排在请求分隔行“请求 #n”和助手回复之前（同 DSH README“理解实现”第 2 段）。
4. WHEN 手动压缩单独占一个运行 THEN 它 SHALL 显示在两轮之间的“轮次之间”区段里。运行内的自动压缩留在所属的轮里。
5. WHEN 会话很长 THEN 记录表 SHALL 先定位到末尾，滚到顶部时按页加载更早的轮次，加载中显示加载行；只渲染可见的行。
6. WHEN 会话有新事件 THEN 记录表 SHALL 跟随末尾；用户向上滚动后停止跟随，新记录不打断查看。
7. WHEN 用工具栏 THEN 用户 SHALL 能一键展开或收起所有轮次、所有调用。收起时保留一行摘要，点击或按 Enter、空格可以展开。
8. WHEN 在工具栏搜索 THEN 搜索 SHALL 覆盖记录的文字、工具名、参数和结果，包括收起的内容；匹配的记录在记录表和时间概览上都标出来。
9. WHEN 面板变窄 THEN 工具栏的按钮和搜索框 SHALL 自动换行。
10. WHEN 从对话页或别处跳到某个工具调用或运行 THEN 记录表 SHALL 展开并定位到它；旧链接 `/s/<会话>/trace/<运行>` 也一样（T4）。

### Requirement 2：模型请求检查器

**User Story:** 作为底座开发者，我希望选中一次模型请求就看到它的全部输入、输出和计时，以便排查回复内容和速度问题。

出处：DSH `REQUEST_TABS`（概述、选项、用量、计时），`SYSTEM_PROMPT_TABS`，`details.*`、`timing.*`、`usage.*`，README“检查记录”第 6、7 段和“时间概览”第 1 段。

#### Acceptance Criteria

1. WHEN 选中一次请求或它的助手回复 THEN 检查器 SHALL 有这些页签：
   - 概述：状态、用途（正常调用或压缩摘要）、提供方、实际模型、停止原因、工具调用数
   - 系统提示词
   - 工具
   - 消息
   - 选项：实际发出的请求选项 JSON
   - 用量：本次请求和会话累计的输入、缓存读取、缓存写入、输出、推理 token
   - 计时：开始时刻、总时长、首 token 延迟、生成时长、吞吐量、计时来源
2. WHEN 回复里有思考内容 THEN 检查器 SHALL 用紧凑的 Markdown 显示思考，和回复正文分开。
3. WHEN 一次请求失败后重试 THEN 检查器 SHALL 显示第几次尝试、最多几次、每次失败的原因、等待时间和上次尝试的错误。重试成功后这些历史仍可查看，但不改变请求现在的状态。
4. WHEN 请求还在等待、流式输出或重试 THEN 它 SHALL 显示为等待中。被中断的请求标为失败，已收到的部分仍可查看。
5. WHEN 事件没有首 token 时刻或请求选项（T3 之前的旧事件） THEN 对应位置 SHALL 显示“未记录”，不推算。
6. WHEN 查看时刻 THEN 用户 SHALL 能在本地时间和 Unix 时间戳之间切换。
7. WHEN 请求因认证等原因失败 THEN 概述 SHALL 显示可读的失败原因，例如“API 密钥无效”，不显示密钥内容。

### Requirement 3：工具检查器

**User Story:** 作为底座开发者，我希望选中一个工具调用就看到它的参数、结果和计时，以便排查工具问题。

出处：DSH `tab.summary`、`tab.payload`、`tab.result`、`tab.schema`、`tab.timing`，`record.*`、`copy.*`，README“理解实现”第 4、5、6 段。

#### Acceptance Criteria

1. WHEN 选中一个工具调用 THEN 检查器 SHALL 有这些页签：
   - 概述：工具名、状态、结果预览
   - 参数：原始 JSON 文本，保留原来的空白
   - 结果
   - Schema：这次请求里这个工具的输入 Schema
   - 计时：开始、结束、耗时
2. WHEN 参数或结果是完整的 JSON 对象或数组 THEN 它们 SHALL 以树形展示，长字符串最多预览 12 行、可以展开。每个节点能复制值、复制 JSON、复制路径；整体能复制格式化的或紧凑的 JSON。
3. WHEN 查看参数 THEN 用户 SHALL 能在树形和原始 JSON 之间切换，也能切换自动换行。
4. WHEN 工具出错 THEN 记录表的预览和检查器 SHALL 原样显示记录下来的结果文本，只有空白的文本也保留。没有文本时显示错误代码。
5. WHEN 工具被钩子拦下、因取消没有发出，或因重启而结果不明 THEN 状态和结果 SHALL 显示是哪种情况和原因。
6. WHEN 一次回复里有多个工具调用 THEN 记录表 SHALL 标出它们是并行还是依次执行，和对话页的分组一致。
7. WHEN 工具来自 MCP 服务器（工具名是 `mcp__<服务器>__<工具>`） THEN 概述 SHALL 分开显示服务器名和工具名。
8. WHEN 工具还在执行 THEN 结果页 SHALL 显示“运行中”。

### Requirement 4：系统提示词、工具和上下文的变化

**User Story:** 作为底座开发者，我希望看到 prompt 在哪一步、因为什么变了，以便理解模型为什么表现不同。

出处：DSH `SYSTEM_UPDATE_TABS`（差异、系统提示词、工具），`layout.initialSystemPrompt`、`layout.systemPromptUpdated`、`layout.toolAdded`、`layout.toolsChanged`，README“已知限制”第 1 条和“理解实现”第 3 段。

#### Acceptance Criteria

1. WHEN 会话第一次组装上下文 THEN 记录表 SHALL 显示一行“初始系统提示词”，可以查看系统提示词和工具。
2. WHEN 两次请求之间 system prompt 变了 THEN 记录表 SHALL 显示“系统提示词已更新”。检查器的“差异”页签按行显示变化，另有完整的系统提示词和工具页签。
3. WHEN 只有一个工具增加或移除 THEN 那一行 SHALL 直接显示工具名。有多个变化时显示新增和移除的数量，展开后按类型列出工具名。
4. WHEN 上下文的某几段变了（例如运行中途记忆变了） THEN 记录表 SHALL 显示“上下文更新”和变化的段名，点开可以看变化前后的内容。
5. WHEN 查看某次请求 THEN 检查器 SHALL 显示这次请求实际生效的系统提示词。压缩后恢复的系统提示词也照样显示，不需要新的系统事件。
6. WHEN 一部分 system 内容或工具是插件钩子加的 THEN 检查器 SHALL 标出是哪个钩子加的（见需求 5）。

### Requirement 5：插件

**User Story:** 作为插件作者，我希望看到我的插件在哪一步做了什么，以便调试插件。

出处：DSH 用 `source.plugin`、`source.pluginNamed`、`tab.source`、`details.source` 标出消息来自哪个插件。插件工作和钩子决定没有对应，自定（T1、T2）。

#### Acceptance Criteria

1. WHEN 一条消息、一段 system 内容或一个工具来自插件 THEN 记录 SHALL 标出来源“插件 · 名字”，检查器有“来源”页签。没有来源的显示“未记录来源”。
2. WHEN 运行里有插件工作 THEN 记录表 SHALL 每轮工作显示一行。检查器显示：哪个钩子在哪个位置要求的、工作类型、输入、结果、是否失败、给模型的续写文字（有的话），以及工作完成后运行是继续还是结束。
3. WHEN 一个钩子拦下了某件事 THEN 记录表 SHALL 显示一行钩子决定：钩子名、位置（模型调用前、工具调用前、失败时、结束前、工作结果后、压缩前）和理由。
4. WHEN 一个钩子给 prompt 加了内容或工具 THEN 钩子决定 SHALL 显示加了多少字的 system 内容、加了哪些工具。
5. WHEN 一个钩子要求了一轮工作 THEN 钩子决定 SHALL 和对应的插件工作行互相链接。
6. WHEN 钩子抛了异常 THEN 记录表 SHALL 显示现有 `HookReported` 的内容。
7. WHEN 钩子只是放行、没有补丁 THEN 记录表 SHALL 不显示它（T2）。
8. WHEN 重启后再打开同一个会话 THEN 钩子决定 SHALL 和当时一模一样，因为它们是回放算出来的。

### Requirement 6：时间概览

**User Story:** 作为底座开发者，我希望在时间轴上一眼看出时间花在哪里，并能从时间轴直接找到记录。

出处：DSH README“时间概览”，`TrajectoryTimeline.tsx`（`onRecordSelect`、`onRecordFocus`、`onRangeChange`、`timelineTooltipLabel`），`toolbar.duration`、`toolbar.useActualDuration`、`toolbar.useEqualWidth`、`timeline.*`。

#### Acceptance Criteria

1. WHEN 打开轨迹页 THEN 记录表上方 SHALL 固定一块时间概览，从左到右按真实开始时间和耗时画出每条记录。助手的条区分首 token 延迟和生成时间。
2. WHEN 鼠标在一段上停留 500 ms THEN 提示 SHALL 显示类型、名称、开始到结束的时刻（精确到毫秒）和总时长；模型调用还显示首 token 延迟和生成时长。
3. WHEN 点一段 THEN 它 SHALL 被选中：检查器打开它的详情，记录表定位到它。点空白处时，记录表滚到最近的记录，选中不变。
4. WHEN 在记录表选中一条 THEN 时间概览上对应的段 SHALL 高亮。
5. WHEN 在时间概览上拖选一个区间 THEN 记录表 SHALL 只显示在这个闭区间内任一时刻处于活动状态的记录。右键单击清除区间。
6. WHEN 用滚轮 THEN 时间域 SHALL 缩放；放大后按住右键拖动可以平移。
7. WHEN 用工具栏的时长开关 THEN 用户 SHALL 能在“按实际时长”和“每个操作等宽”两种画法之间切换。
8. WHEN 记录还在进行 THEN 时间概览 SHALL 只画开始标记，不画耗时（T5）。初始视图和流式更新都停在末尾，向上滚动后停止跟随。
9. WHEN 并行的工具在时间上重叠 THEN 每个工具 SHALL 各画一段，能分别点到；段太窄时保留最小的可点宽度。
10. WHEN 用键盘 THEN 每一段 SHALL 能用 Tab 聚焦、Enter 选中，标签和记录表一致。
11. WHEN 没有计时数据 THEN 时间概览 SHALL 显示“无计时数据”。

### Requirement 7：检查器的通用行为

**User Story:** 作为用户，我希望检查器好用：能调宽度、能复制。

出处：DSH `details.resize`、`details.resizeTitle`、`details.close`，`copy.*`，`record.wrapLines`。

#### Acceptance Criteria

1. WHEN 拖动检查器边缘 THEN 宽度 SHALL 跟着改变；双击恢复默认；用键盘每次调 16 px。宽度下次打开时保留。
2. WHEN 点关闭或按 Esc THEN 检查器 SHALL 关闭，记录表里的选中取消。
3. WHEN 查看任何长文本（参数、结果、系统提示词、消息） THEN 用户 SHALL 能复制原文，也能切换自动换行。

### Requirement 8：统计页签

**User Story:** 作为底座开发者，我希望按轮次比较提示词长度、缓存复用和时间去向，以便找到慢和贵的地方。

出处：DSH `packages/experimental/client-ui-stats/README.zh.md`“概述”。

#### Acceptance Criteria

1. WHEN 打开会话 THEN “对话”“轨迹”旁边 SHALL 有一个“统计”页签。
2. WHEN 打开统计 THEN 每轮 SHALL 占一行，有一列“触发”，说明这一轮由什么触发：用户提问、排队提交、手动压缩、插件工作。
3. WHEN 展开一轮 THEN 每步 SHALL 列出上下文 token、输出 token、缓存命中率、首 token 延迟、模型用时、工具用时、输出速度和总用时条。
4. WHEN 会话很长 THEN 统计 SHALL 和轨迹共用同一套分页，滚到顶部加载更早的轮次。轨迹正在加载同一页时，统计保留加载按钮，不误报“历史已加载完”。
5. WHEN 某项数据没有记录（T3 之前的旧事件） THEN 该格 SHALL 显示“未记录”。

### Requirement 9：一致和不变

**User Story:** 作为用户，我希望轨迹页看到的就是 journal 里发生过的事，而且查看轨迹不影响运行本身。

#### Acceptance Criteria

1. WHEN 运行进行中 THEN 新记录 SHALL 随推送出现，不用刷新。
2. WHEN 对比同一个会话在内存里时和重启后从文件载入的轨迹 THEN 两者 SHALL 逐字节相同（加进 `WireFormatTest`）。
3. WHEN 加了 `HookDecided` 和 T3 的两个字段 THEN 发给模型的请求、回放出的 prompt 和对话页 SHALL 都不变。
4. WHEN 别的用户请求这个会话的轨迹 THEN 服务 SHALL 返回 404，和现在一样。
5. WHEN 在 390 px 宽的手机上打开 THEN 记录表、检查器和时间概览 SHALL 都能用：检查器改为覆盖在记录表上方，页面不出现横向滚动。

## Non-Functional Requirements

### 性能
- 以 ops-hardening 压测数据里 1890 个事件的长会话为准：轨迹首屏不超过 1 秒打开，滚动不掉帧（只渲染可见行）。
- 推送不因为钩子事实变多而明显变慢，用现有的并发压测确认。

### 外观
- 布局、间距、配色和字号照 DSH 的轨迹页，沿用 console-ui 已经对照过的设计值。每个阶段在 1280 px 和 390 px、深色和浅色下各截一张图，和 DSH 对照后发给 Yi。

### 测试
- **内核：** 每种钩子结果、每个位置都报出正确的 `HookDecided`；放行且没有补丁时不报；回放得到同样的事实。
- **模型客户端：** Claude 和 OpenAI 兼容两个客户端都记下首 token 时刻和请求选项，记录里不含 API key。
- **接口：** 轨迹 JSON 加进 `WireFormatTest`。
- **前端：** vitest 覆盖记录表、检查器、时间概览和统计。Playwright 跑一个带插件钩子、并行工具、重试和压缩的会话，检查每类记录从记录表和时间概览都能打开。
- **真实模型：** T3 改到了模型客户端，所以各跑一次真实 Claude 测试，确认请求不变、首 token 时刻和选项记录正确，并报告用量。
