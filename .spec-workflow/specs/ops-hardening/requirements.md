# Requirements Document

## Introduction

`ops-hardening` 解决服务长期运行和重启时撑不住的问题：启动时间和内存都随全部历史增长。做法沿用 DeepSeek Harness（DSH）的已有方案，不自创。每条需求标明出处；“自定”表示两个项目都没有现成做法。出处路径相对各自仓库，pi 指 `packages/coding-agent`。

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
| 冷载入提速 | 投影检查点，冷读从检查点之后接着算；检查点只是捷径，不是事实来源（`session-projection-cache/README.md`） | 无 | 思路同 DSH，但范围更大：快照内核状态和对话条目（H13） |
| 回收条件 | `引用数 == 0 且 在途 == 0`，不按业务状态枚举 | 不需要 | 同 DSH |
| 存储 | 每会话一份日志 | 每会话一个文件 | 同 pi、DSH：每会话一个 JSONL 文件 |
| 性能回归 | `benchmarks/`：合成数据、新进程采样、内存峰值、代码里的预算（`benchmarks/AGENTS.md`） | 无 | 同 DSH |
| 历史加载 | 按轮分页，一页至少 50 条消息且跨 2 轮，最多 500 条（`packages/api/session-controller/README.md`） | 无 | 同 DSH |
| 格式升级 | 版本号、逐级迁移、拒绝未来版本、不覆盖旧数据（`docs/architecture.md`） | `CURRENT_SESSION_VERSION` 和逐级迁移（`src/core/session-manager.ts`） | 同 DSH |
| 崩溃后的运行 | 恢复时补工具错误和“已中断”结束标记，可继续（`docs/subsystems/persistence.md`） | 无此场景 | 同 DSH |
| 提交去重 | 在排队和日志里查 `requestId`（`packages/api/session-controller/src/commands.ts`） | 无 | 同 DSH，用索引查 |

