# Requirements Document

## Introduction

目标不变：**以前发生过的事，打开时照原样显示；以后改代码、改插件、删插件，都不会改变过去。** record-results（11 月 4 日完成）已经做到“打开时照着文件还原，不进内核、不跑插件”，快照 11 月 3 日已去掉。本 spec 把会话文件改成 Yi 2026-11-06 定的样子（“一个event persist一行journal journal里面包含matched plugin matched skill matched tool matched mcp”）：

- **一件事一行。** 现在一件事写两行：先写“发生了什么”（事件行），内核处理完再写“结果”（结果行），中间崩溃会留下只有事件行的半截，要启动时的补写（`Backfill`）去补。改成内核处理完后，把“发生了什么”和“结果”合成一行，一次写进硬盘。
- **配上了什么，只在变化时记。** 系统里的插件、工具、MCP、skill，没配给会话之前只是“可以用的东西”；按用户和开关过滤后配给这个会话的，才是这个会话的“配上了什么”（matched）。现在每一轮提问都把服务器上全部工具的定义和完整 system 抄一遍（`ContextAssembled`，占真实会话文件的 10.7%）。改成会话开始时记一次全文，之后只记增减。
- **存好了才说“已接受”。** 现在用户点发送后，服务器把消息放进内存队列就回“已接受”（`ApiController.java:171-173`），这时崩溃消息就丢了。改成这件事的那一行写进硬盘之后才回。
- **工具没了，模型和用户都知道。** 用户关掉工具、插件被删，内核已经会告诉模型（core-tools C14：Claude 用原生的 `tool_removal`，其他模型用一句文字，`ToolChanges.describe`）。还缺两处：压缩之后这句提示会跟着历史一起消失；对话画面上看不到。
- **服务刚启动时打开会话要快。** 启动后第一次打开长会话 573 ms，主要是程序还没热身，加上每行解析两遍。

例子（Yi 2026-11-06 确认）：

| 时间 | 发生了什么 | 文件里写什么 |
|---|---|---|
| 周一 | 新会话，过滤后配上插件 `scope`、工具 `grep`、`fetch_url` | 第 1 行：用户的提问 + 结果，结果里有“配上了 `scope`、`grep`、`fetch_url`”（工具的完整定义） |
| 周一 | 模型调 `grep`，`scope` 放行，工具返回 | 每件事处理完写一行：发生了什么 + 结果 |
| 周三 | 管理员删了 `fetch_url` 所在的插件 | 文件不动 |
| 周四 | 打开会话 | 照着文件一行一行还原，不跑插件，不进内核，画面和周一一样 |
| 周四 | 接着问一句 | 重新过滤，少了 `fetch_url`：这一行的结果里记“配上的变了：少了 `fetch_url`”；告诉模型；画面上显示一行提示 |

参照 pi-mono 的 `main`（5c6ce2b，2026-11-05）和 DSH 的 `main`（ce7073d5）：

- **pi** 一条消息一行，`appendFileSync` 写完即算（`CA/src/core/session-manager.ts:1188`），没有“先写输入、再写结果”两步。模型、思考级别只在变化时写一行（`model_change`、`thinking_level_change`，`:64-78`、`:1230-1250`），恢复时从头取最后一次（`:419-433`）。工具的增减是对话里的一条 system 消息（`toolsAdded`、`toolsRemoved`），发请求前和上次比，有变化才插入（`agent/src/agent-loop.ts:332-372`）；Claude 用 `tool_removal` 块（`AI/src/api/anthropic-messages.ts:1650-1660`），其他用文字“The following tools are no longer available”，并在之后每次变化时重申仍不可用的工具（`AI/src/api/openai-responses-shared.ts:175-190`）；导出页上显示“Tools Removed”（`CA/src/core/export-html/template.js:1413-1415`）。压缩时工具状态合并成一份，已删除的工具连同提示一起消失（`session-manager.ts:1281-1294`）。
- **DSH** 工具列表记在 `request/header` 事件里，只在变化时写一份完整快照，原因标 `change` 或 `resume`（`core/session/src/types.ts:253-261`、`:362-369`，判断在 `core/agent-loop/src/agent.ts:262-266`）；不告诉模型或用户工具没了，模型调用时返回 `UNKNOWN_TOOL`（`core/tools/src/index.ts:486-500`）。提交提示词时交给 agent 的内存队列就回 `accepted`，不等落盘（`api/session-controller/src/commands.ts:304-349`）。

