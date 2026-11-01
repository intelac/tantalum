# Requirements Document

## Introduction

`ops-hardening` 解决服务长期运行和重启时撑不住的问题。上下文压缩已由 `context-compaction` 完成，这里不再包含。

现状的缺口（2026-11-01 核对代码）：

- `Startup.recover` 每次启动回放整个 journal，启动时间随历史增长。
- `TurnQuery` 的 `turns`、`trace`、`messagesAfter` 每次请求都读整个 journal，含所有用户的事件。`events` 表只有主键，没有按用户的索引。
- `SseHub` 只能按用户关闭连接：一个设备退出，会断开该用户所有设备。
- `Engine.close` 排空环后就关 journal，不通知也不等待在途 Handler；没有配置 Web 层优雅关闭。
- 这些问题现在有多严重没有测过。所以第一步是测基线，目标数字由基线确认。

依据：DeepSeek Harness 有 journal 快照（README 能力对照表）；pi 用文件存会话，不适用。设计第一步再查 DSH 快照的具体做法。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| 启动、回放、内存的基线测量 | 水平扩展、多进程（tech.md 明确不做） |
| journal 快照，启动从快照恢复 | 裁剪或删除 journal（审计依据，一条不删） |
| 回放接口只读该用户的事件 | 两级持久化、遥测（没有真实需求） |
| SSE 按登录会话断开 | 崩溃后自动续跑（仍只能撤销） |
| 关闭顺序 | 把登录会话持久化（重启仍要重新登录） |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| O1 | 快照是什么 | 内核状态、对话投影、提交账本和序号，存在 journal 同库的新表。快照只是缓存 | journal 仍是唯一事实来源，快照坏了不丢数据 |
| O2 | 快照何时失效 | 带格式版本号。版本不符、读取失败或校验不符就记警告并全量回放 | 宁可启动慢，不能恢复出错误状态 |
| O3 | 回放范围 | 回放接口只读该用户的事件，从空状态重放 | 事件序号不变，运行和命令 ID 不变。**新增插件约束**：钩子只能依赖该用户自己的状态，写进 README |
| O4 | 内存随历史增长 | 本 spec 只测量并写明每万事件占多少内存，不释放状态 | 释放已结束运行要改内核和多个接口，风险大。基线显示确实是问题时再单独立项 |
| O5 | 关闭时在途的模型和工具调用 | 等配置的时间（默认 30 秒），到时中断。它们按现有规则在重启后标“待恢复” | 不能无限等一个长调用，也不能丢已写入的事件 |

## Requirements

### Requirement 1：基线

**User Story:** 作为维护者，我希望优化对着实测数字做。

#### Acceptance Criteria

1. WHEN 跑基线工具 THEN SHALL 用生成的 journal（1 万、10 万、50 万事件）测启动时间、堆内存、单用户 `/turns` 和 `/trace` 耗时，结果写进 tech.md。
2. WHEN 基线完成 THEN 需求 2.4 和 4.3 的目标数字 SHALL 在设计文档里确认。

### Requirement 2：journal 快照

**User Story:** 作为运维，我希望重启时间不随历史无限增长。

#### Acceptance Criteria

1. WHEN 距上次快照超过配置的事件数（默认 10000）或正常关闭 THEN 系统 SHALL 写入快照，且不阻塞内核线程；写失败只记日志。
2. WHEN 启动且快照可用 THEN 系统 SHALL 从快照加之后的事件恢复；结果 SHALL 与全量回放逐字节相同，由测试保证，覆盖压缩过的会话和未结束的运行。
3. WHEN 快照损坏或版本不符 THEN 系统 SHALL 记警告并全量回放。
4. WHEN 有 50 万个事件 THEN 启动恢复 SHALL 在 5 秒内完成（暂定，按需求 1.2 确认）。
5. WHEN 旧 journal 没有快照表 THEN 系统 SHALL 正常启动并创建它。

### Requirement 3：连接

**User Story:** 作为用户，我希望退出一个设备不影响另一个设备，失效的身份不能继续收事件。

#### Acceptance Criteria

1. WHEN 用户退出 THEN 只有该登录会话的 SSE SHALL 断开，同一用户的其他会话不受影响。
2. WHEN 登录会话过期 THEN 它的 SSE SHALL 在一个心跳周期内断开。
3. WHEN 用户被停用或改密码 THEN 该用户的 SSE SHALL 全部断开；如果命令行在服务之外修改，生效时间在设计里说明。

### Requirement 4：回放接口

**User Story:** 作为用户，我希望别人的历史再多，我的轨迹页也不会变慢。

#### Acceptance Criteria

1. WHEN 请求 `/turns`、`/trace` 或 SSE 超出内存范围的补读 THEN 系统 SHALL 只读该用户的事件。
2. WHEN 回放单用户事件 THEN 结果 SHALL 与全量回放中该用户的部分相同，由多用户交错的测试保证。
3. WHEN 其他用户有 100 万个事件 THEN 接口耗时 SHALL 不随之明显增长（标准按需求 1.2 确认）。
4. WHEN 旧 journal 没有索引 THEN 启动时 SHALL 创建。

### Requirement 5：关闭

**User Story:** 作为运维，我希望重启不丢已接受的提交。

#### Acceptance Criteria

1. WHEN 收到关闭信号 THEN 系统 SHALL 依次：拒绝新提交（503）→ 断开 SSE → 等待在途 Handler（O5）→ 排空两个环 → 写快照 → 关闭 journal 和锁；每步失败不跳过后面的。
2. WHEN 已返回 200 的提交遇到正常关闭 THEN 它 SHALL 已写入 journal，重启后可见。
3. WHEN 关闭时有长时间的模型调用 THEN 进程 SHALL 在配置时间内退出，该运行重启后标“待恢复”。

### Requirement 6：测试

**User Story:** 作为维护者，我需要证据。

#### Acceptance Criteria

1. WHEN 跑压测 THEN 多用户并发运行中途写快照，重启后视图 SHALL 与全量回放逐字节相同，模型不被调用。
2. WHEN 杀进程重启（`kill -9`，打包的 jar）THEN 已提交的事件 SHALL 不丢，写到一半的快照 SHALL 被忽略。
3. WHEN 跑 Playwright THEN 两个浏览器上下文登录同一用户，一个退出，另一个 SSE SHALL 继续收到事件。
4. 本 spec 不涉及模型行为，不跑真实模型。

## Non-Functional Requirements

### Code Architecture and Modularity
- 快照和索引只在 shell；kernel 和 value 不感知快照。

### Performance
- 快照序列化不在内核线程上做，取不可变状态的引用即可。

### Security
- 快照含完整 prompt，权限与 journal 相同；跨用户隔离测试要覆盖过滤后的回放。

### Reliability
- 快照的任何失败都不能阻止服务启动或影响运行。

### Usability
- 启动日志写明从快照还是全量恢复、回放了多少事件、耗时。
