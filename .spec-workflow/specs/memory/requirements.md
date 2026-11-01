# Requirements Document

## Introduction

`memory` 给 Tantalum 加上跨会话的记忆：用户在一个会话里说过的偏好、项目事实、给过的反馈，在下一个会话的运行里模型还知道。它是第一个“用户能直接感知”的能力，排在 `context-and-ops`、MCP、沙箱、子 agent 之前（Yi 决定）。

依据：

- 和 Yi 讨论定下的四层模型（见下）
- Claude Code 的 auto-memory 形式（索引文件 + 每条一个带 frontmatter 的文件），这是本仓库的开发过程里一直在用的形式，已验证够用
- pi 没有内置记忆层，靠 `AGENTS.md` 这类上下文文件；DeepSeek Harness 的 memory 是默认关闭的第三方 MCP 工具，没有内置服务。两者都是单用户本地安装，隔离不是它们的问题，却是我们的核心要求
- `core-skeleton` 的运行开始快照（`ContextAssembled`）、每用户工作目录和 `Workspace.resolve`、无状态 `Tool`

### 四层模型

| 层 | 范围 | 寿命 | 谁写 | 怎么进 prompt | 存在哪 | 本 spec |
|---|---|---|---|---|---|---|
| 1 工作记忆 | 一次运行 | 运行结束即止 | 内核 | 就是消息历史 | journal + 状态 | 已有 |
| 2 情景记忆 | 一个会话 | 会话存续期 | 压缩工作 | 摘要替换早期历史 | journal 追加事件 | 不在范围，属 `context-and-ops` |
| 3 个人长期记忆 | 一个用户、跨会话 | 用户删掉为止 | 模型 + 用户手工 | 索引常驻 system，条目按需读 | 每用户目录 `memory/` | **本 spec 主体** |
| 4 共享知识 | 一个部署（或一个项目） | 管理员维护 | 管理员 | 常驻 system | 配置指定的只读目录 + `AGENTS.md` | **本 spec，只读接入** |

层与层之间不自动流动：会话摘要里的事实要由模型显式写入第三层；个人记忆提升为共享知识由管理员动手。自动提升是记忆污染的主要来源。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| 每用户的记忆目录、索引和条目文件 | 向量检索、嵌入（几十到几百条靠索引和按需读就够；多用户下还要守“检索不跨用户”的边界，等真有需要再开） |
| 运行开始时把索引和共享知识读进 system | 上下文压缩、会话摘要（`context-and-ops`） |
| 模型用的记忆工具：写、读、删 | 自动周期性整理、自动提升（见决定 M5） |
| 每用户的条目数和字节上限 | 跨用户共享个人记忆、团队记忆的写入界面（第四层只读，由管理员改文件） |
| 设置弹窗里的“记忆”面板：看、删、手工新增 | 记忆的版本历史、恢复已删条目 |
| 凭据过滤：拒绝把看起来像密钥、口令的内容写进记忆 | 记忆加密（目录权限 700 已有，和工作目录同级） |
| 真实模型的跨会话场景测试 | 子 agent 的记忆（等子 agent spec） |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| M1 | 分几层 | 四层（上表）。本 spec 做第三层全部和第四层的只读接入 | 多用户部署逼出第四层：项目事实不该每个人各教一遍，也不该让某个人的模型写进个人记忆后别人看不到。第二层写入者是压缩机制，判断标准和第三层不同，分开 |
| M2 | 存成什么 | 每用户目录下 `memory/MEMORY.md`（索引，每条一行）+ `memory/<slug>.md`（条目，frontmatter 带名称、一句话描述、类型），不建数据库表 | 用户和工具都能直接看、改；路径校验、目录权限、每用户隔离都是现成的；格式和 Claude Code 一致，本仓库自己就在用 |
| M3 | 谁能写 | 模型通过工具写；用户在界面上看、删、手工新增。管理员维护第四层文件 | 模型写是记忆的来源；用户必须能删掉模型记错的东西，这是信任的底线 |
| M4 | 怎么进 prompt | 索引全文和第四层内容在运行开始时读进 system（进 `ContextAssembled` 事件）；条目正文不常驻，模型用 `memory_read` 按需读 | 索引几十行，system 稳定不破坏 prompt caching；条目正文可能很长，全放进去浪费且会稀释注意力。运行开始快照保证回放确定、运行内不变 |
| M5 | 整理（压缩）怎么做 | 硬上限：每人条目数、索引字节数、单条字节数；超限时写入被拒并提示模型先合并或删除。模型可用同名写入覆盖、可删除。**不做**自动周期性整理 | 自动整理让模型定时重写全部记忆，是污染和费用的来源，多用户下还要按人跑。先让上限逼出“少而准”的记忆，用上一段时间再看是否需要 |
| M6 | 第四层放哪 | 配置项 `tantalum.knowledge-dir` 指向一个只读目录，里面的 Markdown 全部读进 system（有总字节上限）；工作目录里的 `AGENTS.md` 保留现有行为 | 每人模式下用户目录彼此独立，共享知识需要一个部署级的位置；只读保证一个用户的模型影响不到别人 |
| M7 | 新记忆何时生效 | 下一个运行开始时 | 运行内 system 是快照，改了会破坏回放确定性和缓存；一次运行里模型刚写的东西它自己在历史里已经看得到 |
| M8 | 记什么、不记什么 | system 里给模型固定的记忆指引：四种类型（用户、反馈、项目、引用），只记跨会话仍有用的事实，不记一次性任务细节；工具拒绝写入看起来像凭据的内容 | 没有指引模型要么什么都不记，要么什么都记；凭据进记忆等于进 system 提示，等于进每次请求和 journal |
| M9 | 工具粒度 | 三个工具：`memory_write(name, type, description, content)`（新建或同名覆盖）、`memory_read(name)`、`memory_delete(name)`。不做搜索工具 | 索引常驻就是搜索：模型看索引决定读哪条。工具越少越不容易用错 |