没有照搬的两点。DSH 文档写明了回收条件，但我没有找到它服务端按空闲自动回收的代码，所以“何时回收”是我们自己的决定（H3）。DSH 是单用户，跨会话的用户级状态（每日额度）它没有，要我们自己解决（H4）。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| 会话按需载入和回收，每会话快照（Yi 决定，H13） | 全局快照（按需载入后启动不需要它） |
| 每会话日志、索引库和投影缓存 | 裁剪或删除 journal（审计依据） |
| 回放接口只读相关事件；序号约定不封死多实例（Yi 决定，H14） | 多实例的实现：共享存储、接管、路由（已记入 BACKLOG，以后单独开 spec） |
| 按轮分页的历史接口 | 崩溃后自动重新执行（可能重复写操作） |
| 格式版本和迁移 | |
| 崩溃后运行自动收尾 | |
| 进回归的性能预算 | |
| SSE 按登录会话断开 | 登录会话持久化 |
| 关闭顺序 | 两级持久化、遥测 |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| H1 | 存储形态（Yi 决定，取代原“单一 journal”） | 每个会话一个只追加的 JSONL 文件：`<data-dir>/sessions/<用户>/<会话>.jsonl`。序号的约定见 H14，运行 ID、命令 ID、SSE 位置的形式不变。会话元数据、运行到会话的映射、去重和用量放在一个可重建的索引库里 | 同 pi、DSH 每会话一份日志。DSH `docs/subsystems/persistence.md`；残缺尾部只丢不完整的记录，同 DSH。索引库和全局序号自定，因为它们是多用户才有的需求 |
| H2 | 内核怎么办 | `Kernel.step` 不变。内存状态只是在用会话的缓存；载入是从空状态重放该会话的事件，回收是从缓存移除，都不写事件 | 回放从不依赖回收，所以结果不受影响；内核仍是纯函数 |
| H3 | 何时回收 | 没有活动运行、没有排队提交，且空闲超过配置时间（默认 30 分钟）；另有常驻会话数上限（默认 1000），超限回收最久未用的 | 判断沿用 DSH 的通用条件，不枚举业务状态。时间和上限是初始值，由基线校正 |
| H4 | 用户级状态 | 用量写入索引库的表 `usage(principal, seq, ...)`。重放一个会话时，每个事件前把该用户用量设为“截至这个序号的累计” | 每日额度钩子读它。这样不用读该用户的全部事件，钩子结果也与当时相同 |
| H5 | 钩子约束（新增，要写进 README） | 钩子只能依赖当前运行、当前会话和该用户的用量，不能读其他会话或其他用户 | 这些数据可能不在内存，重放也不会有。现有示例钩子是否都满足，设计第一步核对；不满足的要改 |
| H6 | 投影缓存 | 每会话一行：用户、标题、最近活动、状态、截至序号。写在索引库，节流写入。读时补上该序号之后的事件 | 与 DSH 相同：只落后不超前，坏了能从 journal 重建 |
| H7 | 载入在哪做 | 不在内核线程上读库。提交线程先载入到待用区，事件进环后由内核线程原子并入 | 内核线程 P99 5 ms 的约束不能被一个大会话破坏。具体机制在设计里定 |
| H8 | 关闭时的在途调用 | 等配置时间（默认 30 秒），到时中断，重启后按 H9 收尾 | 限时排空，超时取消并报告可能丢失，同 DSH `packages/host/product-telemetry-otel`。完整顺序自定 |
| H12 | 多会话写入和放行（Yi 提出） | 每个会话的写入互相独立、并行 fsync。事件落盘后才交给内核，但只保证同一用户内按序：不同用户互不等待。全局序号只用于生成唯一 ID，不再代表处理顺序 | 每会话独立写入路径同 DSH `docs/subsystems/persistence.md`。按用户保序是多用户才有的问题，自定。具体结构（是否改动 Input Ring）在设计里定，单线程内核和先落盘不变 |
| H9 | 崩溃或重启后未结束的运行（**改变现状**） | 启动时给这些运行追加结束事件：未配对的工具调用补“结果未知”，运行标为已中断，用户可在同一会话继续。不自动重新执行 | 同 DSH。用户不用先撤销；不重跑是因为写操作可能已生效。取消“待恢复”状态及 core-skeleton 的相应行为 |
| H10 | 去重 | 不再把账本常驻内存，查索引库的 `(principal, request_id)`；进行中的在内存占位 | 同 DSH 的思路，但范围是用户而不是会话，这是现有接口语义 |
| H13 | 每会话快照（Yi 提出） | 没有活动运行时，为会话写一份快照：内核状态、对话条目、截至的序号和文件位置。冷载入读快照，只重放它之后的事件。损坏、缺失或版本不符就丢弃并全量重放。journal 不删，`/turns`、`/trace` 仍读事件 | 思路同 DSH 的投影检查点：只是捷径，日志领先。设计简报的待定项“journal 快照策略”在此定下 |
| H14 | 序号约定（Yi 决定：这次不做多实例，但不封路） | 序号的约定只有三条：全局唯一、同一用户内递增、可以有空洞且不保证连续。任何代码和文件格式都不能依赖跨用户的先后，也不能依赖连续。分配放在一个接口后面，现在的实现是单进程计数器 | 取代“改成每用户各自计数”的初步建议：运行 ID 和命令 ID 由序号生成且按全局查找，各自计数会让两个用户都有 `run-5`。以后多实例只需换分配方式，不改文件格式。自定 |
| H11 | 性能预算 | Maven profile `bench`：合成数据、新进程、预算写在代码里，不能用环境变量放宽 | 同 DSH `benchmarks/AGENTS.md`。仓库没有 CI，发布前手动跑；有 CI 后接入 |

**对需求 1.1 的偏离（Yi 已确认，2026-11-02）：** 50 万事件的改造前基线没有实测。按平方外推启动约 50 分钟，超过单样本 30 分钟上限。这一档只在改造后实测。

## Requirements

### Requirement 1：基线和性能预算

出处：DSH `benchmarks/AGENTS.md`、`benchmarks/session-corpus`、`session-open`、`long-session-browser`。

**User Story:** 作为维护者，我希望性能退步时测试会失败。

#### Acceptance Criteria

1. WHEN 跑 `bench` THEN SHALL 用合成的 journal（1 万、10 万、50 万事件）在新进程里测启动时间、内存峰值、会话列表、打开长会话、`/turns`、`/trace`，报告每个样本和机器信息。
2. WHEN 改造前基线完成 THEN 需求 2.2、2.3、5.3、8.2 的预算 SHALL 在设计文档确认，并写进代码；结果写进 tech.md。
3. WHEN 超出预算 THEN `bench` SHALL 失败。

### Requirement 2：按需载入和回收

出处：DSH `docs/subsystems/persistence.md`、`docs/defensive-patterns/conversation-and-session.md`。回收时机自定（H3）。

**User Story:** 作为运维，我希望重启时间和内存不随历史总量增长。

#### Acceptance Criteria

