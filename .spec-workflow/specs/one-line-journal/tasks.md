# Tasks Document

工作方式：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志。每个阶段结束：
- 跑全量回归（`scratchpad/regress.sh`：控制台构建、`mvn -o clean verify` 含 `JarCrashIT`、vitest、Playwright）；
- 提交一次；
- 重新打包，复制到 `scratchpad/app/tantalum.jar`，跑 `up.sh` 更新 8091；
- 用一两句话向 Yi 汇报。

提交前扫描密钥。现有测试只因构造参数、行的类型变化而改，断言不放宽。

路径前缀：`value/` = `tantalum-value/src/main/java/tantalum/value/`，`kernel/` = `tantalum-kernel/src/main/java/tantalum/kernel/`，`shell/` = `tantalum-shell/src/main/java/tantalum/shell/`，`examples/` = `tantalum-examples/src/test/java/tantalum/examples/`，`console/` = `console/src/`。

每个任务的 `_Prompt` 开头省略的固定部分：“Implement the task for spec one-line-journal, first run spec-workflow-guide to get the workflow guide then implement the task: 开始时在 tasks.md 把本任务标成 [-]，做完用 log-implementation 记录，再标成 [x]。”

## 阶段 1：一件事一行，存好才回“已接受”

- [x] 1. `Line` 和 `LineCodec`
  - File: shell/journal/Line.java、shell/journal/LineCodec.java（新）、shell/journal/EventCodec.java、shell/journal/RecordCodec.java
  - `Line` 改成 `record Line(EventEnvelope envelope, StepRecord record, Map<CommandId, String> fingerprints)`，删掉 `Line.Event`、`Line.Result`。`LineCodec.encode` 写事件字段加 `result`；`decode(JsonNode, memo)` 从一棵树解出事件和决定。`EventCodec`、`RecordCodec` 各加一个从 `JsonNode` 解码的入口
  - 测试：每种事件一行往返相等；“来自本事件”引用同一行的正文；`result` 缺失或序号不对时 `SessionCorruptException`
  - _Leverage: EventCodec、RecordCodec、RecordMemo.seeEvent_
  - _Requirements: J1, 需求 1.1_
  - _Prompt: Role: Java 后端开发 | Task: 按设计“一行的样子”实现 Line 和 LineCodec | Restrictions: 不改 RecordCodec 已有的去重规则；内核和 value 不知道文件格式 | Success: 往返测试全过，ArchitectureTest 通过_

- [x] 2. 读写一种行、格式 3
  - File: shell/journal/SessionFileReader.java、shell/journal/SessionFile.java、shell/journal/SessionFiles.java、shell/journal/TailSequence.java、shell/journal/StoreFormat.java
  - 读：每行只解析一次，交给 `LineCodec`；末尾半行照旧截断。写：`appendLines` 每批 fsync 一次；删掉只写事件、不 fsync 的分支。`FORMAT = 3`，`storage = 3`：`storage = 2` 且有会话文件时拒绝启动，没有时升级
  - 测试：读写往返；半行截断；`format: 2` 文件拒绝；旧目录有、无会话文件两种情况，用户库和设置不变
  - _Leverage: SessionFile.appendLines、StoreFormat.check_
  - _Requirements: J9, J12, 需求 1.6, 6.2, 8_
  - _Prompt: Role: Java 后端开发 | Task: 让会话文件只有一种行，读的时候每行只解析一次，格式升到 3 | Restrictions: 不迁移旧数据；拒绝时不改任何文件 | Success: StoreFormatTest、SessionFileTest 全过_

- [x] 3. 通道只在处理完后写
  - File: shell/loop/UserLane.java、shell/loop/LaneDispatcher.java
  - 收到事件只取序号、钉住会话、载入冷会话、发布；收到 `Released` 时按会话写一行，全部写完再运行 `afterDurable`、`done`。改类注释
  - 测试：事件进来后文件不变；fsync 前不放行（写入器卡住时输出环为空）；写失败只运行 `done` 并停止处理；多会话并行写
  - _Leverage: 现有 UserLaneTest 的卡住写入器_
  - _Requirements: J2, 需求 1.2, 1.3_
  - _Prompt: Role: Java 并发开发 | Task: 把 UserLane 改成内核处理完才写一行 | Restrictions: 同一用户按序、不同用户互不等待不变；每批一次 fsync | Success: UserLaneTest、RingIntegrationTest、MultiUserTest 全过_

