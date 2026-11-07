# Requirements Document

## Introduction

2026-11-07 的整体 review（`.spec-workflow/reviews/2026-11-07-architecture-review.md`，下称“报告”）的结论：架构硬约束没破，但有六处补丁味和一批真缺陷。这个 spec 把报告第 4 节的五个阶段做掉。每个阶段一次全量回归，全程用脚本化模型和录制的流，不用真实模型（额度只剩约 10 美元）。

调研：缺陷来自我们自己的代码和 open-code-review 的扫描，不是“agent 基本功能”的取舍，所以不逐项查 pi/DSH；两处借了它们的做法并标出处（提交回执的语义照 DSH “接受即落盘”，模型流的看门狗照 pi 的 `AbortSignal` 思路）。

### 范围

五个阶段（报告第 4 节）：

1. 管线与 journal（本文件第 1–9 条需求，最详细）
2. 模型客户端与流（第 10 条）
3. 登录与安全加固（第 11 条）
4. 去重与归位（第 12 条）
5. 拆大类与清注释（第 13 条）

不做：报告 C 组“不成立或可忽略”的项；为了好看改 `ApiController`、`TantalumProperties`；控制台类型生成。

## Requirements

### Requirement 1：提交回执不说谎

**User Story:** 作为提交的客户端，我拿到 200 就意味着这一步已经落盘；重复提交拿到的回执要和原提交一致。

#### Acceptance Criteria

1. WHEN 一个重复的提交在原提交“已认领、还没登记回执”的空当到达 THEN 它 SHALL 等原提交的回执，而不是立刻得到“已保存”（报告 #15、#16）。
2. WHEN 原提交被拒绝（429/503）或没能进入环 THEN 等着它的重复提交 SHALL 得到失败（`NotSaved`），不是 200（#17）。
3. WHEN 服务关闭或管线停住时还有在途回执 THEN 它们 SHALL 全部以失败完成，不再有人永远等（M84）。
4. WHEN 去重用的索引查询失败 THEN 提交 SHALL 以 503 拒绝，不当作新提交处理（M40）。
5. WHEN 发布路径中途抛异常 THEN 名额、新运行计数、回执、认领 SHALL 全部退回（M80）。

### Requirement 2：用户通道不会死

**User Story:** 作为管理员，我不希望一个用户的通道因为一次异常就到重启前都不处理事件。

#### Acceptance Criteria

1. WHEN 通道处理一批事件时抛出任何 Throwable THEN 通道 SHALL 把这批事件按“写失败”处理（上报、解钉、丢弃、运行 done），线程继续处理下一批或在停机后把后续批次丢弃（#18）。
2. 钉住（pin）SHALL 和写入在同一个受保护区域里，异常时已钉住的都解开（M89）。
3. WHEN `afterDurable` 抛出 Error THEN 其余已落盘步骤的 `afterDurable` 和 `done` SHALL 照常运行（M90）。
4. WHEN `exit.publish` 抛异常（不只是返回 false） THEN 剩余事件 SHALL 解钉并丢弃（M92）。
5. WHEN 分发器等放行名额时被中断 THEN 用户名额 SHALL 归还（M82）。
6. WHEN 管线停住后还有停在用户上限外的事件 THEN 它们 SHALL 被丢弃并归还名额，`awaitIdle` 不再空转到超时（M97）。
7. 多会话并行写入一部分失败时仍按整批失败、停机处理（M91 不改）：这是“落盘才生效”的既定做法，在注释里写明。

### Requirement 3：journal 写失败后不污染文件

#### Acceptance Criteria

1. WHEN 一次 `appendLines` 失败 THEN 文件 SHALL 截回写入前的长度（尽力而为），这个句柄 SHALL 关闭并丢弃，下一次写入重新 `open`（走 `repairTail`）（#13）。
2. `UnknownFact.raw()` SHALL 不被编码过程改写（编码用副本）（M55）。
3. 结果行解码时任何 RuntimeException SHALL 变成带行号的 `corrupt result line`（M57）。
4. 读文件的行缓冲 SHALL 有上限（64 MiB），超过视为损坏而不是 OOM（M65）。
5. `StoreLock.acquire` 在加锁前的任何失败 SHALL 关闭已打开的通道（M66）。
6. 语言表的写出顺序 SHALL 覆盖全部 `Language` 值：固定顺序之外的新值按枚举顺序跟在后面，测试保证没有值被丢（M54）。

