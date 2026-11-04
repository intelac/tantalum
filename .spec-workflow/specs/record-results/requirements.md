# Requirements Document

## Introduction

现在会话文件里只存输入（用户消息、模型回复、工具结果），每一步的结果是怎么得来的没有记。重启后打开会话、会话被移出内存后再打开，以及查执行轨迹、查请求详情、断线补读时，都要把文件从第一个事件重放一遍，**用当下这版内核和钩子重新算一次**：压缩时保留了哪几条、钩子补了什么、钩子放没放行、运行到了哪一步。

代码没变时，重放结果和当时逐字节相同（`EvictionInvarianceTest`、`WireFormatTest`）。代码一变就不保证了，而且算错了也不会报错。例子：compaction-threshold 把估算从“字节 ÷ 3”改成“字节 ÷ 1.5”，如果没给旧会话留 `LEGACY_BYTES_PER_TOKEN = 3`，压缩时保留了两个文件的旧会话，重启后只会保留一个，模型就看不到另一个文件了。这类兼容代码现在已经有好几处（`CompactionSettings`、`ContextAssembled.enabledTools/enabledPlugins`、`SessionReplay` 注入用量、`StoreFormat.semantics` 的警告），全靠人记得加。

本 spec 改成“记结果”：内核每处理完一步，把这一步的结果写成一行，紧跟在会话文件里；落盘之后才去调模型、执行工具。重启和各种查询都只读这些结果行，不再调内核和钩子，以后改代码不会改动过去。内核本身不改，它照样负责做每一个新决定；重放留下来做检查工具：用新代码重放旧会话，和结果行比对，就能知道哪里的行为变了。

Yi 2026-11-04 确认这个方向（“好 那我们把这个实现了”），不做成插件（结果行要记下插件的输出；写入时机在内核和执行之间，插件插不进去；插件可以关，恢复不能关）。

参照 pi-mono 的 `main`（0096774）和 DSH 的 `main`（ce7073d5）：

- **pi** 存的就是结果：每条消息按原样存一行（`message`），插件加的内容存成 `custom_message`、改写存成 `context_edit`，压缩存摘要和 `firstKeptEntryId`。恢复时从最新一次压缩开始照着拼，不重新计算（`CA/src/core/session-manager.ts:439-512`、`544-574`、`1350-1409`）。每行写完用 `appendFileSync`，没有 fsync（`:1188`）。助手消息流式结束后才写，崩溃时半截回复丢弃（`agent/src/agent-loop.ts:433-477`）。没有结果的工具调用不改文件，发请求时临时补“No result provided”（`AI/src/api/transform-messages.ts:179-253`）。用量记在各条消息上，载入时全部加一遍（`CA/src/core/usage-totals.ts:22-28`）。格式版本 3，迁移在内存里做完后重写整个文件（`session-manager.ts:41`、`287-331`、`1093-1135`）。
- **DSH** 存事件日志，但只有带 `surfaceOp`（追加，或替换某段序号）的 5 种事件会进模型上下文，内容按原样存。恢复时从序号 0 读起做纯计算的折叠，不重跑钩子（`core/session/src/types.ts:287-523`、`index.ts:647-667`、`999-1020`）。调模型前、执行顶层工具前各落盘一次，落盘失败就不执行（`session-checkpoint-policy/src/index.ts:29-82`）。重启时给没有结果的工具调用补一条结果：调用已开始的标 `TOOL_OUTCOME_UNKNOWN`（“结果未知，有副作用的不要盲目重试”），没开始的标 `TOOL_NOT_STARTED`，再补一条“这一轮已中断”（`core/session/src/repair.ts:32-41`、`88-97`、`157-197`）。钩子的决定存成只记录、不进上下文的 `hook/result`（`docs/persistence-catalog.md:595-612`）。但插件的部分投影只存决定、每次折叠时由插件代码重算，所以插件改版仍会改变旧会话（`compaction-image-offload/src/projection.ts:25-73`）。格式版本 4，每一版一个新文件，逐版迁移，校验后再发布，源文件不改；遇到不认识且没标 `ignorable` 的事件，整个会话拒绝载入（`session-format/src/chain.ts:24-41`、`session-persistence-jsonl/src/generation.ts:492-549`、`817-843`、`session-persistence/src/storage-contract.ts:55-80`）。