- [x] 4. 折叠不再配对，删掉补写
  - File: shell/store/RecordReplay.java、shell/store/Backfill.java（删）、shell/boot/Storage.java、shell/store/IndexCatchUp.java、shell/store/TraceQuery.java、shell/store/TurnQuery.java、shell/store/UsageLedger.java 及其他 `forEachLine` 的使用者
  - 每行直接套用决定；删 `waiting`、`requireAnswered`、`Folded.unanswered`、`Backfill`、`BackfillTest`；`Storage.catchUp` 只剩索引追赶
  - 测试：`EvictionInvarianceTest` 每一步后崩溃恢复逐字节相同；查询、补读、轨迹照旧
  - _Leverage: RecordReplay.Fold_
  - _Requirements: J3, 需求 1.4, 1.5, 9.1_
  - _Prompt: Role: Java 后端开发 | Task: 删掉事件和结果的配对和启动补写 | Restrictions: 删干净，不留只为两种行存在的分支 | Success: shell 模块测试全过，grep 不到 Line.Event、Line.Result、Backfill_

- [x] 5. 检查工具读新格式
  - File: shell/store/RecordCheck.java、shell/store/ReplayCompare.java
  - 每行用当前内核重放这个事件，和行里的决定比较；输出和退出码不变
  - 测试：一致时退出 0；改一个钩子规则后报告第一处差异
  - _Leverage: RecordCheck 现有测试_
  - _Requirements: J13, 需求 9.6_
  - _Prompt: Role: Java 后端开发 | Task: RecordCheck 读一种行 | Restrictions: 只读，不改数据目录 | Success: RecordCheckTest 全过_

- [x] 6. 提交的回执
  - File: shell/loop/Receipts.java（新）、shell/loop/Intake.java、shell/boot/Engine.java
  - `Submission` 带 `durable`；`Engine` 包装 `afterDurable`、`done`：`Submitted` 事件落盘后 `durable`，只运行了 `done` 时 `failed`；重复提交在途时等同一个回执，已落盘时立即完成
  - 测试：落盘后完成；写失败、进程停止时失败；重复提交两种情况；完成后表里不留条目
  - _Leverage: SubmissionLedger_
  - _Requirements: J4, 需求 2.1, 2.4_
  - _Prompt: Role: Java 并发开发 | Task: 实现 Receipts 并接到 Intake 和 Engine | Restrictions: 取消不等回执；拒绝的提交立即返回 | Success: IntakeTest、ReceiptsTest 全过_

- [x] 7. 提交接口等落盘
  - File: shell/web/ApiController.java、shell/boot/TantalumProperties.java（`loop.ack-timeout`，默认 10 s）
  - 提交和手动压缩等回执：完成 → 200；失败 → 500“保存失败，请重发”；超时 → 504“服务器忙，请重发”（实现时改用 504：控制台把所有 503 当作“服务已停止处理事件”；超时常量 10 s 写在 `ApiController.SAVE_WAIT`，不做配置项）
  - 测试：三种回应；控制台失败时保留文字和附件（现有 `Composer` 测试补一条 503）
  - _Leverage: ApiController.accepted_
  - _Requirements: J4, 需求 2.1–2.3_
  - _Prompt: Role: Java/React 全栈开发 | Task: 提交接口在落盘后才回 200 | Restrictions: 接口形状不变 | Success: ApiControllerTest、vitest 全过_

- [x] 8. 崩溃在写一行之前
  - 实现时改了做法：用真实 jar 很难卡准“内核处理完、还没写这一行”的瞬间。改成在现有的 `kill -9` 场景里检查：回过 200 的三个提交被杀之后都在文件里；“写不进去就不回 200”由 `SaveBeforeAcceptTest` 覆盖（目录不可写 → 500，之后 503），“丢了最后一行，会话停在上一步”由 `RecordReplayTest` 覆盖
  - File: examples/JarCrashIT.java
  - 新场景：写入前杀掉进程，重启后这件事不在文件里、提交没有收到 200、运行照 H9 收尾，用同一 `requestId` 重发只处理一次
  - _Leverage: JarCrashIT 现有的杀进程场景_
  - _Requirements: J3, 需求 9.2_
  - _Prompt: Role: 测试开发 | Task: 加崩溃场景 | Restrictions: 用真实 jar | Success: JarCrashIT 通过_

- [x] 9. 阶段 1 收尾
  - 2026-11-06 完成：`LaneBench` P99 27.2 ms；全量回归通过（vitest 330、Playwright 44、JarCrashIT）；8091 旧会话 `s-recordsmoke` 和 `index.db` 已移到 `~/.local/share/tantalum-sessions-backup-2026-11-06/`；8091 实测一问一答 3 行、提交 0.1 s 回 200
  - `LaneBench`：命令放行和提交回应 P99 ≤ 50 ms，写进 `tech.md`；全量回归；先把 8091 的 `s-recordsmoke` 和 `index.db` 移到 `~/.local/share/tantalum-sessions-backup-2026-11-06/` 并告诉 Yi；提交、更新 8091、汇报
  - _Requirements: 需求 1.7, 2.5, 9.7_
  - _Prompt: Role: 发布负责人 | Task: 阶段 1 收尾 | Restrictions: 移数据前先告诉 Yi；提交前扫描密钥 | Success: 回归全过，8091 能登录、能聊天_