我们和两家的异同：
- 和 pi 一样一件事一行、配上的东西只记变化、告诉模型和用户。
- 和 DSH 一样变化时记**完整**的工具定义（pi 也是），恢复时不依赖插件还在不在。
- 比 pi 多一步：压缩之后仍然提示已不可用的工具。
- 比两家都严：存好了才回“已接受”。

路径写法同 record-results：pi 的 `CA` 指 `packages/coding-agent`，`AI` 指 `packages/ai`，`agent` 指 `packages/agent`；DSH 的路径相对于 `packages/`；两边都没有对应做法的标“自定”。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| 一件事一行，处理完一次写进硬盘；删掉启动时的补写 | 改内核的转移逻辑（内核照常做每一个新决定） |
| 存好了才回“已接受” | 快照（不加回） |
| 配上的插件、工具、MCP、system 各段、压缩设置：会话开始和每次压缩后记全文，之间只记变化 | skill：Tantalum 现在没有 skill。以后加 skill 时照本 spec 的规则记（需求 3.5） |
| 压缩之后仍告诉模型哪些工具已不可用；对话画面显示工具变化 | 会话中途**新出现**的工具（新连上的 MCP、以后反向沙箱的电脑连上）怎么交给模型：现在要等压缩或新会话（`ContextSteps.java:43-46`），留给反向沙箱 spec。本 spec 的记录格式要能记中途新增（需求 3.6） |
| 启动预热、每行只解析一次 | 旧会话迁移（同 record-results R9，Yi 2026-11-04 定不迁移） |
| 文件大小按 Yi 的真实会话衡量 | 压缩会话文件（zstd） |
| 改正 `tech.md` 里冷载入的对比基线 | 改变重启时收尾的行为（照 ops-hardening H9） |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 |
|---|---|---|
| J1 | 一行里有什么 | 这件事本身（用户提交、模型回复、工具结果、上下文组装等，同现在的事件行），加上内核对它做的决定（同现在的结果行：状态变化、判断、命令、用量、请求指纹）。结果里引用这件事的正文时只写“来自本行”，不抄第二遍（同 record-results R2）。pi 一条消息一行（`session-manager.ts:1188`）；“输入和决定同一行”自定 |
| J2 | 什么时候写 | 内核处理完这件事、算出决定之后，写这一行并 fsync，**之后**才执行命令、推给画面、写索引（同 record-results R3）。事件进来时只在内存里取序号，不写文件。每件事仍然只 fsync 一次（现在也是一次）。照 DSH 先落盘再执行（`session-checkpoint-policy/src/index.ts:29-82`） |
| J3 | 崩溃在写这一行之前 | 这件事没有留下任何记录，它的命令也没执行，所以没有副作用。用户的提交还没回“已接受”（J4），浏览器显示发送失败，文字留在输入框，再点一次用同一个 `requestId`（`Composer.tsx:116-125`）；模型回复、工具结果这类在途的事，重启时照 H9 收尾（工具记“结果未知”，运行标“因重启中断”）。因此不再有“有事件、没结果”的状态，启动时的补写（`Backfill`）、事件和结果的配对、补写时的内核调用都删掉。自定 |
| J4 | “已接受”什么时候回 | 提交那一行写进硬盘之后。写失败回 500，浏览器照现在显示错误、保留文字。会话还在载入等原因排队时，最多等 10 秒，超时回 503“服务器忙，请重发”；这件事之后如果还是写进去了，重发会按 `requestId` 去重，回“重复提交”，不会处理两次。DSH 进内存队列就回（`commands.ts:304-349`）；我们更严，自定 |
| J5 | 配上了什么 | 每次组装上下文时（每次提问开始一个运行、每次压缩后），按用户和开关过滤出这个会话用的东西：插件（名字）、工具（完整定义：名字、说明、参数，标明来自内置、哪个插件、哪个 MCP 服务器）、MCP 服务器（名字）、system 的固定部分和会变的几段（共享知识、AGENTS.md、记忆索引）、压缩设置、用户开着的工具和插件名单。这就是现在 `ContextAssembled` 里的内容（`ContextHandler.java:91-92`），记法变了，过滤规则不变（core-tools C16） |
| J6 | 怎么只记变化 | 会话第一次组装、每次压缩后重新组装时记全文。之间的组装只记和这个会话上一次记的相比**变了的部分**：哪些工具新增或删除（新增的带完整定义）、哪个工具的定义变了（带新定义）、哪些插件、名单、system 段变了；什么都没变就只记“没变”。恢复时从最近一次全文开始，依次套上之后的变化，得到每一步当时配上了什么，不读插件、工具目录和 MCP。DSH 变化时记完整快照（`agent.ts:262-266`）；pi 只记增减（`agent-loop.ts:332-372`）。我们全文和增减都用：全文让压缩后和单独查看某一步时不用从头算，增减让平时的文件小 |
| J7 | 工具没了的提示 | 现有的不变：开关变了、插件删了，下一次提问时内核追加一条工具变化消息，Claude 用 `tool_removal`，其他模型用文字（`ContextSteps.java:63-69`、`ClaudeRequest.java:205-213`、`ToolChanges.java:41-49`）。**新增**：压缩会把这条消息连同历史一起换成摘要，压缩后重新组装时，内核把“这个会话里配上过、现在不能用的工具”写成一句文字，放进组装出的请求里：“These tools were available earlier in this conversation and are no longer available. Do not call them: fetch_url”。之后每次工具再有变化，也在变化说明里重申仍不可用的工具。名单来自 J6 的记录，和压缩无关。提示由内核放进请求，所以会被请求指纹覆盖，请求详情页看得到。照 pi 重申（`openai-responses-shared.ts:175-186`）；压缩后保留是自定（pi 压缩后提示消失，`session-manager.ts:1281-1294`） |
| J8 | 画面上的提示 | 对话页在工具变化生效的位置显示一行：“工具变化：不再可用 `fetch_url`（插件已删除）”或“（已在插件页关闭）”；新增的显示“新增 `write_file`”。原因按过滤时的情况记：工具还在服务器、用户关了 → “已在插件页关闭”；服务器上已经没有 → “插件已删除或未加载”。执行轨迹页现在已经显示工具变化（`console/src/trace/inspector/toolChange.test.tsx`），不变。照 pi 的导出页（`template.js:1413-1415`），位置和文字自定 |
| J9 | 打开要快 | 服务启动完成后在后台打开一次内置的小会话，让读文件和还原的代码先热身；每行只解析一次（现在每行先解析一遍判断类型，结果行再解码一遍，`SessionFileReader.java:100-143`）。预热不改任何数据，不占用户的名额 |
| J10 | 文件大小的预算 | 按 Yi 的真实会话衡量（Yi 2026-11-06 同意）：`~/.local/share/tantalum-sessions-backup-2026-11-04/` 里的 4 个会话，用新代码重新生成新格式，总大小不超过它们原来（record-results 之前、只有事件）的 110%。按上一轮推算，结果多约 9%，配上的东西只记变化省约 10%，预计和原来差不多。合成语料的结果只记录、不设预算（合成事件太小，结果占比失真） |
| J11 | 冷载入的预算和基线 | `tech.md` 里“改造前 194 ms”是在有快照时测的，不公平。本 spec 先在同一台机器、同一份 1 万事件的语料上重测 record-results 之前的版本，作为基线（预计约 350 ms），改正 `tech.md`。预算：服务启动后第一次打开这个长会话不比基线慢；启动仍在 5 s 内 |
| J12 | 格式版本 | 会话文件头 `SessionFile.FORMAT` 2 → 3，数据目录 `storage` 2 → 3。旧格式的会话不迁移：新版本遇到 `storage = 2` 且有会话文件时拒绝启动，提示先移走 `sessions/` 和 `index.db`（同 record-results R9）。8091 现在只有一个测试会话 `s-recordsmoke`，升级前我先移到备份目录，并告诉 Yi |
| J13 | 检查工具 | `java -jar tantalum.jar check` 保留：用当前代码按序重放会话，和每行记的决定逐步比较。读新格式 |
| J14 | 分四步上线 | 每一步做完都部署到 8091，用一两句话向 Yi 报告：① 一件事一行、存好才回“已接受”、删补写；② 配上的东西只记变化；③ 压缩后的提示和画面提示，做完在 8091 演示（装一个带工具的插件，聊几句，删插件，再接着聊）；④ 预热和每行只解析一次，重测大小和速度 |

