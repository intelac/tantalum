# Design Document

## Overview

内核每处理完一个事件，cleanroom 把这一步“决定了什么”整理成一个值 `StepRecord`，交给这个用户的写入通道；通道把它编码成一行追加到会话文件，落盘之后才放行这一步的命令和推送。恢复时把文件里的事件行和结果行按序号配对，用结果行“套”出每一步的新状态、命令和判断。这个过程叫**折叠**，替换掉现在 `SessionReplay` 里的 `kernel.step`；对话条目和轨迹记录的排版代码（`Projections`）照常运行，输入和现在完全相同，只是来自结果行。

一句话：**`kernel.step(before, event)` 换成 `record.apply(before)`，其余的恢复和查询代码不变。**

```
写：  事件 ─► 通道落盘 ─► cleanroom: kernel.step ─► StepRecord.of(before, decision)
                                                     │
                       通道：编码、追加、fsync ◄────────┘
                                │
                     放行命令（输出环）、推送（SSE）

读：  文件 ─► 事件行 + 结果行（按序号配对）─► record.apply(before) = Decision ─► Projections / IndexChange / 查询
      （只有没有结果行的尾部事件才交给内核，见“尾部”）
```

## Steering Document Alignment

### Technical Standards (tech.md)
- 内核仍是纯函数，只在 cleanroom 线程运行，cleanroom 仍不做 I/O（tech.md “线程模型”）。
- 会话文件仍是一会话一个只追加的 JSONL，落盘仍用 `FileChannel.force`，写入失败仍让进程停止处理事件（`UserLane.Exit.failed`）。
- 结果行和事件行一样不含凭据（tech.md 安全约定）。

### Project Structure (structure.md)
- 值类型 `StepRecord` 放在 `tantalum-value` 的 `state` 包，它只引用已有的值类型（`SessionState`、`RunState`、`Command`、`Fact`、`TokenUsage`）。
- 编码和读文件放在 `shell/journal`：`RecordCodec`（和 `EventCodec` 并列）、`SessionFileReader` 扩展为读两种行。
- 折叠放在 `shell/store`：`RecordReplay`，和 `SessionReplay` 并列。
- 检查工具放在 `shell/boot`：`RecordCheck`。

## Code Reuse Analysis

### Existing Components to Leverage
- **`SessionReplay`**：保留它的结构（逐行读、每一步回调 `Step.accept(envelope, before, decision)`），把“这一步怎么算”抽成一个参数：现在是内核，新的是结果行。`TraceQuery`、`TurnQuery`、`ColdReads`、加载器都经过它，所以这些入口不用各改一遍。
- **`Projections`、`TranscriptPass`、`TracePass`**：不改。它们要的 `(envelope, before, decision)` 由折叠给出。
- **`IndexChange.of(envelope, before, after)`**：不改。折叠给出 `before`、`after`；用量的差值来自结果行。
- **`IndexCatchUp.replayUser`**：它“按用户合并文件、按序号排序、注入累计用量后交给内核”的写法，正好用来处理尾部事件。
- **`SessionFile.append`、`SessionFiles`**：追加结果行和追加事件共用同一个通道、同一次 fsync。
- **`UserLane`**：已经是“取号、写、落盘后放行”的结构，加一种工作项“结果行”即可。
- **`EvictionInvarianceTest`**：已经在比较“从不回收”和“每步回收”逐字节相同，扩展成“每步崩溃后只读结果行恢复”。

### Integration Points
- **`Cleanroom.step`**：多做一件事，算出 `StepRecord`；命令和推送不再直接发出，改为交给通道。
- **`Engine` 启动**：`catchUp` 之前先补写尾部的结果行，之后照旧 `interrupt(unfinished)`。
- **`StoreFormat`**：`storage` 改为 2，删掉 `semantics`。
- **控制台**：会话列表的“比对不一致”标记（第 1 步），请求详情的“不完全一致”提示（第 2 步）。

## Architecture

### 1. 结果行的内容（R1、需求 1）