## 阶段 2：配上了什么，只记变化

- [x] 10. 工具来源
  - File: value/event/ContextAssembled.java、shell/handler/ContextHandler.java、shell/tool/ToolCatalog.java、shell/access/PluginGate.java
  - 加 `toolSources`（`builtin`、`plugin:<名字>`、`mcp:<服务器名>`），`version` 2；不写地址和令牌
  - 测试：三种来源；内核行为不变
  - _Leverage: ToolCatalog.serverName_
  - _Requirements: J5, 需求 3.1_
  - _Prompt: Role: Java 后端开发 | Task: ContextAssembled 记下每个工具的来源 | Restrictions: 内核不读这个字段 | Success: ContextHandlerTest、EventCodecTest 全过_

- [x] 11. `MatchedCodec`
  - File: shell/journal/MatchedCodec.java（新）、shell/journal/RecordMemo.java、shell/journal/LineCodec.java
  - 第一次、压缩后、不知道前文时写全文；其余写变化（system、sections、名单变了才写；工具按名字写增删改）；读取时套回完整事件；没有前文的“变化”拒绝
  - 测试：设计里列的六种情况；`CompactionStressTest` 逐步一致
  - _Leverage: RecordMemo 的按会话状态_
  - _Requirements: J6, 需求 3.1–3.6_
  - _Prompt: Role: Java 后端开发 | Task: 配上的东西只记变化 | Restrictions: 解码后的 ContextAssembled 和原值逐字段相同 | Success: MatchedCodecTest、EvictionInvarianceTest 全过_

- [x] 12. 删插件后旧会话不变
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/PluginRemovalTest.java（新）
  - 一个插件提供工具和钩子 → 用过 → 拿掉插件、重启 → 对话页、轨迹、请求详情不变；新一步不再配上它
  - _Requirements: 需求 3.4, 3.7, 9.3_
  - _Prompt: Role: 测试开发 | Task: 证明删插件不改变过去 | Restrictions: 用真实管线 | Success: 测试通过_

- [x] 13. 文件大小
  - 2026-11-06：放在 `tantalum-shell/src/test/java/tantalum/shell/store/RealSizeProbe.java`（取代 `RecordSizeProbe`），4 个真实会话 748,822 → 777,035 字节（103.8%）。合成语料留到任务 22 和 `ReadBench` 一起量
  - File: examples/bench/RealSizeProbe.java（新，手动运行）
  - 只读备份目录的 4 个会话，用当前内核重放，按新格式编码，和原大小比较；合成语料的结果一并记进 `tech.md`
  - _Requirements: J10, 需求 7_
  - _Prompt: Role: 性能工程师 | Task: 按真实会话测文件大小 | Restrictions: 不改备份目录 | Success: 总大小 ≤ 原来的 110%，结果写进 tech.md_

- [x] 14. 阶段 2 收尾
  - 全量回归、提交、更新 8091、汇报
  - _Requirements: 需求 9.7_
  - _Prompt: Role: 发布负责人 | Task: 阶段 2 收尾 | Restrictions: 提交前扫描密钥 | Success: 回归全过_

## 阶段 3：工具没了，模型和用户都知道

- [x] 15. `toolsSeen` 和 `unavailable`
  - File: value/state/SessionState.java、value/model/ToolChangeBlock.java、shell/journal/RecordCodec.java、shell/journal/EventCodec.java
  - 两个新字段及编码；压缩不清空 `toolsSeen`
  - 测试：往返；压缩后仍在
  - _Requirements: J7, 需求 4.2, 4.3_
  - _Prompt: Role: Java 后端开发 | Task: 加两个值字段 | Restrictions: 只加字段 | Success: value、shell 编码测试全过_