## Requirements

### Requirement 1：一件事一行

**User Story:** 作为用户，我希望会话文件里每一行都是完整的一件事，崩溃也不会留下半截要补的记录。

出处：pi `CA/src/core/session-manager.ts:1188`；DSH `session/session-checkpoint-policy/src/index.ts:29-82`；J1–J3。

#### Acceptance Criteria

1. WHEN 内核处理完一件事 THEN 服务器 SHALL 在这个会话的文件里追加**一行**，同时写明这件事和内核对它的决定。
2. WHEN 这一行还没写进硬盘 THEN 服务器 SHALL NOT 执行这件事的命令、推给画面、写索引。
3. 服务器 SHALL NOT 在内核处理之前把事件写进会话文件。
4. WHEN 进程在写这一行之前崩溃 THEN 重启后这件事 SHALL 不存在于文件里；在途的运行 SHALL 照 H9 收尾。
5. 启动时的补写（`Backfill`）、事件行和结果行的配对 SHALL 删掉；恢复、查询、补读、检查工具 SHALL 只读一种行。
6. WHEN 文件末尾有写了一半的行 THEN 服务器 SHALL 照现在的规则截掉它（`SessionFileReader.java:306`）。
7. IF 用 `LaneBench` 测量（100 个会话、10 个用户、每会话每秒 1 个事件） THEN 从事件进入输入环到命令放行，P99 SHALL 不超过 50 ms（现有预算）。