### Requirement 4：驻留状态自洽

#### Acceptance Criteria

1. `Residency.pin` SHALL 一次返回状态和失败原因，通道不再第二次读时钟（M86）。
2. 失败且过了重试时间、钉住为零的条目 SHALL 被回收（M87）。
3. WHEN 载入后的发布被拒绝 THEN 这个会话 SHALL 回到 COLD，不留“已驻留但没合并”的状态（M88）。
4. WHEN 载入失败而事件属于已有运行 THEN 也 SHALL 报一条 `LOAD_FAILED` 的阶段事实，让界面看得到（M78）。

### Requirement 5：内核步骤对异常和重复结果的处理

#### Acceptance Criteria

1. WHEN 步骤函数抛异常而没有运行可以结束 THEN 内核 SHALL 报一条 `StepFailed` 事实（事件类型、异常类名），不再无痕返回（报告试扫 1）。
2. WHEN `CommandDropped` 的命令不是当前阶段等的命令 THEN 运行 SHALL 不变（M7）。
3. WHEN 一个 `ToolFinished` 对应的槽位已经不是 RUNNING THEN SHALL 忽略它（M16）。
4. WHEN 回复因 MAX_TOKENS 截断且带工具调用 THEN SHALL 不执行这些调用，运行按“截断”结束（M11）。
5. 排队的每条提问 SHALL 各自带自己的模型选择，开始运行时用自己的那份（#4）。
6. 第一次冻结上下文时开着的工具名单 SHALL 和全集求交（M9）。
7. 带 `continueText` 的工作 SHALL 把 `work` 交给 `callModel`（M14）。
8. 带附件的用户提问 SHALL 算“用户亲笔的文字消息”（M-试扫 Compactor.isUserText）。

### Requirement 6：异常文字不进 journal、SSE 和模型

#### Acceptance Criteria

1. 一个公共的 `Failures.describe(Throwable)` SHALL 只给出异常类名（和可选的固定说明），不含消息；`HookChain`、`UserLane.loadOne`、`ModelHandler`、`ToolHandler`、`OpenAiClient` 的失败文字 SHALL 都经它（试扫 3、#31、#32、#37、M94）。
2. 原始异常 SHALL 记在服务器日志里（由能记日志的那一层记）。
3. 连接程序发给服务器的工具失败 SHALL 是固定文字，本机细节只进本地 journal（#24）。

### Requirement 7：钩子说明按钩子比较

#### Acceptance Criteria

1. WHEN 只有一个钩子的说明变了 THEN 只有它的那一块 SHALL 重发（试扫 HookPatches 第 53 行）。

### Requirement 8：驻留和放行的小修

（并入第 2、4 条，见各自的验收条。）

### Requirement 9：阶段 1 的验证

#### Acceptance Criteria

1. 每条验收 SHALL 有对应的单元测试或集成测试，用脚本化模型。
2. 阶段末 SHALL 跑一次全量回归，并部署到 8091。

### Requirement 10：阶段 2 —— 模型客户端与流

#### Acceptance Criteria

1. 看门狗 SHALL 用 `AtomicReference` 持有流，并在流对象发布后检查是否已过期：过期就立即关闭（#19、#20）。
2. Claude 流：没见过的块类型 SHALL 不产生空文字块；没拿到签名的思考块 SHALL 丢弃而不是以空签名发回；孤立的 delta SHALL 不当文字（M99–M101）。
3. Claude 请求：全空白的文字块 SHALL 不发；空的工具结果 SHALL 用占位文字；思考块的字段缺失 SHALL 当作不发而不是抛异常（M102–M105）。
4. OpenAI 流：响应体 SHALL 关闭；200 之后流里的 `{"error":…}` SHALL 当作失败；没有 `index` 的并行调用 SHALL 按 id 分开（M107–M109）。
5. 错误体进 journal 前 SHALL 脱敏、截断（#33）。
6. `ClaudeClient` 的失败分类 SHALL 先看看门狗和中断（M98）。

### Requirement 11：阶段 3 —— 登录与安全加固

#### Acceptance Criteria

