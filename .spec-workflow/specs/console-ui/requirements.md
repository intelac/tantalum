# Requirements Document

## Introduction

`console-ui` 把现在的参考控制台换成 DeepSeek Harness（下称 DSH）Web 界面的外观和交互。

Yi 选择方案 A：**照搬外观和交互，用 React 重新实现，后端是 Spring Boot。** 不搬 DSH 的前端工程，也不实现它的 RPC 协议和 cordis 插件树。

依据：

- DSH 仓库 `apps/web`、`packages/client/*`（MIT 许可证）
- `core-skeleton` 的需求 10、11，以及已实现的接口

### 范围

只搬 Tantalum 后端能支撑的界面。

| 在范围内 | 不在范围内（原因） |
|---|---|
| 两栏框架：会话侧栏、主区（“对话”和“轨迹”标签页） | 右侧面板、工作区和文件树、文件预览、终端、浏览器面板（后端没有） |
| 对话区：空状态、消息流、Markdown、推理内容、工具行 | 计划、目标、定时任务、技能（后端没有） |
| 输入栏、排队提示、停止按钮 | 子 agent 界面（等 `durable-semantics`） |
| 浅色和深色主题 | 审批界面（等 `plugin-model`） |
| 登录页、退出登录 | 注册、找回密码、SSO、用户管理界面、角色权限 |
| 设置弹窗：外观、模型（只读） | 插件市场、DeepSeek 账号、充值、语音、翻译、桌面端 |
| 每次模型调用的回放（放在“轨迹”标签页） | 多语言（本 spec 只有简体中文，文案集中存放，以后好加） |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| U1 | 复用到什么程度 | 自己写组件。尺寸、圆角、断点、配色这类设计值可以对照 DSH；如果直接复制了它的文件（如图标、CSS 片段），就保留出处，并在 `THIRD_PARTY_NOTICES.md` 附 MIT 声明 | 方案 A 不搬工程；MIT 要求保留版权声明 |
| U2 | 品牌 | 用 Tantalum 的名称和自己的标志，不用 DeepSeek 的标志、名称、鲸鱼和小鱼图案 | MIT 不授予商标权；DSH 也说明其他身份的部署应替换品牌 |
| U3 | `steer` 的入口 | 界面上不提供，跟 DSH 一致：运行中的提交一律排队。接口保留 `steer` | DSH 已明确去掉 Web 的 `steer` 入口，只保留后端能力。**这改变了 `core-skeleton` 需求 11 第 1 条** |
| U4 | journal 回放放在哪里（Yi 已确认） | 放进“轨迹”标签页，形式参照 DSH 的轨迹页。右侧面板这次不做 | 需求 11 第 2、3 条仍要满足；右侧面板的内容我们都没有 |
| U5 | 身份（Yi 决定：多用户需要登录） | 增加登录页和真实认证：用户名加密码，服务端会话放在 `HttpOnly` Cookie。principal 只来自登录会话，服务端不再信任 `X-Principal` 请求头 | 请求头可以伪造，多用户隔离不能建立在它上面。**这改变了 `core-skeleton` 需求 10 第 7 条** |
| U7 | 工作目录（Yi 已确认） | 默认每个用户一个子目录，可配置为共享 | 只隔离界面不隔离文件，不算多用户；`Tool` 接口趁还没对外发布时改 |
| U6 | 用户从哪里来 | 不开放注册。第一个管理员由环境变量创建，其他用户由命令行创建；用户数据单独存表，不进 journal，密码只存哈希 | 底座面向内部部署，开放注册会扩大攻击面；账号数据不是运行事件 |

## Alignment with Product Vision

这是参考控制台，用来演示和排查运行，不是最终产品界面。所以这里只做到“看起来和用起来都像 DSH”，不追求功能对等。

## Requirements

### Requirement 1：应用框架

**User Story:** 作为用户，我希望界面布局和 DSH 一样，以便直接上手。

#### Acceptance Criteria

1. WHEN 打开控制台 THEN 界面 SHALL 显示左侧栏和主区；主区顶部有会话标题和“对话”“轨迹”两个标签页。
2. WHEN 拖动左侧栏边界 THEN 宽度 SHALL 限制在 264–420 px，默认 280 px，并在刷新后保留。
3. WHEN 视口窄于 1024 px THEN 左侧栏 SHALL 自动收起为 56 px 的图标栏；WHEN 窄于 768 px THEN 它 SHALL 变为覆盖内容的抽屉。
4. WHEN 在 390 px 宽度下使用 THEN 页面 SHALL 没有横向滚动，所有操作都能完成。