1. WHEN 服务启动 THEN 系统 SHALL 只处理有未结束运行的会话，并按 H9 收尾；其余会话不进内存。
1a. WHEN 运行被收尾 THEN 用户 SHALL 能在同一会话继续提交，模型看到配对完整的历史；未确认的工具 SHALL 标“结果未知”，不重新执行。
2. WHEN 有 50 万个事件 THEN 启动 SHALL 在 5 秒内完成（Yi 已确认）。
3. WHEN 服务长期运行 THEN 堆内存 SHALL 取决于常驻会话，不取决于历史总量（标准按需求 1.2 确认）。
4. WHEN 提交到、查询或取消一个不在内存的会话 THEN 系统 SHALL 透明载入；状态和对话视图 SHALL 与从未回收时逐字节相同，prompt 的压缩视图也相同。
5. WHEN 会话满足 H3 THEN SHALL 被回收；有活动运行或排队提交的会话 SHALL NOT 被回收。
6. WHEN 载入一个大会话 THEN 其他会话的事件处理 SHALL 不被阻塞。
7. WHEN 载入和回收与提交、迟到的工具结果并发 THEN 事件 SHALL 不丢、不重复。
9. WHEN 一个用户的文件写得很慢 THEN 其他用户的事件处理 SHALL 不被拖住，由故意放慢写入的测试保证。
8. WHEN 别人的会话 ID 被访问 THEN 仍 SHALL 返回 404，且不载入它的内容。

### Requirement 3：索引、版本、用量和去重

出处：格式版本同 DSH `docs/architecture.md`、pi `src/core/session-manager.ts`；去重见 H10；用量表自定（H4）。

**User Story:** 作为运维，我希望旧数据能直接升级。

#### Acceptance Criteria

1. WHEN 旧的单一 journal 第一次启动 THEN 系统 SHALL 拆成每会话文件并建索引库，只做一次，中断后可继续，日志有进度；旧文件 SHALL 保留不动。
1a. WHEN 每会话文件尾部残缺 THEN SHALL 只丢不完整的记录并记日志，已完整的事件不丢。
1b. WHEN 一批事件写入多个会话文件 THEN 全部 fsync 后才算落盘；崩溃时序号可以有空洞，不能错序。
1c. WHEN 索引库丢失 THEN SHALL 能从每会话文件完整重建。
2. WHEN 投影缓存或用量表损坏、版本不符 THEN 系统 SHALL 记警告并从 journal 重建。
3. WHEN 重复提交（含手动压缩）的 `requestId` 已在任意历史中 THEN SHALL 仍被去重，不把全部历史常驻内存。
4. WHEN journal 的格式版本比程序新 THEN 启动 SHALL 拒绝并说明，不改动数据。
5. WHEN 升级格式 THEN SHALL 逐级迁移，已有文件 SHALL NOT 就地改写，迁移结果写成带版本的新文件（同 DSH）。
6. WHEN 钩子读用量 THEN 实时和重放 SHALL 看到相同的值，含跨天和同一用户多会话交错。

### Requirement 4：会话列表

出处：DSH `packages/session/session-projection-cache/README.md`、`packages/api/session-controller/README.md`。

**User Story:** 作为用户，我希望会话再多，侧栏也很快。

#### Acceptance Criteria

1. WHEN 请求会话列表 THEN SHALL 只读投影缓存加少量补读，不载入会话内容。
2. WHEN 进程崩溃后重启 THEN 列表 SHALL 与 journal 一致。

### Requirement 5：回放接口

出处：按会话读同 DSH `docs/subsystems/persistence.md`；用户级补读自定。

**User Story:** 作为用户，我希望别人的历史再多，我的轨迹页也不会变慢。

#### Acceptance Criteria

1. WHEN 请求 `/turns`、`/trace` THEN 系统 SHALL 只读该运行所在会话的事件和用量表。
2. WHEN 请求超出内存范围的 SSE 补读 THEN 系统 SHALL 只读该用户、且序号之后有更新的会话文件。
3. WHEN 其他用户有 100 万个事件 THEN 接口耗时 SHALL 不明显增长（标准按需求 1.2 确认）。
4. WHEN 按会话回放 THEN 结果 SHALL 与全量回放中该会话的部分相同，由多用户交错的测试保证。

### Requirement 6：连接

出处：已释放的连接方被拒绝，同 DSH `docs/api-gateway.md`。登录过期和停用用户 DSH 没有，自定。

**User Story:** 作为用户，我希望退出一个设备不影响另一个设备，失效的身份不能继续收事件。

#### Acceptance Criteria

1. WHEN 用户退出 THEN 只有该登录会话的 SSE SHALL 断开。
2. WHEN 登录会话过期 THEN 它的 SSE SHALL 在一个心跳周期内断开。
3. WHEN 用户被停用或改密码 THEN 该用户的 SSE SHALL 全部断开；命令行在服务之外修改时的生效时间在设计里说明。

### Requirement 7：关闭

出处：关闭时主动断开所有连接，同 DSH `packages/host/webserver/README.md`；限时排空见 H8；整体顺序自定。

**User Story:** 作为运维，我希望重启不丢已接受的提交。