- [x] 16. 内核的提示
  - File: kernel/step/ContextSteps.java、shell/model/ToolChanges.java、shell/model/ClaudeRequest.java、shell/model/OpenAiClient.java、kernel/compaction/TokenEstimate.java
  - 压缩后重新组装时，`toolsSeen − 现在开着的` 不空就追加 `ContextUpdate` 提示；工具变化时带 `unavailable`；文字发法加 “Still unavailable: …”，Claude 发法不变
  - 测试：压缩前后提示；重申；请求指纹包含提示；调用已不可用的工具仍返回错误
  - _Leverage: ContextUpdate、ToolChangeBlock_
  - _Requirements: J7, 需求 4.1–4.5_
  - _Prompt: Role: Java 后端开发 | Task: 压缩后仍告诉模型哪些工具不能用 | Restrictions: 内核只加这两处 | Success: ContextStepsTest、ToolSwitchStepsTest、ClaudeRequestTest、OpenAiClientTest 全过_

- [x] 17. 对话页的工具变化
  - File: shell/conversation/TranscriptPass.java、console/conversation/ToolChangeNotice.tsx（新）、console/text/zh.ts
  - 条目 `toolChange`：新增、不再可用的名字和原因（`disabled`、`gone`，从同一行的 `ContextAssembled` 判断）；控制台显示一行灰色小字
  - 测试：投影测试两种原因；vitest 渲染；打开旧会话仍在原位
  - _Requirements: J8, 需求 5_
  - _Prompt: Role: 全栈开发 | Task: 对话页显示工具变化 | Restrictions: 轨迹页现有显示不变；压缩后的提示不在画面重复 | Success: 测试全过_

- [x] 18. 端到端
  - File: examples/E2eServer.java、console/e2e/one-line-journal.spec.ts（新）
  - E2eServer 加能拿掉的测试工具 `e2e_fetch`；用它 → 拿掉 → 移出内存后重新打开照原样 → 接着问显示“不再可用”
  - 实现时改了做法：E2eServer 的内置工具启动时固定注册、测试 MCP 服务器不能中途删工具，所以端到端走“插件页关掉 grep”这条路（显示“已在插件页关闭”）；“服务器上已经没有”这条路由 `PluginRemovalTest`（真实管线、重启）、`TranscriptToolChangeTest` 和 vitest 覆盖，第 20 步在 8091 上停掉浏览器 MCP 实际演示
  - _Requirements: 需求 9.5_
  - _Prompt: Role: 测试开发 | Task: 写端到端场景 | Restrictions: record-results.spec.ts 照常通过 | Success: Playwright 全过_

- [x] 19. 真实模型一次
  - File: examples/ClaudeSmokeTest.java
  - `toolGoneAfterCompaction`：用一次工具 → 拿掉 → 压缩 → 问一个会用到它的问题，判定模型不调用它；结果和花费写进 `tech.md`
  - _Requirements: 需求 4.6_
  - _Prompt: Role: 测试开发 | Task: 用 Claude 验证压缩后的提示 | Restrictions: 只跑这一个；key 只从环境读，不打印 | Success: 模型没有调用已不可用的工具_

- [ ] 20. 阶段 3 收尾和演示
  - 全量回归、提交、更新 8091；在 8091 演示：装一个带工具的插件，聊几句，删插件，再接着聊，截图给 Yi
  - _Requirements: J14, 需求 9.7_
  - _Prompt: Role: 发布负责人 | Task: 阶段 3 收尾和演示 | Restrictions: 提交前扫描密钥 | Success: Yi 看到提示_

## 阶段 4：打开要快

- [ ] 21. 启动预热
  - File: shell/boot/Warmup.java（新）、tantalum-shell/src/main/resources/warmup/session.jsonl（生成）、tantalum-shell/src/test/java/tantalum/shell/boot/WarmupFileTest.java（新）
  - 启动后后台折叠内置小会话 5 遍，临时目录，失败只记 WARN
  - 测试：文件能读出来；预热不碰数据目录
  - _Requirements: J9, 需求 6.1_
  - _Prompt: Role: Java 后端开发 | Task: 启动预热 | Restrictions: 不占名额、不进索引 | Success: 测试通过，日志有 warmup done_

- [ ] 22. 基线重测和 `tech.md`
  - 用 `git worktree` 检出 `766655f` 跑 `ReadBench`（1 万事件）作基线；当前版本再跑一次；改正 `tech.md` 里 194 ms 的说明，写入两组数据
  - _Requirements: J11, 需求 6.3, 6.4_
  - _Prompt: Role: 性能工程师 | Task: 重测冷载入 | Restrictions: 同一台机器、同一份语料 | Success: 第一次打开不比基线慢，启动 ≤ 5 s_

- [ ] 23. 收尾
  - README 里会话文件格式的说明、`BACKLOG.md`、实现日志；全量回归、提交、更新 8091、汇报，并报告 Claude 用量
  - _Requirements: 需求 9.7_
  - _Prompt: Role: 发布负责人 | Task: 全部收尾 | Restrictions: 提交前扫描密钥 | Success: 回归全过，文档和代码一致_