我们和两家的异同：
- 和 pi 一样记结果、恢复时不重算。
- 和 DSH 一样先落盘再执行，没有结果的工具调用照 DSH 分“结果未知”“没发出”两种（我们的 `ToolPairing` 已经是这样）。
- 比 DSH 更进一步：插件的输出也按原样记，插件改版不影响旧会话。

路径写法：pi 的 `CA` 指 `packages/coding-agent`，`AI` 指 `packages/ai`，`agent` 指 `packages/agent`；DSH 的路径相对于 `packages/`；两边都没有对应做法的标“自定”。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| 内核每一步写一行结果，落盘后才执行命令、推给画面 | 改内核的转移逻辑（内核不改） |
| 重启、冷载入、索引追赶、断线补读、对话页、执行轨迹页、请求详情都只读结果行 | 快照（结果行已经让恢复不重算；11 月 3 日去掉的快照不再加回） |
| 每次模型调用记下请求指纹，请求详情还原后比对 | 存每次请求的完整正文（每次都是整段历史，会让文件按平方增长） |
| 插件自定义的判断类型可以存、可以读 | pi 的会话树和分叉（另一个功能） |
| 遇到旧格式的会话文件拒绝启动，提示先移走 | 旧会话迁移（Yi 2026-11-04：“把所以原来的session都删掉”） |
| | 压缩会话文件（zstd） |
| 分三步上线：只写不用并比对 → 改用结果行 → 清理 | 改变重启时收尾的行为（照现在的 `RunInterrupted`） |
| 重放留作检查工具：用新代码重放旧会话，和结果行比对 | |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 |
|---|---|---|
| R1 | 结果行写什么 | 只写这一步**决定了的东西**：会话状态的变化（追加了哪些消息、压缩换了哪一段、排队消息、当前运行等字段，只写变了的）；还没结束的运行的变化（重启收尾要用）；这一步的判断（`Fact`：阶段变化、压缩、钩子的决定和说明、省略的图片）；这一步发出的命令（模型调用只记钩子补丁和请求指纹，不记整份请求）；这一步的用量。pi 存消息和插件内容（`session-manager.ts:183-194`）；DSH 存带 `surfaceOp` 的消息事件和只记录的 `hook/result`（`types.ts:287-523`）。“只写变了的字段”自定 |
| R2 | 正文存几份 | 只存一份。追加的消息如果就是事件行里的原文（模型回复、工具结果），结果行只写“来自本事件”，不再抄一遍；被裁剪、被钩子改过的才按原样写。pi 和 DSH 都按原样再存一份（pi `session-manager.ts:1215-1225`；DSH 的回复同时存 `message` 和原始 `stream`，`types.ts:349-356`）。我们的事件行本来就存了原文，再抄一遍会让文件翻倍。11 月 3 日去掉快照的原因之一就是重复存储，所以自定 |
| R3 | 落盘顺序 | 结果行落盘之后，才执行这一步的命令（调模型、执行工具、插件工作），才把变化推给画面。计算的线程（`Cleanroom`）照旧不做 I/O：它把结果交给这个用户的写入通道，通道落盘后放行命令。DSH 在调模型前、执行工具前落盘，失败就不执行（`session-checkpoint-policy/src/index.ts:29-82`）；pi 不 fsync（`session-manager.ts:1188`）。我们照 DSH |
| R4 | 恢复读什么 | 只读事件行和结果行，不调内核和钩子：重启后的状态、冷载入、索引追赶（`IndexCatchUp`）、断线补读、对话页、执行轨迹页、请求详情都一样。对话条目和轨迹记录由“事件 + 结果行”算出：画面的排版代码照常运行，但它用的决定来自结果行，不重算。pi 只拼消息（`session-manager.ts:544-574`）；DSH 做纯计算的折叠（`session/src/index.ts:647-667`）。“画面排版照常运行”同 DSH 的显示折叠 |
| R5 | 有事件行、没有结果行 | 说明内核还没处理完这一步就崩溃了。因为落盘之前不执行命令，这一步没有产生任何副作用。重启时把这些事件按序号交给内核，当作第一次处理（命令不执行），写出结果行。这和现在启动时的行为相同，只是范围从“整个文件”缩小到“最后没处理完的几个事件”。用户已经提交的消息不会丢。自定（pi 和 DSH 在执行前没有“已收到、未处理”这一状态） |
| R6 | 重启时没跑完的运行 | 照现在的规则收尾（`RunInterrupted` → `CancelSteps.interrupted` → `RunEnd.finish`），行为不变。已发出、没有结果的工具调用记为“结果未知”，没发出的记为“没有执行”（`ToolPairing`）；模型还没看到的插话追加进历史；排队的消息照旧开新运行。收尾用到的运行状态来自结果行。照 DSH `repair.ts:32-41`、`157-197`（我们已经是这两种） |
| R7 | 请求指纹 | 每次调模型时，算出发出的请求（system、工具、消息、输出约束）的 SHA-256，记进结果行。请求详情页用结果行还原请求后再算一次，不一致时页面上标明“与当时发出的请求不完全一致”。两边都没有，自定 |
| R8 | 插件自己定义的判断类型 | 插件注册自己类型的读写方式，按类型名和版本号存。读到不认识的插件类型（插件卸载了或降级了）时，原样保留 JSON，执行轨迹里显示“未知的插件记录（类型名）”，会话照常载入：这类记录只用于显示，不影响上下文。不认识的**状态**记录（结果行里会话、运行的部分）照现在的规则拒绝载入这个会话（`SessionCorruptException`）。DSH 插件记录标 `ignorable`（`core/session/src/index.ts:850-865`），不认识的非 `ignorable` 记录整个会话拒绝载入（`storage-contract.ts:55-80`）；pi 不认识的保留、不进上下文（`session-manager.ts:465`）。我们照 DSH 的分法 |
| R9 | 旧会话 | **不迁移**（Yi 2026-11-04：“把所以原来的session都删掉”；8091 原有的 4 个会话已移到 `~/.local/share/tantalum-sessions-backup-2026-11-04/`）。新版本启动时，如果数据目录的 `format.json` 是 `storage = 1` 且 `sessions/` 下有会话文件，拒绝启动，提示先移走 `sessions/` 和 `index.db`；没有会话文件时直接改成 `storage = 2`。DSH 逐版迁移（`session-format/src/chain.ts:24-41`）、pi 在内存里迁移后重写（`session-manager.ts:1093-1135`），我们现在只有一个用户、4 个会话，不值得为它写迁移 |
| R10 | 格式版本 | 会话文件头 `SessionFile.FORMAT` 1 → 2，数据目录 `format.json` 的 `storage` 1 → 2。旧程序遇到新数据照现有规则拒绝启动（`StoreFormat.check`）。`semantics` 版本的警告不再需要，删掉。照 DSH 的单一版本号（`types.ts:68-90`） |
| R11 | 分三步上线，每步都能单独停 | **第 1 步，只写不用**：照常用重放恢复，同时写结果行。冷载入时两边各算一次，结果不一致就记错误日志，并在控制台的会话上标出。**第 2 步，改用结果行**：恢复和各个查询都改读结果行。**第 3 步，清理**：删掉按重放恢复的代码，重放只留作检查工具（测试和命令行回归检查）。照片里的方案（Yi 2026-11-04 提供） |
| R12 | 回归检查工具 | 一个命令行入口：用当前代码重放数据目录里的会话，和结果行比对，列出第一处不一致（会话、序号、哪个字段）。不改数据。用来在部署前检查改代码会不会改变旧会话的行为。两边都没有，自定 |