### Requirement 2：设计规范

**User Story:** 作为用户，我希望配色、字体和间距都接近 DSH，并能切换深浅色。

#### Acceptance Criteria

1. WHEN 编写样式 THEN 颜色、圆角、字体和动效 SHALL 只来自一组 CSS 变量；圆角为 4、8、12、16、20、28 px，动效为 0.1、0.2、0.3 秒。
2. WHEN 用户选择浅色、深色或跟随系统 THEN 全部界面 SHALL 随之切换，选择在刷新后保留，且加载时没有明显闪烁。
3. WHEN 用户开启减少动效的系统设置 THEN 过渡动画 SHALL 关闭。
4. WHEN 使用键盘操作 THEN 所有控件 SHALL 能聚焦，焦点可见；弹窗 SHALL 能用 Esc 关闭并把焦点还给触发它的控件。

### Requirement 3：会话侧栏

**User Story:** 作为用户，我希望在侧栏新建和切换会话。

#### Acceptance Criteria

1. WHEN 打开控制台 THEN 侧栏 SHALL 列出当前用户的会话，最近活动的在前，标题取第一条提问的开头。
2. WHEN 点击“新会话” THEN 对话区 SHALL 回到空状态；第一次提交后才真正创建会话。
3. WHEN 切换会话 THEN 地址栏 SHALL 更新，刷新后 SHALL 回到同一会话。
4. WHEN 某会话有运行在进行 THEN 它的行 SHALL 显示运行中状态。
5. WHEN 重启后有“待恢复”的运行 THEN 它的行 SHALL 显示这个状态。

### Requirement 4：对话区

**User Story:** 作为用户，我希望像 DSH 一样阅读模型的回复和工具过程。

#### Acceptance Criteria

1. WHEN 会话没有消息 THEN 对话区 SHALL 显示居中的空状态和输入栏。
2. WHEN 显示用户消息 THEN 它 SHALL 是右对齐的气泡；模型回复 SHALL 是左对齐的无气泡正文。
3. WHEN 显示模型文本 THEN 系统 SHALL 渲染 Markdown（标题、列表、表格、代码块），代码块有语言标签和复制按钮；SHALL NOT 执行或渲染其中的原始 HTML。
4. WHEN 模型一轮回复有过程内容 THEN 思考和工具调用 SHALL 按实际发生的顺序放在同一个过程区里，包括每次调用工具前后的思考，不只显示最后一段。
   - 过程区的标题显示状态和耗时（如“已完成，用时 32 秒”）。运行中展开并实时更新；结束后折叠，可再次展开。
   - 模型没有返回思考内容时（如 Claude 的 `adaptive` 模式跳过思考），只显示工具行，不显示空的思考块。
5. WHEN 一轮回复里模型连续多次调用工具 THEN 过程区 SHALL 显示完整的工具链，不合并、不省略：
   - 每一步都有自己的工具行，一轮中的所有模型调用和工具调用都按发生顺序排列，能看出哪段思考引出了哪次调用。
   - 同批并行的调用显示为一组，独占调用单独一行；还没发出的调用显示为等待中。
   - 每一步显示状态和耗时；耗时由 journal 的事件时间算出。
   - 被钩子拦截、参数无效的调用同样显示，并说明原因。
   - 运行中新的一步实时出现；刷新或重启后，工具链 SHALL 能从 journal 完整还原。
6. WHEN 显示单个工具行 THEN 它 SHALL 包含名称、参数摘要、状态；展开后显示完整参数和结果。错误结果和合成错误（如 `TOOL_OUTCOME_UNKNOWN`）SHALL 有明显标记。
7. WHEN 流式输出进行中 THEN 文本 SHALL 逐步出现，结算后以 journal 内容替换，画面不跳动。
8. WHEN 用户停留在底部 THEN 新内容 SHALL 自动滚动；WHEN 用户向上翻看 THEN SHALL 停止自动滚动，并显示回到底部的按钮。
9. WHEN 回复被取消 THEN 已显示的部分 SHALL 标记为已中断。WHEN 运行失败 THEN 对话区 SHALL 显示原因（如上下文超长、拒答、限流）。
10. WHEN 会话很长（如 500 条消息）THEN 流式更新时 SHALL 只重绘正在变化的部分，保持可用。

