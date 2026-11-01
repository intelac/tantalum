# Requirements Document

## Introduction

`ops-hardening` 解决服务长期运行和重启时撑不住的问题：启动时间和内存都随全部历史增长。做法沿用 DeepSeek Harness（DSH）的已有方案，不自创。

现状（2026-11-01 核对代码）：

- 启动回放整个 journal，全部会话、运行和对话条目常驻内存，运行结束也不释放。
- `TurnQuery` 每次请求读整个 journal，含所有用户。`events` 表只有主键。
- 会话列表由内存里的全部对话条目现算。
- `SseHub` 只能按用户关闭：一个设备退出，该用户所有设备都断开。
- `Engine.close` 不等在途 Handler。
- 提交账本启动时读全部历史；`rebuild` 漏了 `CompactRequested`，重启后重复的手动压缩请求不会被去重（需求 3.3 修复）。

### 参考做法

| 问题 | DSH（已读源码） | pi | 本 spec |
|---|---|---|---|
| 没人用的会话 | 不进内存；用时 `resume` 或只读打开 | 只有打开的那个 | 同 DSH |
| 会话列表 | 只读文件头和投影缓存，不打开内容 | 扫文件 | 同 DSH |
| 投影缓存 | 每会话检查点，每 N 事件或 T 毫秒写；崩溃时只会落后、不会超前；不兼容就忽略 | 无 | 同 DSH，N 和 T 可配 |
| 冷读缓存 | 有上限的 LRU，按修订号失效 | 无 | 同 DSH |
| 回收条件 | `引用数 == 0 且 在途 == 0`，不按业务状态枚举 | 不需要 | 同 DSH |
| 存储 | 每会话一份日志 | 每会话一个文件 | **不同**：保留单一 journal，按会话建索引 |

没有照搬的两点。DSH 文档写明了回收条件，但我没有找到它服务端按空闲自动回收的代码，所以“何时回收”是我们自己的决定（H3）。DSH 是单用户，跨会话的用户级状态（每日额度）它没有，要我们自己解决（H4）。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| 会话按需载入和回收 | 全局快照（按需载入后启动不需要它） |
| 索引和投影缓存 | 裁剪或删除 journal（审计依据） |
| 回放接口只读相关事件 | 水平扩展、多进程（tech.md 明确不做） |
| SSE 按登录会话断开 | 崩溃后自动续跑（仍只能撤销） |
| 关闭顺序 | 登录会话持久化、两级持久化、遥测 |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| H1 | 存储形态 | 保留单一 SQLite journal，加索引 `(session_id, seq)`、`(principal, seq)`。不拆成每会话文件 | 全局序号、单写入者和“先落盘”都依赖它；SQLite 索引已能得到按会话读取的效果 |
| H2 | 内核怎么办 | `Kernel.step` 不变。内存状态只是在用会话的缓存；载入是从空状态重放该会话的事件，回收是从缓存移除，都不写事件 | 回放从不依赖回收，所以结果不受影响；内核仍是纯函数 |
| H3 | 何时回收 | 没有活动运行、没有排队提交，且空闲超过配置时间（默认 30 分钟）；另有常驻会话数上限（默认 1000），超限回收最久未用的 | 判断沿用 DSH 的通用条件，不枚举业务状态。时间和上限是初始值，由基线校正 |
| H4 | 用户级状态 | 用量写入新表 `usage(principal, seq, ...)`。重放一个会话时，每个事件前把该用户用量设为“截至这个序号的累计” | 每日额度钩子读它。这样不用读该用户的全部事件，钩子结果也与当时相同 |
| H5 | 钩子约束（新增，要写进 README） | 钩子只能依赖当前运行、当前会话和该用户的用量，不能读其他会话或其他用户 | 这些数据可能不在内存，重放也不会有。现有示例钩子是否都满足，设计第一步核对；不满足的要改 |
| H6 | 投影缓存 | 每会话一行：用户、标题、最近活动、状态、截至序号。写在 journal 同库，节流写入。读时补上该序号之后的事件 | 与 DSH 相同：只落后不超前，坏了能从 journal 重建 |
| H7 | 载入在哪做 | 不在内核线程上读库。提交线程先载入到待用区，事件进环后由内核线程原子并入 | 内核线程 P99 5 ms 的约束不能被一个大会话破坏。具体机制在设计里定 |
| H8 | 关闭时的在途调用 | 等配置时间（默认 30 秒），到时中断，重启后按现有规则标“待恢复” | 不能无限等一个长调用 |

## Requirements

### Requirement 1：基线

**User Story:** 作为维护者，我希望优化对着实测数字做。

#### Acceptance Criteria

1. WHEN 跑基线工具 THEN SHALL 用生成的 journal（1 万、10 万、50 万事件）测启动时间、堆内存、会话列表、单用户 `/turns` 和 `/trace` 耗时，改造前后各一次，结果写进 tech.md。
2. WHEN 改造前基线完成 THEN 需求 2.2、2.3、5.3 的目标数字 SHALL 在设计文档里确认。

### Requirement 2：按需载入和回收

**User Story:** 作为运维，我希望重启时间和内存不随历史总量增长。

#### Acceptance Criteria