## Alignment with Product Vision

Tantalum 是自托管的多用户 agent 运行时。记忆让它从“每次从零开始的工具”变成“认识使用者的助手”，同时每一处都守住多用户的边界：个人记忆只属于本人，共享知识只读，配额按人。记忆内容进事件、进 journal，所以“模型当时为什么这么做”在回放和轨迹里仍然可答。

## Requirements

### Requirement 1：记忆的存储与隔离

**User Story:** 作为用户，我希望我的记忆只属于我，存在我能看到、能自己改的地方。

#### Acceptance Criteria

1. WHEN 用户第一次写记忆 THEN 系统 SHALL 在该用户的工作目录下创建 `memory/`，索引为 `memory/MEMORY.md`，每条记忆为 `memory/<slug>.md`，目录权限与工作目录相同。
2. WHEN 写入条目 THEN 文件 SHALL 以 frontmatter 保存名称（kebab-case slug）、一句话描述和类型（`user`、`feedback`、`project`、`reference` 之一），正文为 Markdown；索引 SHALL 同步增删对应的一行。
3. WHEN 任何记忆操作给出的名称 THEN 系统 SHALL 只接受 `[a-z0-9-]{1,64}`，拒绝其他字符；路径 SHALL 经过 `Workspace.resolve`，不可能落到 `memory/` 之外或别的用户目录。
4. WHEN 共享工作目录模式（`workspace-mode=shared`）THEN 记忆 SHALL 仍按用户分开：目录为 `<root>/memory/<用户名>/`。
5. WHEN 写入条目 THEN 系统 SHALL 先写临时文件再重命名，并发写同一用户的两个运行 SHALL 不会留下半截文件或损坏的索引。
6. WHEN 索引文件被人手工改坏（如引用了不存在的条目）THEN 运行开始 SHALL 仍能继续，索引按能解析的行读入，坏行记日志。

### Requirement 2：记忆进入上下文

**User Story:** 作为用户，我希望新会话里模型已经知道我之前让它记住的事，不用我再说一遍。

#### Acceptance Criteria

1. WHEN 运行开始组装上下文 THEN `ContextAssembled` 事件的 system SHALL 依次包含：基础提示、记忆指引、第四层共享知识、`AGENTS.md`、该用户的记忆索引全文；顺序固定。
2. WHEN 记忆索引为空 THEN system SHALL 不包含空的记忆段，只包含指引。
3. WHEN 同一个运行里模型写了新记忆 THEN 该运行后续的模型调用 SHALL 仍使用开始时的快照，新记忆在下一个运行生效。
4. WHEN 回放某个运行 THEN 内核 SHALL 不读任何记忆文件，system 来自事件；回放得到的 prompt 与实时的逐字节相同。
5. WHEN 一个用户的运行组装上下文 THEN system 里 SHALL 只有这个用户的索引，任何路径都不能读到别人的。
6. WHEN 索引进入 system THEN 它 SHALL 位于 system 的末尾、在钩子追加内容之前，使 prompt caching 的 system 断点在整个运行里稳定。