```java
/** 内核处理一个事件的结果。只含变了的部分；恢复时 apply 到处理前的状态上，得到处理后的状态。 */
public record StepRecord(long seq,
                         List<SessionChange> sessions,   // 变了的会话
                         List<RunState> runs,            // 变了的运行，按原样
                         List<RunId> removedRuns,        // 从状态里移走的运行
                         TokenUsage usage, LocalDate usageDay,  // 这一步给这个用户加的用量，没有为 null
                         List<Command> commands,
                         List<Fact> facts) {}

/** 一个会话的变化。messages 只追加，所以只记新增的；其余字段变了才记（Optional.empty 表示没变，值为 null 表示变成 null）。 */
public record SessionChange(SessionId id, Principal principal, boolean created,
                            List<Message> appended,
                            Optional<RunId> activeRun, Optional<RunId> lastRun, Optional<List<Message>> followUps,
                            Optional<SessionContext> context, Optional<Compaction> compaction,
                            Optional<UsageAnchor> anchor, Optional<List<String>> omittedImages) {}
```

- **怎么算出来**：`StepRecord.of(before, decision)` 逐个比较 `before` 和 `decision.newState()` 的会话表、运行表和这个用户的用量，用 `==` 先判断（内核没碰的对象是同一个引用），不同时才用 `equals`。`messages` 新状态必须以旧状态为前缀（`SessionState` 的约定是只追加）；不是的话抛异常，按 cleanroom 现有规则停止处理（这说明内核违反了约定，不能悄悄写错）。
- **怎么套回去**：`record.apply(before)` 返回 `Decision(after, commands, facts)`，`after` 用 `before` 的对象加上变化重建。测试断言 `apply(before)` 和实时的 `newState` 相等（见“测试”）。
- **运行**：变了就按原样记整个 `RunState`。结束的运行没有工具轮和插话，只剩阶段和原因，很小，所以不再特别处理（需求 1.4 的“只写已结束和原因”自然满足）。
- **用量**：记这一步的**差值**，不是累计值：一个文件只含一个会话，累计值跨会话，单个文件折叠不出来。
- **只改事件所在的会话**：核对过，内核每一步只通过 `Outcome.session(run.ref())` 读写会话，运行由事件的 `run` 或会话的 `activeRun` 找到，都属于事件所在的会话；`OwnerRef.OfCommand` 只在编码里登记，内核没有用到（`Outcome.java:64-102`、`Kernel.findRun`）。所以 `sessions` 最多一项，就是事件所在的会话。`StepRecord.of` 把这一点当作约定来检查：改到别的会话就抛异常，和“消息只追加”一样，不悄悄写错。为了以后可能的跨会话运行，`sessions` 仍定义为列表。

### 2. 正文只存一份（R2、需求 1.3、1.8）

结果行由 `RecordCodec` 编码，编码时做两件事：

**字符串引用。** 结果行里长度 ≥ 256 字节（UTF-8）的字符串，如果同一个文件前面已经出现过（事件行或结果行），就写成 `"\u0000#" + SHA-256 十六进制`（66 个字符），不再抄一遍。模型回复的文字和思考签名、工具结果的正文、工具调用参数里的长字符串、system 都在其中。字面上以 `\u0000` 开头的普通字符串写成多加一个 `\u0000`，解码时去掉，所以不会和引用混淆。
- 写入方为每个常驻会话记一份“这个文件里已有哪些长字符串”的哈希集合（`RecordMemo`）。冷载入读文件时顺便建好；不知道时就按原样写，只是文件大一点，正确性不依赖它。
- 读取方读每一行时，把长字符串按哈希放进表里，遇到引用就查表。只对结果行解引用，事件行照旧由 `EventCodec` 读。
- 引用只指向**更早的行**，所以顺序读一遍就能全部解开。

**请求只记相对上一次的变化。** 模型调用命令 `CallModel` 里的请求是整段历史，不能每次都记。命令编码成：
```json
{"prompt": {"system": "same" | "<字符串>", "tools": "same" | [...],
            "keep": 12, "add": [<消息>...], "output": {...}},
 "fingerprint": "<SHA-256>"}
```
- `keep` 是和**这个会话上一次请求**相同的前几条消息，`add` 是之后的消息。我们的请求本来就按 prompt 缓存的要求保持前缀稳定，所以通常 `add` 只有这一轮新加的一两条。压缩之后前缀会变，`keep` 变小，那一次记得多一些。
- 上一次的请求也放在 `RecordMemo` 里，读取时按文件顺序跟踪。不知道上一次时 `keep` 为 0，按原样写。
- 不复现 `PromptProjection`、`ImageBudget` 的逻辑：请求里的消息是工具变化挪过位置、省略过图片的结果，这里记的是最后实际发出的那份，只是写成相对上一次的变化。