#### Acceptance Criteria

1. WHEN 收到关闭信号 THEN 系统 SHALL 依次：拒绝新提交（503）→ 断开 SSE → 等待在途 Handler（H8）→ 排空两个环 → 写投影缓存 → 关闭 journal 和锁；每步失败不跳过后面的。
2. WHEN 已返回 200 的提交遇到正常关闭 THEN 它 SHALL 已写入 journal，重启后可见。
3. WHEN 关闭时有长时间的模型调用 THEN 进程 SHALL 在配置时间内退出。

### Requirement 8：历史分页

出处：DSH `packages/api/session-controller/README.md`（`loadOlder`、`loadThrough`）、`benchmarks/long-session-browser`。

**User Story:** 作为用户，我希望长会话打开很快。

#### Acceptance Criteria

1. WHEN 打开会话 THEN 接口 SHALL 只返回最近一页，按运行对齐，至少 50 条消息且跨 2 个运行，最多 500 条。
2. WHEN 打开 500 条消息的会话 THEN 首屏时间 SHALL 在预算内（按需求 1.2 确认）；向上滚动 SHALL 加载更早的页，位置不跳动。
3. WHEN 分页和 SSE 同时进行或断线重连 THEN 条目 SHALL 不缺、不重复。

### Requirement 9：测试

**User Story:** 作为维护者，我需要证据。

#### Acceptance Criteria

1. WHEN 跑不变量测试 THEN 同一组事件在“从不回收”“每个事件后都回收”“每次都从快照载入”三种配置下，prompt、对话视图、钩子结果 SHALL 逐字节相同，覆盖压缩、工具轮、记忆变化、额度钩子。
2. WHEN 跑压测 THEN 多用户并发运行，回收和载入不断发生，已有并发压测的不变量 SHALL 全部成立。
3. WHEN 杀进程重启（`kill -9`，打包的 jar）THEN 已提交的事件 SHALL 不丢，落后的缓存 SHALL 被补齐，未结束的运行 SHALL 按 H9 收尾，且不重新调用模型和工具。
4. WHEN 跑 Playwright THEN 两个浏览器上下文登录同一用户，一个退出，另一个 SSE SHALL 继续收到事件；被回收的会话打开后内容完整；崩溃后的运行显示已中断且能继续。
5. WHEN 用真实 Claude 严格校验模式 THEN 崩溃收尾后的继续提交 SHALL 被接受（判定：无 400；这是唯一的真实模型测试，因为合成结果的历史只有真实服务能校验）。

### Requirement 10：每会话快照

出处：思路同 DSH `packages/session/session-projection-cache/README.md`，范围见 H13。

**User Story:** 作为用户，我希望再长的会话，重新激活时也不用从头重放。

#### Acceptance Criteria

1. WHEN 载入一个有有效快照的会话 THEN 系统 SHALL 只重放快照之后的事件，由读取计数验证。
2. WHEN 从快照载入 THEN 状态、对话视图和 prompt SHALL 与全量重放逐字节相同（需求 9.1）。
3. WHEN 快照缺失、损坏、比文件超前或版本不符 THEN 系统 SHALL 记警告并全量重放，结果不受影响。
4. WHEN 写快照失败 THEN 运行 SHALL 不受影响，cleanroom SHALL 不做文件 I/O。
5. WHEN 有活动运行 THEN SHALL NOT 为该会话写快照。
6. WHEN 比较长会话的冷载入 THEN `bench` SHALL 报告有无快照两种耗时。

### Requirement 11：序号约定

出处：自定（H14）。

**User Story:** 作为维护者，我希望以后做多实例时不用迁移已有文件。

#### Acceptance Criteria

1. WHEN 序号跳号或跨用户乱序 THEN 载入、回放、分页、SSE 补读、去重、用量 SHALL 全部正确。
2. WHEN 分配序号 THEN SHALL 经过一个接口，序号 SHALL 不超过 2^53（控制台用 JavaScript 数字解析）。

## Non-Functional Requirements

### Code Architecture and Modularity
- 载入、回收、索引、缓存都在 shell。内核的改动限于用量注入（如需要）和 H9 的收尾事件，在设计里说明。

### Performance
- 内核线程不做文件和索引库读取。
- 并行 fsync 的写入吞吐和延迟 SHALL 进需求 1 的基线，与单一 SQLite 对比。

### Security
- 每会话文件、缓存和用量表权限只限进程所有者；跨用户隔离测试要覆盖载入和按会话回放。

### Reliability
- 索引库和缓存的任何失败都不能阻止启动或影响运行；每会话文件是唯一事实来源。

### Usability
- 启动日志写明恢复了多少会话、耗时；README 写明回收配置和钩子约束。