### Requirement 3：记忆工具

**User Story:** 作为模型，我需要在该记的时候记、在需要时读、在记错时删。

#### Acceptance Criteria

1. WHEN 工具目录注册 THEN 系统 SHALL 提供 `memory_write`、`memory_read`、`memory_delete` 三个内置工具，schema 声明必填参数，服务端为 `memory`，可并发（不同用户互不影响；同一用户并发写由 Requirement 1.5 保证正确）。
2. WHEN 调用 `memory_write` 且名称已存在 THEN 系统 SHALL 覆盖该条目并更新索引行，返回“已更新”；不存在则新建并返回“已记住”。
3. WHEN 调用 `memory_write` 的类型不在四种之内、描述为空或超长、正文超过单条上限 THEN 工具 SHALL 返回错误结果并说明限制，不写入。
4. WHEN 调用 `memory_read` 的条目不存在 THEN 工具 SHALL 返回错误结果；存在则返回 frontmatter 和正文。
5. WHEN 调用 `memory_delete` THEN 系统 SHALL 删除条目文件并移除索引行；删除不存在的条目返回错误结果。
6. WHEN 工具返回的任何文本 THEN SHALL 不包含服务器上的绝对路径。
7. WHEN 工具被 `ReadOnlyHook` 这类只读策略评估 THEN 记忆工具 SHALL 不被当作对工作目录的写操作拦下（记忆目录是它自己的）；策略钩子若要禁止记忆写入，按名称拦 `memory_write`、`memory_delete` 即可。

### Requirement 4：上限与整理

**User Story:** 作为部署者，我不希望任何一个用户的记忆把 system 提示撑爆或把磁盘写满。

#### Acceptance Criteria

1. WHEN 配置 THEN 系统 SHALL 提供每人条目数上限、索引总字节上限、单条正文字节上限，有默认值（建议 200 条、16 KB、8 KB），非正数启动失败。
2. WHEN `memory_write` 新建条目会超过条目数或索引字节上限 THEN 工具 SHALL 拒绝并返回“记忆已满，请先用 memory_delete 删除或用同名 memory_write 合并”，不写入。
3. WHEN 覆盖已有条目 THEN 条目数不变，只检查索引字节和单条字节上限。
4. WHEN 第四层共享知识的总字节超过配置上限 THEN 系统 SHALL 按文件名顺序截断并在日志里警告，运行不失败。
5. 系统 SHALL NOT 自动重写、合并或淘汰记忆条目（决定 M5）。

### Requirement 5：共享知识层

**User Story:** 作为管理员，我希望把团队都该知道的项目事实放在一处，所有用户的模型都看到，但谁的模型都改不了。

#### Acceptance Criteria

1. WHEN 配置了 `tantalum.knowledge-dir` THEN 运行开始 SHALL 读取该目录下的 `*.md`（不递归，按文件名排序）进 system 的共享知识段；未配置则没有这一段。
2. WHEN 目录不存在或不可读 THEN 启动 SHALL 记录警告并继续，运行不失败。
3. WHEN 模型调用任何记忆工具试图写入共享知识 THEN SHALL 不可能：工具只认用户自己的 `memory/`，共享目录不在任何可写路径上。
4. WHEN `AGENTS.md` 存在 THEN 现有行为 SHALL 保留，位置在共享知识段之后、记忆索引之前。

### Requirement 6：记忆界面

**User Story:** 作为用户，我希望看到模型替我记了什么，能删掉记错的，也能自己加一条。

#### Acceptance Criteria

1. WHEN 打开设置弹窗 THEN SHALL 有“记忆”面板，列出当前用户的全部条目：名称、类型、描述、更新时间，按更新时间倒序；并显示“已用 n/N 条”。
2. WHEN 点击一条 THEN 面板 SHALL 显示正文（Markdown 渲染，与对话区同一组件）。
3. WHEN 点击删除并确认 THEN 条目 SHALL 被删除，列表更新；确认用现有的对话框组件，键盘可操作。
4. WHEN 手工新增 THEN 用户填写名称、类型、描述、正文；校验规则和上限与工具相同，错误原样显示。
5. WHEN 接口被调用 THEN `/api/memory`、`/api/memory/{name}` SHALL 只操作当前登录用户的记忆；访问别人的名字和不存在的名字一样返回 404；写接口受现有的 `Origin` 校验。
6. WHEN 面板打开时另一个运行写了新记忆 THEN 列表 SHALL 在面板下次打开或手动刷新时反映，不要求实时推送。