**指纹（R7、需求 4）。** `fingerprint` 是用 `EventCodec` 的 mapper 编码 `Prompt` 后算的 SHA-256。请求详情页还原请求后再算一次，不同时显示提示。指纹只覆盖 `Prompt`（system、工具、消息、输出约束），不含请求头和凭据。

### 3. 落盘之后才放行（R3、需求 2）

```
Cleanroom（单线程，不做 I/O）
  decision = kernel.step(before, e)
  state = decision.newState(); view.publish(state)            // 内存视图照旧立即更新
  projected = projections.apply(e, before, decision)          // 内存里的条目、轨迹照旧立即更新
  record = StepRecord.of(before, decision)
  lanes.submit(e.principal(), new Released(record, decision.commands(), notifications))

UserLane（每个用户一个虚拟线程）
  取走队列里的全部工作项：事件和结果行
  按会话分组追加，每个文件 fsync 一次                           // 事件和结果行同一批落盘
  事件：照旧放进 cleanroom 环
  结果行：放行命令（输出环），再按 (seq, index) 推送事实、条目、轨迹记录
```

- **为什么由通道写**：一个用户的文件只有他的通道在写（`SessionFile` 要求同一文件同时只有一个写入者），结果行和事件行走同一个通道，就不会有两个写入者。cleanroom 只是把工作项放进队列，不等待。
- **文件里的顺序**：通道按队列顺序写。如果第 N 步的结果行还在排队时，用户又发了一个插话（第 N+1 个事件），文件里可能是“事件 N、事件 N+1、结果 N”。所以结果行按序号配对，不要求紧跟在事件后面。同一个会话的结果行一定按序号递增（cleanroom 对同一用户按序号处理）。
- **输出环满**：现在 cleanroom 在输出环满时调 `ResultPublisher.reportDropped`。这个逻辑随放行一起挪到通道里，行为不变。
- **延迟**：每一步多一次 fsync。同一批里的事件和结果行合并成一次。用 `LaneBench` 测“从事件进入输入环到命令放行”，预算 P99 ≤ 50 ms（需求 2.4）。

### 4. 读文件和折叠（R4、需求 3）

`SessionFileReader.forEach` 改为回调两种行：`Line.Event(envelope)`、`Line.Result(record)`。结果行顶层有 `"result": <seq>`，事件行有 `"seq"`，靠这个区分。事件序号仍要求递增；结果行序号也要求递增，并且必须对应一个已经读到的事件，否则抛 `SessionCorruptException`。

`RecordReplay` 实现和 `SessionReplay` 相同的回调：
```
对每一行：
  事件行 → 放进“待配对”
  结果行 → 取出对应事件；decision = record.apply(state)；step.accept(envelope, state, decision)；state = decision.newState()
读完：还在“待配对”里的就是尾部（见下一节）
```
- 冷载入（`UserLane.Loader`）、`ColdReads`、`TraceQuery`、`TurnQuery`（`/turns` 和 SSE 补读）都把 `SessionReplay` 换成 `RecordReplay`，回调不变。
- `IndexCatchUp` 对大小不符的文件也改用折叠：`IndexChange.of(envelope, before, after)` 不变，用量来自结果行的差值，不再需要“按用户合并、注入累计用量后交给内核”。
- 折叠不注入累计用量：只有钩子需要它，折叠不跑钩子（核对过 `shell/trace`、`shell/conversation` 不读用户用量）。
- 显示代码里用到的内核纯函数（`SummaryPrompt.text`、`ContextDiff` 等）照常调用，它们是排版，不是决定（需求 3.4）。

### 5. 尾部：补写结果行（R5、需求 5）

崩溃时最后几个事件可能已经落盘、结果行还没写。启动时 `Backfill` 在 `IndexCatchUp` 之前运行：
1. 只看索引里大小不符的文件（没崩溃时文件大小和索引一致），找出有未配对事件的。
2. 按用户把这些事件合并、按序号排序。每个会话先用自己已有的结果行折叠出状态，再像 `IndexCatchUp.replayUser` 现在那样注入累计用量，把事件逐个交给内核，**命令不执行**。
3. 每一步的 `StepRecord` 直接追加到对应文件，每个文件 fsync 一次。