## Requirements

### Requirement 1：每一步写一行结果

**User Story:** 作为维护者，我希望内核每一步的决定都按原样记进会话文件，以便以后改代码时不用为旧会话加兼容。

出处：pi `CA/src/core/session-manager.ts:183-194`、`1215-1225`；DSH `core/session/src/types.ts:287-523`、`docs/persistence-catalog.md:595-612`。

#### Acceptance Criteria

1. WHEN 内核处理完一个事件 THEN 服务器 SHALL 在这个会话的文件里追加一行结果，写明它对应的事件序号。
2. WHEN 这一步改变了会话状态 THEN 结果行 SHALL 只写变了的部分：追加的消息、压缩的替换段和保留起点、排队消息、当前运行、冻结的上下文、用量锚点、省略的图片。
3. WHEN 追加的消息和事件行里的原文相同 THEN 结果行 SHALL 只写“来自本事件”，不抄正文。被裁剪、被钩子改过的消息 SHALL 按原样写。
4. WHEN 这一步改变了还没结束的运行 THEN 结果行 SHALL 写下这个运行的新状态；运行结束后 SHALL 只写“已结束”和原因。
5. WHEN 这一步产生了判断（`Fact`） THEN 结果行 SHALL 按产生的顺序写下全部判断，包括钩子的决定和说明。
6. WHEN 这一步发出了命令 THEN 结果行 SHALL 写下每条命令。模型调用只写钩子补丁（追加的 system、追加的工具）、省略的图片和请求指纹，不写整份请求。
7. WHEN 这一步没有改变任何东西 THEN 结果行 SHALL 仍然写出，内容为空，好让读的人知道这一步已处理。
8. IF 用 50 万事件的语料测量 THEN 加上结果行之后，会话文件的总大小 SHALL 比现在增加不超过 30%。

