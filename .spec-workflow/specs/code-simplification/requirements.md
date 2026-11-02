# Requirements Document

## Introduction

2026-11-03 对整个代码做了一次只看“简单、清晰、易懂”的审查（不找缺陷）。结论：骨架是对的，补丁感集中在后来加功能的地方，那里是在原结构旁边加一层，而不是把结构改成新的样子。本 spec 把这些地方改成一种做法、一个位置、一个名字。不加新能力，对用户可见的行为不变，审查中顺带发现的 4 个小问题一起修。

这是重构，不涉及 agent 功能，没有 pi、DSH 的出处，下文“自定”不再逐条标注。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| `tantalum-value`、`tantalum-kernel`、`tantalum-shell` 主代码，`console/src` | `tantalum-examples` 和测试代码只随主代码改动调整，不单独重构 |
| 审查中发现的 4 个问题（需求 1） | BACKLOG 里的已知缺口和待 Yi 决定的事项（快照、回收检查） |
| README、tech.md、设计文档里和代码不一致的说法 | 新能力、性能优化 |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 |
|---|---|---|
| C1 | 怎么做 | 分 6 个阶段：清理、包结构、管线、外围、内核、前端。每个阶段结束跑全量回归（`mvn verify`、跳号回归、vitest、Playwright），提交一次，向 Yi 汇报 |
| C2 | 上下文变化的两套机制 | 只留“只发变化的部分”。`ContextAssembled` 和 `SessionContext` 去掉 `dynamic`，`sections` 不再可空，删掉整块重发和旧事件用的构造函数。升级 `format.json` 的内核语义版本：旧会话照常打开，但回放不再保证和当时逐字节相同（README 已写明这个边界）。Yi 确认过没有要保留的旧数据 |
| C3 | 索引补不上时的“全部重放”兜底 | 删掉。索引补不上时拒绝启动，日志说明删除 `index.db` 后重启即可从会话文件重建。会话进内存只留一条路：启动时有未结束运行的会话，也走和平时一样的按需载入 |
| C4 | 测试专用的入口 | 主代码里只留生产用的构造函数和方法。测试用的占位实现（`NONE`、`never`）移到测试代码 |
| C5 | 索引库和快照的格式 | 可以改（都能从会话文件重建）：去掉只写不读的列，升级索引版本号；快照形状变了就让旧快照失效、改为重放 |
| C6 | `users.db` 的列迁移 | 删掉迁移代码，建表语句一次写全，和“旧数据不迁移”一致 |
| C7 | 跳号回归的开关 `tantalum.test.sequence-step` | 保留，写进 README 的“测试”一节（审查时被误判为死代码，就是因为没写） |

## Requirements

### Requirement 1：审查中发现的问题

**User Story:** 作为用户，我希望这些已知的小问题被修掉，而不是等重构顺便消失。

#### Acceptance Criteria

1. WHEN 自动压缩判断“可总结的内容太少”且裁剪也帮不上 THEN `beforeModelCall` 钩子 SHALL 在这一步里只执行一次，事实不重复报告，已经拿到的工作结果不丢；先写复现测试。
2. WHEN 运行中在输入框按 Enter THEN 提示文字 SHALL 和实际行为（排队）一致；控制台不再出现“插话”字样，除非真的提供插话操作。
3. WHEN 读上下文窗口大小 THEN 配置接口和压缩 SHALL 用同一个来源。
4. WHEN 压缩途中取消运行 THEN 迟到的摘要回复 SHALL 不进历史，规则写在一处，所有结束路径一致；先写测试。
5. WHEN 运行中 THEN 过程区标题 SHALL 不再每秒无用地重绘。

### Requirement 2：只有一种做法

**User Story:** 作为维护者，我希望每件事只有一种做法，读一处就懂。

#### Acceptance Criteria