### Requirement 5：输入栏

**User Story:** 作为用户，我希望提问、排队和停止都顺手。

#### Acceptance Criteria

1. WHEN 按 Enter THEN 系统 SHALL 提交；WHEN 按 Shift+Enter THEN 换行；输入法组字时按 Enter SHALL NOT 提交。
2. WHEN 输入多行 THEN 输入栏 SHALL 随内容变高，到上限后内部滚动。
3. WHEN 运行进行中 THEN 提交 SHALL 进入排队（`follow-up`），排队内容 SHALL 显示在输入栏上方。
4. WHEN 运行进行中 THEN 发送按钮 SHALL 旁边有停止按钮；点击后 SHALL 产生 `Cancel`。
5. WHEN 提交被拒绝（429、503）THEN 输入内容 SHALL 保留，并显示原因；重试 SHALL 使用同一个 `requestId`。
6. WHEN 切换会话 THEN 未发送的草稿 SHALL 按会话保留。

### Requirement 6：轨迹标签页

**User Story:** 作为底座开发者，我希望保留查看每次调用的能力，以便排查问题。

#### Acceptance Criteria

1. WHEN 打开轨迹页 THEN 它 SHALL 显示当前运行的时间条和事件列表，并标出每一段的 Handler。
2. WHEN 选中一次模型调用 THEN 它 SHALL 显示由 journal 回放得出的完整 system prompt、工具列表、消息和回复。
3. WHEN 回复带用量 THEN 轨迹页 SHALL 显示 token 用量和实际模型。

### Requirement 7：设置弹窗

**User Story:** 作为用户，我希望能调外观，并知道当前用的是哪个模型。

#### Acceptance Criteria

1. WHEN 打开设置 THEN 它 SHALL 是左侧导航加右侧内容的弹窗，布局和 DSH 的设置页一致。
2. WHEN 进入“外观” THEN 用户 SHALL 能选择主题。
3. WHEN 进入“模型” THEN 界面 SHALL 只读显示供应商、模型名和是否外发到云端。SHALL NOT 提供输入 API Key 的地方，Key 只从环境变量读取。

### Requirement 8：连接状态

**User Story:** 作为用户，我希望断线时知道发生了什么，恢复后内容不丢。

#### Acceptance Criteria

1. WHEN SSE 断开 THEN 界面 SHALL 显示断线提示，并按 `core-skeleton` 的规则自动重连和补读。
2. WHEN 重连成功 THEN 提示 SHALL 消失，对话内容 SHALL 与 journal 一致。
3. WHEN 服务返回 503 THEN 界面 SHALL 说明服务已停止处理事件。

### Requirement 9：登录

**User Story:** 作为多用户部署中的用户，我希望用自己的账号登录，只看到自己的会话。

#### Acceptance Criteria

1. WHEN 未登录访问控制台 THEN 界面 SHALL 显示登录页：Tantalum 标志、用户名、密码、登录按钮，支持深浅色和手机宽度，风格与其余界面一致。
2. WHEN 登录成功 THEN 系统 SHALL 回到用户原来要去的页面；WHEN 失败 THEN SHALL 只提示“用户名或密码错误”，不透露是哪一项错。
3. WHEN 未登录请求 `/api/*` THEN 系统 SHALL 返回 401，包括 SSE。WHEN 会话过期 THEN 界面 SHALL 回到登录页，并保留输入栏草稿。
4. WHEN 请求带 `X-Principal` 头 THEN 系统 SHALL 忽略它，principal 只取自登录会话。
5. WHEN 用户点击退出登录 THEN 服务端会话 SHALL 立即失效，该用户已打开的 SSE 连接 SHALL 断开。
6. WHEN 存储密码 THEN 系统 SHALL 只存加盐的慢哈希（Argon2id 或 bcrypt），SHALL NOT 写入日志、journal 或响应。
7. WHEN 同一账号或来源连续登录失败 THEN 系统 SHALL 限速；成功登录后 SHALL 更换会话 ID。
8. WHEN 写操作到达 THEN 系统 SHALL 防 CSRF：Cookie 为 `HttpOnly`、`SameSite=Strict`，非 `127.0.0.1` 部署时为 `Secure`，并校验 `Origin`。
9. WHEN 首次启动且没有用户 THEN 系统 SHALL 用环境变量 `TANTALUM_ADMIN_USER`、`TANTALUM_ADMIN_PASSWORD` 创建第一个用户；都未设置时启动失败并说明怎么设置。SHALL NOT 内置默认密码。
10. WHEN 运行命令行工具 THEN 管理员 SHALL 能添加用户、重置密码和停用用户；停用后其会话立即失效。
11. WHEN 多用户测试 THEN 测试 SHALL 用两个真实账号验证：看不到对方的会话、运行、回放和 SSE，直接访问对方的 ID 返回 404。