补写到一半崩溃也没关系：每一行结果只依赖它前面的结果行，已经落盘的都是对的，下次启动只补剩下的。

**旧格式的数据目录（R9、需求 8）**：不迁移。`StoreFormat.check` 看到 `storage = 1` 时，如果 `sessions/` 下有会话文件就拒绝启动，提示先移走 `sessions/` 和 `index.db`；没有就改成 `storage = 2`。会话文件头写 `format: 2`，读到 `format: 1` 的文件同样拒绝。

### 6. 重启收尾（R6、需求 6）

不改。`Engine.interrupt(unfinished)` 照旧给没结束的运行发 `RunInterrupted`。那个会话由加载器按结果行载入，运行状态（包括工具轮里每个调用的状态）来自结果行，所以 `ToolPairing` 能照旧分出“结果未知”和“没有执行”。`RunInterrupted` 这一步本身也是普通的一步，会写结果行。

### 7. 不认识的记录（R8、需求 7）

- 判断类型（`Fact`）目前全部是内置的 6 种：`PhaseChanged`、`ContextCompacted`、`CompactionFailed`、`HookDecided`、`HookReported`、`ImagesOmitted`。钩子只能返回 `Allow`、`Stop`、`NeedWork`，插件没有办法产生自己的 `Fact`（`HookChain.run`）。所以**不做插件注册接口**（需求 7.1）：现在没有插件能用到它。以后插件真能定义判断类型时，再加注册。
- 读到不认识的判断类型（以后的新版本写的，又降级回来）：原样保留 JSON，执行轨迹显示“未知的记录（类型名）”，会话照常载入（需求 7.2）。
- 读到不认识的命令类型、状态字段：拒绝载入这个会话，错误写明序号和类型（需求 7.3）。

### 8. 三步上线（R11、需求 9）

| 步 | 做什么 | 读的时候用什么 | 能停在这里吗 |
|---|---|---|---|
| 1 只写不用 | 结果行编码、`StepRecord`、通道落盘后放行、启动补写尾部、`storage = 2`、`RecordReplay`、冷载入两边比较并标出、检查工具 | 重放（照旧） | 能。所有会话都有结果行，但恢复不依赖它 |
| 2 改用 | 冷载入、`ColdReads`、`TraceQuery`、`TurnQuery`、`IndexCatchUp` 改用 `RecordReplay`；比较改成开关 `tantalum.journal.compare-replay`，默认关；请求详情的指纹提示 | 结果行 | 能。重放代码还在，打开开关就能比较 |
| 3 清理 | 删掉 `SessionReplay` 里交给内核的路径（只留给检查工具和测试），删掉 `StoreFormat.semantics` | 结果行 | 完成 |

第 1 步之后，旧版程序不能再打开这个数据目录（现有规则：`storage` 比程序新就拒绝启动）。退回旧版本时要用另一个数据目录。

### 9. 检查工具（R12、需求 10）

`java -jar tantalum.jar check [--tantalum.data-dir=…]`：Spring 以非 Web 模式启动，只建内核（和服务器同一套转移表、同一组钩子插件、同一份配置），不建模型客户端、MCP 和端口。对每个会话，用内核从头重放，再用结果行折叠，逐步比较 `Decision`（新状态、命令、判断），输出每个不一致会话的第一处差异（会话、序号、哪个字段）。只读，全部一致时退出码为 0，否则为 1。第 1 步冷载入时的比较也用同一个比较函数 `ReplayCompare`。

## Components and Interfaces

### value
- `StepRecord`、`SessionChange`（`tantalum.value.state`）：`of(before, decision)`、`apply(before)`。

### shell/journal
- `RecordCodec`：`byte[] encode(StepRecord, RecordMemo)`、`StepRecord decode(JsonNode, StringTable, PromptTracker)`。字符串引用和请求的相对编码都在这里。
- `RecordMemo`：每个常驻会话一份，包括长字符串的哈希集合和上一次请求。随会话回收（`Evictor.evicted`）一起丢掉。
- `SessionFileReader.forEach(file, Consumer<Line>)`：读两种行；原来的 `forEach(file, Consumer<EventEnvelope>)` 保留，只回调事件行，给检查工具和测试用。
- `SessionFile.append(List<Line>)`：事件行和结果行同一批写、一次 fsync。