### Requirement 2：存好了才说“已接受”

**User Story:** 作为用户，我希望看到“已接受”的消息一定不会丢；没存好的，我能看到发送失败并重发。

出处：DSH `api/session-controller/src/commands.ts:304-349`（不照它）；J4。

#### Acceptance Criteria

1. WHEN 用户提交一条消息 THEN 服务器 SHALL 在包含这次提交的那一行写进硬盘之后，才回应“已接受”。
2. WHEN 写入失败 THEN 服务器 SHALL 回 500；控制台 SHALL 照现在显示错误，保留输入框里的文字和附件。
3. WHEN 10 秒内没写进硬盘 THEN 服务器 SHALL 回 503“服务器忙，请重发”。
4. WHEN 用户用同一个 `requestId` 重发 THEN 服务器 SHALL 不处理第二次：已经写进去的回“重复提交”，没写进去的照常处理。
5. IF 用 `LaneBench` 的负载测量 THEN 提交接口的回应时间 P99 SHALL 不超过 50 ms。

### Requirement 3：配上了什么，只在变化时记

**User Story:** 作为用户，我希望打开旧会话时，它当时配上的插件、工具、MCP 都照原样还原，即使它们已经改了、删了；文件也不要因为每轮重抄而变大。

出处：pi `CA/src/core/session-manager.ts:64-78`、`419-433`，`agent/src/agent-loop.ts:332-372`；DSH `core/session/src/types.ts:253-261`、`362-369`，`core/agent-loop/src/agent.ts:262-266`；J5、J6。

#### Acceptance Criteria