1. WHEN 服务启动 THEN 系统 SHALL 只恢复有未结束运行的会话，这些运行仍标“待恢复”；其余会话不进内存。
2. WHEN 有 50 万个事件 THEN 启动 SHALL 在 5 秒内完成（暂定，按需求 1.2 确认）。
3. WHEN 服务长期运行 THEN 堆内存 SHALL 取决于常驻会话，不取决于历史总量（标准按需求 1.2 确认）。
4. WHEN 提交到、查询或取消一个不在内存的会话 THEN 系统 SHALL 透明载入；状态和对话视图 SHALL 与从未回收时逐字节相同，prompt 的压缩视图也相同。
5. WHEN 会话满足 H3 THEN SHALL 被回收；有活动运行或排队提交的会话 SHALL NOT 被回收。
6. WHEN 载入一个大会话 THEN 其他会话的事件处理 SHALL 不被阻塞。
7. WHEN 载入和回收与提交、迟到的工具结果并发 THEN 事件 SHALL 不丢、不重复。
8. WHEN 别人的会话 ID 被访问 THEN 仍 SHALL 返回 404，且不载入它的内容。

### Requirement 3：索引、用量和去重

**User Story:** 作为运维，我希望旧数据能直接升级。

#### Acceptance Criteria

1. WHEN 旧 journal 第一次启动 THEN 系统 SHALL 建索引并回填投影缓存和用量表，只做一次，中断后可继续，日志有进度。
2. WHEN 投影缓存或用量表损坏、版本不符 THEN 系统 SHALL 记警告并从 journal 重建。
3. WHEN 重复提交（含手动压缩）的 `requestId` 已在任意历史中 THEN SHALL 仍被去重，不把全部历史常驻内存。
4. WHEN 钩子读用量 THEN 实时和重放 SHALL 看到相同的值，含跨天和同一用户多会话交错。

### Requirement 4：会话列表

**User Story:** 作为用户，我希望会话再多，侧栏也很快。

#### Acceptance Criteria

1. WHEN 请求会话列表 THEN SHALL 只读投影缓存加少量补读，不载入会话内容。
2. WHEN 进程崩溃后重启 THEN 列表 SHALL 与 journal 一致。

### Requirement 5：回放接口

**User Story:** 作为用户，我希望别人的历史再多，我的轨迹页也不会变慢。

#### Acceptance Criteria

1. WHEN 请求 `/turns`、`/trace` THEN 系统 SHALL 只读该运行所在会话的事件和用量表。
2. WHEN 请求超出内存范围的 SSE 补读 THEN 系统 SHALL 只读该用户的事件。
3. WHEN 其他用户有 100 万个事件 THEN 接口耗时 SHALL 不明显增长（标准按需求 1.2 确认）。
4. WHEN 按会话回放 THEN 结果 SHALL 与全量回放中该会话的部分相同，由多用户交错的测试保证。

### Requirement 6：连接

**User Story:** 作为用户，我希望退出一个设备不影响另一个设备，失效的身份不能继续收事件。

#### Acceptance Criteria

1. WHEN 用户退出 THEN 只有该登录会话的 SSE SHALL 断开。
2. WHEN 登录会话过期 THEN 它的 SSE SHALL 在一个心跳周期内断开。
3. WHEN 用户被停用或改密码 THEN 该用户的 SSE SHALL 全部断开；命令行在服务之外修改时的生效时间在设计里说明。

### Requirement 7：关闭

**User Story:** 作为运维，我希望重启不丢已接受的提交。

#### Acceptance Criteria

1. WHEN 收到关闭信号 THEN 系统 SHALL 依次：拒绝新提交（503）→ 断开 SSE → 等待在途 Handler（H8）→ 排空两个环 → 写投影缓存 → 关闭 journal 和锁；每步失败不跳过后面的。
2. WHEN 已返回 200 的提交遇到正常关闭 THEN 它 SHALL 已写入 journal，重启后可见。
3. WHEN 关闭时有长时间的模型调用 THEN 进程 SHALL 在配置时间内退出。

### Requirement 8：测试

**User Story:** 作为维护者，我需要证据。

#### Acceptance Criteria

1. WHEN 跑不变量测试 THEN 同一组事件在“从不回收”和“每个事件后都回收”两种配置下，prompt、对话视图、钩子结果 SHALL 逐字节相同，覆盖压缩、工具轮、记忆变化、额度钩子。
2. WHEN 跑压测 THEN 多用户并发运行，回收和载入不断发生，已有并发压测的不变量 SHALL 全部成立。
3. WHEN 杀进程重启（`kill -9`，打包的 jar）THEN 已提交的事件 SHALL 不丢，落后的缓存 SHALL 被补齐。
4. WHEN 跑 Playwright THEN 两个浏览器上下文登录同一用户，一个退出，另一个 SSE SHALL 继续收到事件；被回收的会话打开后内容完整。
5. 本 spec 不涉及模型行为，不跑真实模型。

## Non-Functional Requirements

### Code Architecture and Modularity
- `Kernel.step` 的语义不变；载入、回收、索引、缓存都在 shell。用量注入如需改 value，在设计里说明。

### Performance
- 内核线程不做 SQLite 读取。

### Security
- 缓存和用量表权限与 journal 相同；跨用户隔离测试要覆盖载入和按会话回放。

### Reliability
- 缓存的任何失败都不能阻止启动或影响运行；journal 仍是唯一事实来源。

### Usability
- 启动日志写明恢复了多少会话、耗时；README 写明回收配置和钩子约束。