### shell/loop
- `Cleanroom`：在 `step` 里算 `StepRecord`，交给 `LaneDispatcher.release(principal, Released)`。
- `UserLane`：工作项分成 `Numbered`（事件）和 `Released`（结果行、命令、推送）；`Released` 在落盘后放行。

### shell/store
- `RecordReplay`：和 `SessionReplay` 同样的 `load`、`each`、`until`。
- `Backfill`：启动时补写结果行。
- `ReplayCompare`：比较两种算法得到的 `Decision` 序列，返回第一处差异。

### shell/boot
- `RecordCheck`：检查工具的入口。
- `KernelFactory`：从 `Engine` 抽出建内核的代码，`Engine` 和 `RecordCheck` 共用。

### console
- 会话列表：比较不一致的会话显示一个警告标记，鼠标悬停时提示“恢复比对不一致，见服务器日志”（第 1 步）。
- 请求详情：`fingerprintMatches === false` 时，在顶部显示“与当时发出的请求不完全一致”（第 2 步）。

## Data Models

结果行（一行 JSON，键顺序固定）：
```json
{"result": 42,
 "sessions": [{"id": "s-1", "principal": "yi", "appended": [{"role": "assistant", "content": [{"type": "text", "text": "\u0000#3f2a…"}]}],
               "activeRun": null}],
 "runs": [{"ref": {...}, "phase": {"kind": "Awaiting", ...}, ...}],
 "removedRuns": [],
 "usage": {"day": "2026-11-04", "input": 1200, "output": 300, "cacheRead": 0, "cacheWrite": 0},
 "commands": [{"type": "CallModel", "id": "s-1_42-0", "prompt": {"system": "same", "tools": "same", "keep": 40, "add": [...]},
               "fingerprint": "9b1c…"}],
 "facts": [{"type": "PhaseChanged", ...}]}
```
- 没变的字段不写。`Optional.empty` 对应“不写这个键”，`null` 对应“写 null”。
- 命令和判断的类型名沿用 SSE 里的名字。
- 会话文件头 `{"format": 2, ...}`。读到 `format: 1` 的文件拒绝（需求 8）。

## Error Handling

| 情况 | 处理 |
|---|---|
| 结果行落盘失败 | 照事件写入失败处理：`Exit.failed`，进程停止处理事件；这一步的命令不放行 |
| `StepRecord.of` 发现消息不是只追加 | 抛异常，cleanroom 置 `broken`，入口返回 503（现有规则）。说明内核违反约定 |
| 结果行引用了没见过的字符串、序号没有对应事件 | `SessionCorruptException`，这个会话拒绝载入（现有规则） |
| 不认识的判断类型 | 原样保留，轨迹里显示“未知的记录”，会话照常载入 |
| 补写尾部时某个会话文件中间损坏 | 跳过这个文件、记错误日志，其余照常；这个会话照现有规则拒绝载入 |
| 数据目录里有旧格式的会话文件 | 拒绝启动，提示先移走 `sessions/` 和 `index.db`，不改文件 |
| 第 1 步比较不一致 | 记错误日志（会话、序号、字段），会话列表标出；恢复用重放的结果 |
| 请求指纹不一致 | 请求详情页显示提示，内容照常显示 |

## Security
- 结果行只含内核的值。凭据从来不进内核状态，所以也进不了结果行；指纹只覆盖 `Prompt`。
- 提交前扫描暂存内容中的密钥（照旧）。

## Testing Strategy

### Unit Testing
- `StepRecordTest`：`apply(before) == newState`，覆盖新建会话、追加消息、压缩、排队消息、子运行、运行结束、用量。消息不是只追加时，`of` 抛异常。
- `RecordCodecTest`：编码再解码得到相同的值；长字符串第二次出现时写成引用；字面上以 `\u0000` 开头的字符串能原样还原；请求的 `keep`/`add` 能还原出完整请求，指纹一致；不知道上一次请求时 `keep` 为 0；不认识的判断类型保留原文。
- `SessionFileReaderTest`：两种行交错、结果行序号对不上事件、结果行在事件之前，各自的报错。