### Requirement 2：结果行落盘后才执行

**User Story:** 作为用户，我希望服务器做过的每件事在文件里都有记录，以便崩溃重启后不会出现“工具已经执行、文件里却没有”的情况。

出处：DSH `session/session-checkpoint-policy/src/index.ts:29-82`；pi `CA/src/core/session-manager.ts:1188`（不 fsync，不照它）。

#### Acceptance Criteria

1. WHEN 一步的结果行还没落盘 THEN 服务器 SHALL NOT 执行这一步的命令，也 SHALL NOT 把这一步的变化推给画面。
2. WHEN 结果行落盘失败 THEN 服务器 SHALL 照现在事件写入失败的规则停止处理事件，不执行这一步的命令。
3. 计算线程 SHALL 照旧不做 I/O：落盘由这个用户的写入通道做，不同用户互不等待。
4. IF 用 `LaneBench` 测量（100 个会话、10 个用户、每会话每秒 1 个事件） THEN 从事件进入输入环到命令放行，P99 SHALL 不超过 50 ms（现有预算）。

### Requirement 3：恢复只读结果行

**User Story:** 作为用户，我希望重启后打开旧会话，模型看到的历史和画面都和重启前一模一样，即使中间服务器换了新版本。

出处：pi `CA/src/core/session-manager.ts:476-512`、`544-574`；DSH `core/session/src/index.ts:647-667`、`999-1020`。

#### Acceptance Criteria

1. WHEN 服务器重启、冷载入会话、补齐索引、断线补读、打开对话页、执行轨迹页或请求详情 THEN 服务器 SHALL 只读事件行和结果行，SHALL NOT 调用内核和钩子（需求 5 的未处理事件除外）。
2. WHEN 一个会话处理到某一步时进程崩溃，重启后再打开 THEN 恢复出的会话状态、运行状态、对话条目、轨迹记录 SHALL 和崩溃前内存里的逐字节相同。
3. WHEN 换了一个钩子插件的规则（例如只读钩子的放行范围）后重启 THEN 旧会话的历史、对话页、执行轨迹 SHALL 不变；新规则只作用于之后的步骤。
4. WHEN 打开对话页和执行轨迹页 THEN 画面 SHALL 由事件行和结果行里的判断排版得出；排版代码改了，旧会话的显示可以跟着变，但显示的决定 SHALL 来自结果行。
5. WHEN 用户的用量需要合计（额度钩子、用量页） THEN 服务器 SHALL 从结果行里的用量合计，不重放。
6. IF 用 `ReadBench` 测量 50 万事件的语料 THEN 打开长会话首屏（冷载入）SHALL 不比现在慢，启动 SHALL 仍在 5 s 预算内。