1. WHEN 会话进内存 THEN 启动和平时 SHALL 走同一条载入路径（C3）；损坏文件的处理只有一种。
2. WHEN 告诉模型上下文变了 THEN SHALL 只有一套机制（C2）。
3. WHEN 模型调用失败、超时或流提前结束 THEN 两个模型客户端 SHALL 用同一份分类和处理代码，“被中断”的判断规则相同；客户端里只剩各自协议的部分。
4. WHEN 接口读写 JSON、取当前用户、校验会话 ID THEN 所有 Controller SHALL 用同一份工具代码。
5. WHEN 提交、压缩、取消占用和归还名额 THEN SHALL 只有一份占名额、发布、回滚的代码。
6. WHEN 回放一个会话 THEN 载入和逐事件查询 SHALL 共用一个回放循环；三个环 SHALL 共用一个封装。
7. WHEN 压缩没有被采用（钩子拒绝、摘要失败、内容太少） THEN 规则 8 的处理 SHALL 写在一张表里，触发方式自带它需要的数据，不再用 null 表示“已接管”，不再靠原因文字判断控制流。
8. WHEN 前端找运行状态、判断是否运行中、显示运行结束的文案、读写本地存储 THEN 每件事 SHALL 只有一个实现。
9. WHEN 主代码里一个类有多个构造函数 THEN SHALL 只是因为生产代码真的需要（C4）。

### Requirement 3：结构和名字对得上

**User Story:** 作为维护者，我希望看包名和类名就知道里面是什么。

#### Acceptance Criteria

1. WHEN 看 shell 的包 THEN 原始记录（会话文件、事件编解码、格式、锁）和可重建缓存（索引、快照、回放查询）SHALL 分在两个包里，包名和 README“数据目录”一节一致。
2. WHEN 检查 shell 包之间的依赖 THEN SHALL 没有循环，并由 ArchUnit 规则保证；下层包 SHALL 不依赖 `boot`。
3. WHEN 看 `Engine` THEN 它 SHALL 只负责组装、启动和关闭；存储的打开和关闭、接收提交、查询各自成类。
4. WHEN 看 `ApiController`、`ModelSteps`、`Transcript`、前端的 `App` 和 `ConversationPage` THEN 每个 SHALL 只做一类事。
5. WHEN 一个词出现在代码里 THEN 它 SHALL 只有一个意思（例如 recover、compact、snapshot、dispatcher），类名和字段名 SHALL 和类型一致。
6. WHEN 内核状态里有一份数据 THEN 它 SHALL 只存一处（`RunState` 不再复制会话的 system 和工具），`workOrigin` 这类值 SHALL 有类型而不是字符串。

### Requirement 4：没有残留

**User Story:** 作为读代码的人，我希望注释可信，没有用不到的东西。

#### Acceptance Criteria

1. WHEN 读注释 THEN SHALL 没有新旧叠在一起、和代码矛盾的注释；注释说明为什么，不讲历史，不只写需求编号（pi、DSH 出处保留）。
2. WHEN 搜索主代码 THEN SHALL 没有无人调用的方法、只写不读的字段或列、用不到的文案和组件属性。
3. WHEN 读 README、tech.md 和各 spec 的设计文档 THEN 关于代码结构的说法 SHALL 和代码一致；已完成 spec 的设计文档只改明显错误的说法，不重写。

## Non-Functional Requirements

### 行为不变
- 每个阶段结束时现有测试全部通过；只为删除测试专用入口、改名、移包而修改测试，不放宽断言。
- 对用户可见的变化只有需求 1 的修复。
- 每个阶段至少做一次变异检查：故意改坏一处被重构的逻辑，确认有测试失败。

### 可读性
- 重构后 shell 里没有超过 300 行的类（模型客户端的协议解析除外，要说明理由）。
- 每个阶段的汇报列出删了多少行、加了多少行、移动了哪些包。

### 成本
- 不需要真实模型测试，除非改动碰到发给 Claude 的请求格式（阶段 4 的模型客户端、阶段 5 的上下文变化）；碰到时各跑一次相关的真实测试并报用量。