### Integration Testing
- **每一步崩溃（需求 11.1）**：扩展 `EvictionInvarianceTest`，场景里每一步之后都只用结果行恢复，和崩溃前内存里的会话、运行、条目、轨迹逐字节相同。场景已经覆盖工具轮、思考、压缩、每次不同的记忆、两个用户交错、额度用完。
- **插件规则改了（需求 11.2）**：同一个会话文件，用两套钩子（只读钩子允许和不允许某个目录）分别恢复，结果相同；之后的新一步才不同。
- **实时和折叠一致**：在 `RingIntegrationTest`、`CompactionStressTest` 里挂一个测试用的检查器，每一步都比较 `record.apply(before)` 和实时的 `Decision`。
- **尾部（需求 11.3）**：结果行落盘前崩溃（测试用的 `SessionFile` 在第二次 fsync 前抛出），重启后用户提交的消息还在对话里，运行收尾为“因重启中断”。
- **旧格式（需求 11.3）**：`storage = 1` 且有会话文件时拒绝启动、文件不变；没有会话文件时升级为 2、用户库不变。尾部补写中途杀掉再启动，结果和一次补完相同。
- **检查工具**：一个一致、一个手工改坏的数据目录，退出码分别为 0 和 1，输出写明会话和序号。
- `JarCrashIT`：照旧通过（真实进程、杀进程、重启）。

### End-to-End Testing（需求 11.4）
- 改了访问范围的钩子规则后重启（`E2eServer` 换一份钩子配置重启），旧对话的内容和执行轨迹不变。
- 执行工具时重启：工具卡片显示“结果未知”，这一轮显示“因重启中断”。
- 重启前后，执行轨迹页的截图区域文字逐项相同。

### 性能（需求 1.8、2.4、3.6）
- `LaneBench`：P99 ≤ 50 ms。
- `ReadBench`：50 万事件语料的冷载入和启动，和 tech.md 里现在的数字比较。
- 会话文件大小：同一份语料（`ReadBench` 的生成器）有无结果行的总大小，增加 ≤ 30%。如果超出，先看是哪类内容（运行状态、请求、判断），再定怎么改；结果写进 tech.md。

## 阶段

1. 第 1 步：只写不用（含检查工具）。
2. 第 2 步：改用结果行。
3. 第 3 步：清理、文档、端到端。

每一步结束：完整回归、更新 8091、汇报。

## 风险

| 风险 | 应对 |
|---|---|
| 文件增大超过 30% | 字符串引用和请求的相对编码覆盖了大头；第 1 步就在语料上实测，超出时先定位再改 |
| 每步多一次 fsync，延迟超预算 | 同批合并；`LaneBench` 在第 1 步就测，超出时考虑把同一用户多个会话的 fsync 并行（现在已经是并行写） |
| 内核以后有一步改到别的会话 | `StepRecord.of` 检查这一约定并抛异常；测试里的检查器每一步比较实时和折叠，第 1 步就会暴露 |
| 第 1 步之后不能退回旧版本 | 退回时用另一个数据目录；现在只有 8091 一处部署 |

## 偏离或补充需求

- **R2 / 需求 1.3**：不做“来自本事件”这种按语义写的标记，改用通用的长字符串引用加请求的相对编码。效果相同（正文只存一份），但不需要知道“哪条消息是从哪个事件来的”，以后改内核也不用跟着改这里。
- **需求 1.4**：结束的运行照样记整个 `RunState`，它本来就只剩阶段和原因。
- **需求 2.1“不推给画面”**：SSE 推送等落盘之后再发。内存里的状态视图（`StateView`）和对话条目照旧立即更新，所以在这一次 fsync 的几毫秒里，REST 查询可能读到还没落盘的一步。如果正好这时崩溃，重启后这一步按尾部重新处理，结果相同，只是没有执行命令。
- **需求 7.1**：不做插件注册接口。现在插件没有办法产生自己的判断类型，以后能产生时再加。
- **需求 8**：按 Yi 2026-11-04 的决定改成不迁移、旧格式拒绝启动（需求文档已同步修改）。