1. WHEN 一个会话第一次组装上下文，或压缩后重新组装 THEN 这一行 SHALL 记下配上的全部内容：插件、工具的完整定义和来源、MCP 服务器、system 的固定部分和会变的几段、压缩设置、用户开着的工具和插件名单。
2. WHEN 之后再组装上下文 THEN 这一行 SHALL 只记和这个会话上一次相比变了的部分；新增的工具和定义变了的工具 SHALL 带完整定义；什么都没变时 SHALL 只记“没变”。
3. WHEN 恢复会话、查看某一步的请求详情、运行检查工具 THEN 服务器 SHALL 只用这些记录得出当时配上了什么，SHALL NOT 读插件、工具目录或 MCP。
4. WHEN 插件被删除或改版后打开旧会话 THEN 对话页、执行轨迹、请求详情 SHALL 和删除前一样。
5. 记录格式 SHALL 为以后的 skill 留出位置：加 skill 时，配给会话的 skill 照本需求只在变化时记。
6. 记录格式 SHALL 能记会话中途新增的工具（带完整定义），供反向沙箱 spec 使用；本 spec 不改变新工具什么时候交给模型。
7. 过滤规则 SHALL 不变：只配这个用户开着的工具和插件，所有调用插件的地方仍经过同一个过滤入口（core-tools C16）。

### Requirement 4：工具没了，告诉模型

**User Story:** 作为用户，我希望插件删了或者我关了某个工具以后，接着聊时模型知道这个工具不能用了，压缩之后也一样。

出处：pi `agent/src/agent-loop.ts:332-372`、`AI/src/api/anthropic-messages.ts:1650-1660`、`AI/src/api/openai-responses-shared.ts:175-190`、`CA/src/core/session-manager.ts:1281-1294`；J7。

#### Acceptance Criteria

1. WHEN 一次提问开始时配上的工具比上次少了 THEN 内核 SHALL 照现在追加工具变化消息（Claude 用 `tool_removal`，其他模型用文字）。
2. WHEN 压缩后重新组装上下文，且这个会话里配上过的工具现在不能用 THEN 内核 SHALL 在组装出的请求里加一句文字，列出这些工具，告诉模型不要再调用。
3. WHEN 之后工具又有变化 THEN 变化说明 SHALL 同时重申仍不可用的工具。
4. 这句提示 SHALL 由内核放进请求，SHALL 包含在请求指纹里，请求详情页 SHALL 显示它。
5. WHEN 模型仍然调用了已不可用的工具 THEN 服务器 SHALL 照现在返回错误结果，不执行。
6. 真实模型验证：用 Claude 跑一次“用过工具 → 删插件 → 压缩 → 接着问会用到这个工具的问题”，模型 SHALL 不调用它。只跑这一个场景，结果写进 `tech.md`。

### Requirement 5：工具变化，画面上看得到

**User Story:** 作为用户，我希望在对话里看到哪个工具从哪里开始不能用了、为什么。

出处：pi `CA/src/core/export-html/template.js:1413-1415`；J8。

#### Acceptance Criteria

1. WHEN 会话里发生工具变化 THEN 对话页 SHALL 在变化生效的位置显示一行，写明新增、不再可用的工具名和原因（“已在插件页关闭”或“插件已删除或未加载”）。
2. WHEN 打开旧会话 THEN 这一行 SHALL 照样显示在原来的位置，原因来自当时的记录。
3. 执行轨迹页现有的工具变化显示 SHALL 不变。

### Requirement 6：打开要快

**User Story:** 作为用户，我希望服务刚启动时打开长会话也不慢。

出处：自定；J9、J11。

#### Acceptance Criteria

1. WHEN 服务启动完成 THEN 服务器 SHALL 在后台用一个内置的小会话预热读文件和还原的代码；预热 SHALL NOT 改动数据目录、SHALL NOT 占用户名额、失败时只记日志。
2. 读会话文件时每一行 SHALL 只解析一次。
3. IF 用 `ReadBench` 在 1 万事件的语料上测量 THEN 服务启动后第一次打开长会话 SHALL 不比 J11 的基线慢；启动 SHALL 在 5 s 内。
4. `tech.md` 的冷载入数据 SHALL 改正：写明 194 ms 是有快照时测的，给出 J11 重测的基线。