### Requirement 4：请求指纹

**User Story:** 作为排查问题的人，我希望请求详情页显示的就是当时发出的请求，如果还原得不一样，页面要告诉我。

出处：自定（pi、DSH 都没有）。

#### Acceptance Criteria

1. WHEN 服务器调模型 THEN 结果行 SHALL 记下这次请求的 SHA-256：按固定的规则对 system、工具、消息、输出约束编码后计算。
2. WHEN 请求详情页还原一次请求 THEN 服务器 SHALL 再算一次指纹并和记录比较；不一致时 SHALL 在页面上标明“与当时发出的请求不完全一致”，并照常显示还原的内容。
3. 指纹 SHALL NOT 包含 API key、请求头等任何凭据。

### Requirement 5：未处理完的事件

**User Story:** 作为用户，我希望服务器崩溃前我已经提交的消息，重启后不会丢。

出处：自定。

#### Acceptance Criteria

1. WHEN 重启时某些事件有事件行、没有结果行 THEN 服务器 SHALL 先用结果行恢复状态，再把这些事件按序号交给内核处理，写出结果行，这一步的命令不执行。
2. WHEN 上一条的处理结束 THEN 服务器 SHALL 照需求 6 收尾还没结束的运行。
3. 这条路径 SHALL 只用于从未处理完的事件，不重算已经有结果行的事件。

### Requirement 6：重启收尾不变

**User Story:** 作为用户，我希望重启时正在跑的那一轮照现在的方式结束，不会重复执行可能已经生效的工具。

出处：DSH `core/session/src/repair.ts:32-41`、`88-97`、`157-197`；我们的 `ToolPairing`、`CancelSteps.interrupted`、`RunEnd.finish`。

#### Acceptance Criteria

1. WHEN 重启时有还没结束的运行 THEN 服务器 SHALL 照现在发 `RunInterrupted` 结束它，运行状态来自结果行。
2. WHEN 运行里有已发出、没有结果的工具调用 THEN 服务器 SHALL 补一条“结果未知”的结果（`TOOL_OUTCOME_UNKNOWN`）；没发出的 SHALL 补“没有执行”（`ABORTED_BEFORE_DISPATCH`）。
3. WHEN 运行里有模型还没看到的插话 THEN 服务器 SHALL 把它追加进历史；有排队消息时 SHALL 照旧开新运行。

### Requirement 7：插件自己定义的判断类型

**User Story:** 作为插件作者，我希望我的插件产生的记录能存下来、读回去；插件卸载以后，旧会话也照样能打开。

出处：DSH `core/session/src/index.ts:850-865`、`session-persistence/src/storage-contract.ts:55-80`；pi `CA/src/core/session-manager.ts:465`。

#### Acceptance Criteria

1. WHEN 插件定义了自己的判断类型 THEN 插件 SHALL 能注册它的类型名、版本号和读写方式；README 的插件说明 SHALL 写明怎么注册。
2. WHEN 读到不认识的插件类型 THEN 服务器 SHALL 原样保留它的 JSON，在执行轨迹里显示“未知的插件记录（类型名）”，会话照常载入。
3. WHEN 读到不认识的状态记录或内置类型 THEN 服务器 SHALL 拒绝载入这个会话，错误信息写明序号和类型名（同现在的 `SessionCorruptException`）。

### Requirement 8：旧格式的数据目录

**User Story:** 作为部署的人，我希望新版本遇到旧格式的会话文件时明确拒绝，而不是读错。

出处：Yi 2026-11-04（“把所以原来的session都删掉”）；拒绝规则同现在的 `StoreFormat.check`。

#### Acceptance Criteria

1. WHEN 数据目录的 `storage` 是 1、`sessions/` 下有会话文件 THEN 服务器 SHALL 拒绝启动，错误信息写明要先移走 `sessions/` 和 `index.db`，不改动任何文件。
2. WHEN `storage` 是 1、没有会话文件 THEN 服务器 SHALL 把 `storage` 改成 2，正常启动；用户库、插件开关、用户设置不受影响。