## 实现中的偏离（第 1 步，2026-11-04）

- **事件不再单独 fsync（R3 的实现方式）**：LaneBench 实测，每步两次 fsync（事件一次、结果行一次）时 P99 101 ms，超出 50 ms 的预算：macOS 的 `FileChannel.force` 每次都刷整块磁盘的缓存，100 个会话每秒 200 次就排队了。改为事件只写不 fsync，和它的结果行一起落盘；在那之前不放行命令、不推送 SSE、不写索引（`steps.onStep` 挪到落盘之后，索引不会比文件超前，提交去重不会认一个丢了的提交）。崩溃时还没落盘的事件和结果一起丢，和从没发生过一样；提交接口本来就在进入输入环时返回，没有承诺过落盘。改后 P50 6.6 ms、P99 33.9 ms，第一秒之后 P99 10.3 ms，比加结果行之前（7.3 / 22 ms）还快。README 的“事件先落盘”相应改成“落盘之后才有效果”。
- **解除钉住不等落盘**：原设计让会话在结果行落盘前不能离开内存。这样 cleanroom 在这一步之后的回收检查总是看到它被钉住，空闲会话要等下一个事件才回收（`EvictionInvarianceTest` 等 4 个测试失败）。实际上不需要：同一用户的结果行和事件由同一个通道按队列顺序写，通道每一批都先写后载入，会话再被载入时一定读得到之前排队的结果行。只有放行名额和外部名额等落盘后归还。
- **`StepRecord`、`SessionChange` 放在 `value.decision`**：它们引用 `Decision`，放在 `state` 会让两个包互相依赖。
- **`SessionChange` 只有一个**：`StepRecord.session` 是单个变化，不是列表；一步只改事件所在的会话，违反时 `of` 抛出。
- **正文只存一份**：在 50 万事件的合成语料上实测，最初的写法让文件增大 94%，逐项找出大头后加了这些：
  - 请求里的消息写成会话历史的下标（按对象找，摘要调用带着很早的历史），请求的工具写成会话上下文工具的下标；
  - 会话上下文、运行状态只写变了的字段，工具轮只写变了的格（`toolSlots`）；
  - 内容块整块引用（`"\u0000@"+哈希`，只在结果行之间）；这一步追加的助手消息就是事件里的模型回复时写 `"fromEvent": true`；
  - 压缩摘要调用里裁剪过的工具结果写成原文的切片（`"\u0000$"+哈希:开头长度:结尾长度:中间`）；
  - 一行里重复的运行引用只写一次（`"\u0000="`），属于这个文件的会话时只写运行 ID；会话自己的 ID 和用户不再写；
  - 字符串引用的门槛从 256 字节降到 128 字节，哈希取 SHA-256 的前 128 位。
  结果：Yi 的 4 个真实会话 +9%，合成语料 +41%（预算 30%，见下条）。
- **合成语料上超出 30% 的预算**：剩下的主要是每一步的结构（运行阶段、命令 ID、用量、判断），平均每行约 800 字节。合成语料的事件平均只有 1.9 KB、每几轮就压缩一次，所以占比高；真实会话的事件大得多（工具输出、思考），占比小。再压只能换二进制格式或压缩文件，不在本 spec 范围。是否接受，交给 Yi 决定。
- **请求指纹只在打开请求详情时核对**：折叠时每次模型调用都重新编码整个请求算指纹，长会话里是平方级的开销（占折叠时间的 18%）。现在折叠只带出记下的指纹，请求详情页核对那一次。
- **读文件提速**：按块读、找换行符，代替逐字节读（长会话事件读取 70 ms → 27 ms）；只读事件时不解析结果行，只从行首取序号。
- **检查工具分两层**：比较逻辑在 `store/RecordCheck`（不依赖 Spring，嵌入的应用可以直接调用），`boot/RecordCheckCommand` 是命令行入口。累计用量从结果行算，不打开索引库，也不取存储锁。
- **`RingIntegrationTest` 不挂检查器**：它用的是不写文件的 `Release.IMMEDIATE`。真实管线下逐步对照由 `EvictionInvarianceTest`（每一步都只用结果行恢复）和 `CompactionStressTest`（250 轮、12 次压缩之后跑检查工具）覆盖。