### Requirement 7：文件大小

**User Story:** 作为 Yi，我希望会话文件的大小按我真实的会话来衡量。

出处：Yi 2026-11-06（同意按真实会话衡量）；J10。

#### Acceptance Criteria

1. IF 用新代码把 `~/.local/share/tantalum-sessions-backup-2026-11-04/` 里的 4 个会话重新生成为新格式 THEN 总大小 SHALL 不超过它们原来大小的 110%。
2. 合成语料上的大小变化 SHALL 记进 `tech.md`，不设预算。
3. 测量 SHALL 只读备份目录，不改动它。

### Requirement 8：格式版本和旧数据

**User Story:** 作为部署的人，我希望新版本遇到旧格式时明确拒绝，而不是读错。

出处：record-results R9、R10；J12。

#### Acceptance Criteria

1. 会话文件头的格式版本 SHALL 是 3，数据目录 `format.json` 的 `storage` SHALL 是 3。
2. WHEN `storage` 是 2、`sessions/` 下有会话文件 THEN 服务器 SHALL 拒绝启动，错误信息写明要先移走 `sessions/` 和 `index.db`，不改动任何文件。
3. WHEN `storage` 是 2、没有会话文件 THEN 服务器 SHALL 改成 3，正常启动；用户库、插件开关、用户设置不受影响。

### Requirement 9：验证

**User Story:** 作为 Yi，我希望每一步都有测试证明没改坏东西。

出处：record-results 需求 11；J13、J14。

#### Acceptance Criteria

1. 测试 SHALL 在场景的每一步之后模拟崩溃：恢复出的状态、对话条目、轨迹记录 SHALL 和崩溃前内存里的逐字节相同（`EvictionInvarianceTest`）。
2. 测试 SHALL 覆盖崩溃在写一行之前：重启后这件事不在文件里，提交没有回“已接受”，运行照 H9 收尾。
3. 测试 SHALL 证明删了插件后旧会话的对话页、轨迹、请求详情不变，之后的新一步不再配上它的工具和钩子。
4. 测试 SHALL 覆盖压缩前后的工具提示（需求 4.1–4.4）和格式版本（需求 8）。
5. 端到端测试 SHALL 覆盖：删插件后打开旧会话照原样；接着问时对话页显示工具变化；record-results 现有的三个端到端场景照常通过。
6. 检查工具 SHALL 在 `CompactionStressTest` 的语料上报告全部一致。
7. 每一步结束时，完整回归（value、kernel、shell、examples、JarCrashIT、vitest、Playwright）SHALL 全部通过，8091 SHALL 更新到这一步。

## Non-Functional Requirements

### Code Architecture and Modularity
- 一行的编码放在 `shell/journal`；内核和 value 模块不知道文件格式。
- 内核的转移逻辑只加需求 4.2、4.3 的提示，别的不改。
- 删掉的代码删干净：`Backfill`、事件和结果的配对、只为两种行存在的分支。
- 类名简单直白。

### Performance
- 写路径 P99 ≤ 50 ms（需求 1.7、2.5）。
- 冷载入不比基线慢，启动 ≤ 5 s（需求 6.3）。
- 真实会话文件 ≤ 原来的 110%（需求 7.1）。

### Security
- 每一行和配上的记录 SHALL NOT 含 API key 或任何凭据，规则同现在的 journal。工具定义来自插件和 MCP 服务器的说明，MCP 的连接地址和令牌 SHALL NOT 写进去。

### Reliability
- 写不进硬盘时不执行命令、不回“已接受”（需求 1.2、2.2）。
- 旧格式拒绝启动，不猜（需求 8.2）。

### Usability
- 控制台的变化只有两处：对话页的工具变化行（需求 5），提交的 503 提示（需求 2.3）。