### Requirement 10：后端补充（Spring Boot）

**User Story:** 作为前端，我需要会话列表和消息历史，现在的接口只能按运行查询。

#### Acceptance Criteria

1. WHEN 请求 `GET /api/sessions` THEN 系统 SHALL 返回当前用户的会话：ID、标题、最近活动时间、运行状态。
2. WHEN 请求 `GET /api/sessions/{id}/messages` THEN 系统 SHALL 返回消息视图和排队内容，内容块类型与 journal 一致，并包含工具链每一步的状态、分组和起止时间；不属于该用户时返回 404。
3. WHEN 请求 `GET /api/config` THEN 系统 SHALL 返回供应商、模型名和是否云端，SHALL NOT 包含 API Key。
4. WHEN 消息视图变化 THEN 系统 SHALL 通过 SSE 发出事实，前端不用轮询。新事实只是回放时重新算出的通知，不写 journal。
5. WHEN 实现这些接口 THEN `tantalum-kernel` 的转移语义 SHALL 不变；状态里需要新字段时，只能由已有事件投影得出。已写入的 journal SHALL 仍能回放。

### Requirement 11：工作目录隔离

**User Story:** 作为多用户部署中的用户，我希望模型只能访问我自己的文件。

#### Acceptance Criteria

1. WHEN 工具或 `ContextHandler` 访问文件 THEN 系统 SHALL 使用该运行所属用户的目录 `<workspace>/<用户名>`，目录不存在时创建，权限 700。
2. WHEN 用户 A 的运行访问用户 B 的目录（包括 `..`、绝对路径和符号链接）THEN 系统 SHALL 拒绝，模型收到错误结果。
3. WHEN 工具执行 THEN 它 SHALL 从调用上下文得到 principal；插件工具不用自己从参数里取身份。
4. WHEN 创建用户 THEN 用户名 SHALL 只允许小写字母、数字、`-`、`_`，否则拒绝，这样用户名可以安全地用作目录名。
5. WHEN 配置 `tantalum.workspace-mode=shared` THEN 全部用户 SHALL 共用 `<workspace>`，启动日志 SHALL 提示文件不隔离。默认是 `per-user`。
6. WHEN 运行两账号隔离测试 THEN 测试 SHALL 验证互相读不到对方的文件和 `AGENTS.md`。

## Non-Functional Requirements

### Code Architecture and Modularity
- 技术栈不变：React 19、TypeScript、Vite、TanStack Query、Tailwind；不引入 cordis、Electron 或 DSH 的包。
- `console/src` 按界面区域分目录，组件名用领域词。业务状态不散落在组件里，流式更新不引起整棵树重绘。
- Markdown 等新依赖要写明理由，版本固定。

### Security
- 模型和工具内容一律按不可信内容处理：不用 `dangerouslySetInnerHTML` 渲染未净化内容，链接只允许 `http`、`https`，并带 `rel="noopener noreferrer"`。
- 前端不接触 API Key，也不把会话凭据放进 `localStorage`。
- 认证用 Spring Security 实现，不自己写密码学代码。
- 有了真实认证也仍默认只绑定 `127.0.0.1`；对外暴露时要求 HTTPS，由部署方的反向代理或 TLS 配置提供。

### Performance
- 打包后的 JS gzip 后不超过 300 KB（现在约 83 KB），超过时说明原因。

### Testing
- 单元测试覆盖消息投影、输入栏状态和 Markdown 安全。
- Playwright 端到端测试用脚本化模型跑通：登录、提问、工具行、排队、停止、切换会话、断线重连、退出登录。
- 每个主要界面在 1280 px 和 390 px、浅色和深色下各截一张图，和 DSH 对照后发给 Yi 确认。

### Licensing
- 复制自 DSH 的文件保留版权声明，并记录在 `THIRD_PARTY_NOTICES.md`。