### Requirement 9：分三步上线

**User Story:** 作为 Yi，我希望这次改动能分步上线，每一步出问题都能停在那一步，不影响现在能用的功能。

出处：Yi 2026-11-04 提供的方案（三步走）。

#### Acceptance Criteria

1. **第 1 步：只写不用。** 服务器 SHALL 写结果行，恢复照旧用重放。冷载入时 SHALL 两边各算一次并比较，不一致时记错误日志，并在控制台会话列表上标出这个会话。
2. **第 2 步：改用结果行。** 需求 3 的各个入口 SHALL 改读结果行；第 1 步的比较 SHALL 改成配置开关，默认关。
3. **第 3 步：清理。** 按重放恢复的代码 SHALL 删掉；`Replay`、`SessionReplay` 只留给测试和需求 10 的检查工具使用。
4. 每一步结束时，完整回归（value、kernel、shell、examples、JarCrashIT、vitest、Playwright）SHALL 全部通过，8091 SHALL 更新到这一步。

### Requirement 10：回归检查工具

**User Story:** 作为维护者，我希望部署前能检查新代码会不会改变旧会话的行为。

出处：自定。

#### Acceptance Criteria

1. WHEN 维护者对一个数据目录运行检查命令 THEN 工具 SHALL 用当前代码重放每个会话，和结果行逐步比较，列出每个不一致会话的第一处差异：会话、序号、哪个字段。
2. 工具 SHALL 只读，不改数据目录里的任何文件。
3. WHEN 全部一致 THEN 工具 SHALL 以状态 0 退出；有不一致时 SHALL 以状态 1 退出。

### Requirement 11：验证

**User Story:** 作为 Yi，我希望这次骨架改动有足够的测试证明它没有改坏东西。

出处：Yi 2026-11-04 提供的方案（验证部分）。

#### Acceptance Criteria

1. 测试 SHALL 在场景的每一步之后模拟崩溃：只读结果行恢复出的状态和条目，必须和崩溃前内存里的逐字节相同（扩展 `EvictionInvarianceTest`）。
2. 测试 SHALL 证明“插件规则改了，旧对话不变”：同一个会话文件，用两套钩子规则分别恢复，结果相同；只有之后的新步骤不同。
3. 测试 SHALL 覆盖需求 5（崩溃在结果行落盘前）和需求 8（旧格式拒绝启动、空目录升级）。
4. 端到端测试 SHALL 覆盖三个场景：改了访问范围的钩子规则后重启，旧对话不变；执行工具时重启，工具记为“结果未知”；重启前后执行轨迹页显示一致。
5. 性能 SHALL 用 `LaneBench`、`ReadBench` 实测，结果写进 `steering/tech.md`，并和需求 1.8、2.4、3.6 的预算比较。

## Non-Functional Requirements

### Code Architecture and Modularity
- 结果行的编码放在 `shell/journal`，和 `EventCodec` 同一层；内核和 value 模块不知道文件格式。
- 内核（`tantalum-kernel`）的转移逻辑不改。允许在 value 模块增加结果行需要的值类型。
- 类名简单直白，例如 `StepRecord`、`RecordCodec`、`RecordReader`。

### Performance
- 写路径：P99 ≤ 50 ms（需求 2.4）。
- 冷载入、启动：不比现在慢，启动 ≤ 5 s（需求 3.6）。
- 文件大小：增加 ≤ 30%（需求 1.8）。

### Security
- 结果行和指纹 SHALL NOT 含 API key 或任何凭据，规则同现在的 journal。

### Reliability
- 落盘失败时不执行命令（需求 2.2）。
- 旧格式的会话文件拒绝启动，不猜（需求 8）。
- 不认识的状态记录拒绝载入，不猜（需求 7.3）。

### Usability
- 控制台的变化只有两处：第 1 步标出比较不一致的会话（需求 9.1），请求详情页的“不完全一致”提示（需求 4.2）。