### Requirement 7：记忆指引与凭据过滤

**User Story:** 作为用户，我希望模型记的是真正有用的事，而且绝不把密码、密钥记下来。

#### Acceptance Criteria

1. WHEN 组装 system THEN 记忆指引 SHALL 说明：四种类型各记什么；只记跨会话仍有用的事实（偏好、长期约束、项目事实、用户纠正过的做法、外部链接）；不记一次性任务的中间结果；写之前先看索引，有相近条目就用同名覆盖；用户明确让记的要记，让忘的要删。
2. WHEN `memory_write` 的内容匹配凭据特征（如 `sk-ant-`、`AKIA`、`password=`、`Bearer ` 后跟长串、PEM 头）THEN 工具 SHALL 拒绝并说明“记忆不能保存凭据”，不写入，不把内容回显到结果里。
3. WHEN 用户在界面手工新增 THEN 同一过滤 SHALL 生效。
4. WHEN 轨迹页显示一次模型调用 THEN 它的 prompt 里 SHALL 能看到那次运行用了哪一版索引（现有功能自然满足，作为验收点列出）。

### Requirement 8：测试

**User Story:** 作为维护者，我需要知道记忆在真实模型上真的被用起来了，而不只是文件写对了。

#### Acceptance Criteria

1. WHEN 跑单元测试 THEN 记忆存储、索引解析、名称校验、上限、凭据过滤、并发写、工具、接口、`ContextHandler` 的 system 组装、回放不读文件 SHALL 各有测试；覆盖率要求与现有模块相同。
2. WHEN 跑真实模型场景（门控与 `ClaudeScenarioTest` 相同）THEN SHALL 有一个跨会话场景：会话 A 里用户说“以后回答一律用 Java 25 语法并记住”，断言 `memory_write` 被调用且文件存在；会话 B 新运行的 `ContextAssembled.system` 含该索引行，用户问一个 Java 问题，断言回答用了 Java 25 的写法。判定标准和结论写进 tech.md。
3. WHEN 跑真实模型场景 THEN SHALL 有一个“让它忘记”场景：用户说“忘掉关于 Java 版本的记忆”，断言 `memory_delete` 被调用、文件消失、下一个运行的 system 不再含它。
4. WHEN 跑多用户测试 THEN 两个用户各写记忆后，各自运行的 system SHALL 只含自己的索引；一个用户的 `memory_read` 不可能读到另一个用户的条目名。
5. WHEN 跑 e2e THEN 设置弹窗的记忆面板 SHALL 能列出、查看、删除一条由脚本化模型写入的记忆。

## Non-Functional Requirements

### Code Architecture and Modularity
- 记忆的存储和校验放在 shell 的一个新包（如 `tantalum.shell.memory`），工具、接口、`ContextHandler` 都只通过它访问文件；内核不知道记忆的存在，value 不加新事件类型。
- 文件格式解析与写入是纯函数，便于测试；I/O 集中在一个类。
- 三个工具复用 `ToolArguments`，错误信息风格与 `read_file`、`grep` 一致。

### Performance
- 运行开始多读一个索引文件和共享目录，典型几 KB，不引入新的锁；共享知识可以在启动时读一次并在文件变化时按修改时间重读，不要求热更新语义。
- 界面列表接口返回索引即可，不读全部正文。

### Security
- 所有路径经 `Workspace.resolve`；名称白名单；工具结果和接口响应不含服务器路径；凭据过滤；接口按登录用户；写接口受 `Origin` 校验。
- 记忆内容进 system 等于进每次模型请求和 journal，指引里明确告知模型不要记敏感信息。

### Reliability
- 写入原子（临时文件 + 重命名）；索引损坏不阻塞运行；共享目录缺失不阻塞启动。
- 运行内快照：记忆文件在运行中途被改不影响该运行，也不影响回放。

### Usability
- 面板文案与现有设置弹窗一致（简体中文，集中在 `zh.ts`）；删除需确认；错误原样显示。