1. 改密码 SHALL 关掉别处登录的 SSE，三步在一个事务里（#35、M117、M33）。
2. `touch` 写失败 SHALL 不影响登录有效性；`sweep` 失败 SHALL 不阻断登录；Cookie 的 Max-Age SHALL 随每次 `touch` 刷新的时间重新发；登录 SHALL 有绝对上限（M29、M30、M32、M34）。
3. SSE 的任何退出 SHALL 完成 emitter（#23、M118、M119）。
4. 写操作没有 Origin 头时 SHALL 看 `Sec-Fetch-Site`，跨站拒绝；拒绝 SHALL 返回 JSON 错误码（#29）。
5. 登录和改密码的请求体 SHALL 先看 Content-Length 再读（#36）。
6. Argon2 SHALL 在锁外计算（#5）；最后一个管理员的检查 SHALL 是一条条件 UPDATE（#6）；用户表打开 SHALL 不漏连接（M36）。
7. `AddressRules` 的自端口守卫 SHALL 也检查内嵌的 IPv4；网卡枚举失败 SHALL fail-closed（#21、M110、M111）。
8. `Egress` SHALL 跨重试保留已读的头字节，头超时按总时长算，`close()` 和新连接不竞争（#22、M113、M114）。
9. `LocalLinks.attach` 的登记和 welcome 之间 SHALL 没有死连接窗口；到期定时器 SHALL 在登记前就位（#14、M71）。
10. 连接程序：`Link.stop` 和新会话的竞争、握手前的 `end`、重复调用 id、Error 处理、`context` 线程的错误处理 SHALL 修掉（#2、#3、M1、M2、M5）；`--allow-read` SHALL 走真实路径和“太宽”检查（#25）；非回环的 `ws://` SHALL 拒绝，除非显式 `--insecure`（#26）；`MxcPolicy` 的拒绝路径 SHALL 用真实路径（#27）。
11. 浏览器：`Browsers.close()` 和清扫线程 SHALL 对单个失败容错；`UserBrowser.open` SHALL 对任何异常关掉驱动（#10–#12、M42、M45）。
12. `Engine` 构造失败 SHALL 关掉已打开的一切，环启动后失败也一样；启动的 `interrupt()` SHALL 有上限并看环是否已坏（#7–#9）。
13. 密码 SHALL 有最短长度（8），`requestId` 和 `run` 路径变量 SHALL 校验格式（#38–#40、M116）；`LoginThrottle` 的淘汰 SHALL 先清过期（#28）；浏览器子进程的环境 SHALL 用允许名单（#30）。

### Requirement 12：阶段 4 —— 去重与归位（报告 2.1、2.2、2.6）

#### Acceptance Criteria

1. “不是用户写的”前缀和 system 更新的判定 SHALL 只在 value 里有一份，内核和 shell 共用。
2. `ModelChanges.fold` 和 `HookTexts.fold` SHALL 合成一个折叠器。
3. 工具 SHALL 拆成描述（名字、服务端、并发）和实现，`KernelFactory.safety` 不再用 null 依赖造对象。
4. `HookChain` SHALL 不再持有 `required`，只信会话里记的名单。
5. `HookDecided` SHALL 有自己的 `question` 字段；`Compacting.patch` SHALL 删除（读旧行时忽略）。
6. `KernelHook` SHALL 拆成回调接口和 `PluginInfo`。
7. `PluginGate.HOOK_POINTS` SHALL 从 `KernelHook` 的方法派生（M24）；after-tool 钩子抛异常 SHALL 按 `failClosed` 处理（M25）。
8. `compactionAllowed` SHALL 并进 `RunState`；`CompactSteps.notAdopted` SHALL 按原因拆开（M8、M10）；`Fetcher` 的两个布尔 SHALL 变成一个结果类型（M47）。

### Requirement 13：阶段 5 —— 拆大类与清注释（报告 2.4、2.5）

#### Acceptance Criteria

1. `RecordCodec` SHALL 拆成四个包内类，`RecordCodec` 只编排；期望文件逐字节不变。
2. `Engine` 的装配 SHALL 分成四步，各返回一个记录。
3. `RunState` SHALL 按阶段分组。
4. 主代码里的 spec 编号注释 SHALL 逐包换成规则本身。

## Non-Functional Requirements

- 旧 journal 行逐字节不变；请求期望文件逐字节不变（阶段 2 只新增段）。
- 不用真实模型。
- 每个阶段一次全量回归。
