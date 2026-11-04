# Tasks Document

工作方式：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志。每个阶段结束：
- 跑全量回归（`scratchpad/regress.sh`：控制台构建、`mvn -o clean verify` 含 `JarCrashIT`、跳号回归、vitest、Playwright）；
- 做一次变异检查；
- 提交一次；
- 重新打包、更新 8091 上的服务；
- 向 Yi 汇报。

提交前扫描密钥。现有测试只因构造参数变化而改，断言不放宽。`WireFormatTest` 的期望文件（SSE 逐字节）不变：推送内容不变，只是时机挪到落盘之后。

路径前缀：`value/` = `tantalum-value/src/main/java/tantalum/value/`，`kernel/` = `tantalum-kernel/src/main/java/tantalum/kernel/`，`shell/` = `tantalum-shell/src/main/java/tantalum/shell/`，`console/` = `console/src/`。

## 阶段 1：只写不用

- [ ] 1. `StepRecord`、`SessionChange`
  - File: value/state/StepRecord.java、value/state/SessionChange.java
  - `of(before, decision)`：比较会话表、运行表、事件用户的用量；消息必须只追加、只能改事件所在的会话，否则抛 `IllegalStateException`。`apply(before)` 返回 `Decision`
  - 测试：新建会话、追加消息、压缩、排队消息、运行开始和结束、用量差值，`apply(before)` 等于 `newState`；违反两条约定时抛出
  - _Requirements: R1, 需求 1.1–1.7_

- [ ] 2. `RecordCodec` 基本编码
  - File: shell/journal/RecordCodec.java、shell/journal/EventCodec.java（复用 mapper 和多态登记）
  - 一行结果的 JSON（`"result": seq`），没变的字段不写，`null` 照写；命令、判断沿用现有类型名。不认识的判断类型解码为 `UnknownFact(type, Json)`，不认识的命令或状态字段抛 `SessionCorruptException`
  - 测试：往返相等；未知判断保留原文；未知命令拒绝
  - _Requirements: R1, R8, 需求 7.2, 7.3_

- [ ] 3. 长字符串引用
  - File: shell/journal/RecordCodec.java、shell/journal/RecordMemo.java
  - ≥ 256 字节且文件里已出现过的字符串写成 `"\u0000#"+SHA-256`；字面以 `\u0000` 开头的加一个 `\u0000`；读取方的字符串表收录事件行和结果行里的长字符串
  - 测试：第二次出现写成引用、能解开；转义往返；memo 为空时按原样写；引用了没见过的哈希时报错
  - _Requirements: R2, 需求 1.3_

- [ ] 4. 请求的相对编码和指纹
  - File: shell/journal/RecordCodec.java、shell/journal/RecordMemo.java
  - `CallModel` 写 `system`/`tools` 的 `same`、`keep`/`add`、`fingerprint`；上一次请求按会话跟踪
  - 测试：连续三次请求还原出完整 `Prompt`，指纹与 `EventCodec` 编码的 SHA-256 一致；压缩后前缀变短；不知道上一次时 `keep` 为 0
  - _Requirements: R2, R7, 需求 4.1, 4.3_

- [ ] 5. 文件格式 2
  - File: shell/journal/SessionFile.java、shell/journal/SessionFileReader.java、shell/journal/StoreFormat.java、shell/journal/TailSequence.java
  - 头部 `format: 2`；`append` 接受事件行和结果行混合一批、一次 fsync；`forEach(file, Consumer<Line>)` 读两种行，结果行序号递增且对应已读到的事件；`TailSequence` 跳过结果行；`StoreFormat` 的 `storage = 2`，`storage = 1` 且有会话文件时拒绝启动，没有时升级
  - 测试：交错读写；结果行序号不对、在事件之前时报错；`format: 1` 文件拒绝；旧目录有、无会话文件两种情况，用户库不变
  - _Requirements: R10, R9, 需求 8_

- [ ] 6. 通道：落盘后放行
  - File: shell/loop/UserLane.java、shell/loop/LaneDispatcher.java、shell/loop/ResultPublisher.java（如需要）
  - 新工作项 `Released(record, commands, notifications)`：和事件同批写进对应会话文件，fsync 后放行命令到输出环、按 (seq, index) 推送；输出环满时照旧 `reportDropped`；写入失败照旧 `Exit.failed` 且不放行
  - 测试：fsync 前不放行（测试用写入器卡住时输出环为空）；写入失败不放行；推送编号和现在相同
  - _Requirements: R3, 需求 2.1–2.3_

- [ ] 7. Cleanroom 算出结果行
  - File: shell/loop/Cleanroom.java、shell/loop/Evictor.java、shell/boot/Engine.java（接线）
  - `step` 里算 `StepRecord`，命令和推送交给通道；内存视图和条目照旧立即更新；会话回收时丢掉它的 `RecordMemo`
  - 测试：现有 `RingIntegrationTest`、`MultiUserTest` 等照旧通过；每个事件后文件里都有结果行
  - _Requirements: R1, R3_

- [ ] 8. `RecordReplay` 折叠
  - File: shell/store/RecordReplay.java、shell/store/SessionReplay.java（抽出“怎么算一步”）
  - 和 `SessionReplay` 同样的 `load`/`each`/`until`；冷载入时顺便建好 `RecordMemo`
  - 测试：同一个场景文件，两种算法给出的切片和每一步的 `Decision` 相同
  - _Requirements: R4, 需求 3.1, 3.2_

- [ ] 9. 启动时补写尾部
  - File: shell/store/Backfill.java、shell/boot/Storage.java、shell/boot/Engine.java
  - 大小不符的文件里没有结果行的事件，按用户合并排序，用结果行折叠出的状态加累计用量交给内核，命令不执行，结果行追加到各自文件
  - 测试：结果行落盘前崩溃（测试写入器在第二次 fsync 前抛出），重启后用户提交的消息在对话里、运行收尾为“因重启中断”；补写中途崩溃再启动，结果和一次补完相同
  - _Requirements: R5, R6, 需求 5, 6, 11.3_

- [ ] 10. 冷载入比较（第 1 步）
  - File: shell/store/ReplayCompare.java、shell/boot/Storage.java（加载器）、shell/web 会话列表接口
  - 加载器两种都算，比较不一致时记错误日志（会话、序号、字段）并记下这个会话；恢复用重放的结果；会话列表接口多一个 `replayMismatch` 字段
  - 测试：手工改坏一行结果行时日志和接口里都标出；一致时不标
  - _Requirements: R11, 需求 9.1_

- [ ] 11. 控制台标出不一致的会话
  - File: console/api/types.ts、会话列表组件、console/i18n/zh.ts
  - `replayMismatch` 为真时显示警告标记，悬停提示“恢复比对不一致，见服务器日志”
  - 测试：vitest 组件测试
  - _Requirements: 需求 9.1_

- [ ] 12. 检查工具
  - File: shell/boot/KernelFactory.java、shell/boot/RecordCheck.java、`TantalumApplication`（`check` 参数）
  - 从 `Engine` 抽出建内核；`java -jar tantalum.jar check` 非 Web 模式启动，逐个会话比较，输出第一处差异，退出码 0/1，只读
  - 测试：一致和改坏两个数据目录；目录里的文件不变
  - _Requirements: R12, 需求 10_

- [ ] 13. 每一步崩溃、插件规则改了
  - File: tantalum-shell/src/test/.../loop/EvictionInvarianceTest.java、新测试 `PluginChangeTest`、测试用检查器挂进 `RingIntegrationTest`、`CompactionStressTest`
  - 每一步之后只用结果行恢复，和内存逐字节相同；同一文件两套只读钩子规则恢复结果相同；每一步 `record.apply(before)` 等于实时的 `Decision`
  - _Requirements: 需求 3.2, 3.3, 11.1, 11.2_

- [ ] 14. 实测
  - File: tantalum-examples/src/test/.../bench（`LaneBench`、`ReadBench`，加文件大小统计）、.spec-workflow/steering/tech.md
  - 写路径 P99、冷载入、启动、文件大小增幅，和预算比较
  - _Requirements: 需求 1.8, 2.4, 3.6, 11.5_

## 阶段 2：改用结果行

- [ ] 15. 冷载入和 `ColdReads` 改用折叠
  - File: shell/boot/Storage.java、shell/store/ColdReads.java、shell/boot/TantalumProperties.java
  - 加载器、`ColdReads` 用 `RecordReplay`；比较改成 `tantalum.journal.compare-replay`，默认关
  - 测试：开关开、关两种；冷载入不调内核（测试用内核调用计数为 0）
  - _Requirements: R4, 需求 3.1, 9.2_

- [ ] 16. 轨迹、`/turns`、断线补读改用折叠
  - File: shell/store/TraceQuery.java、shell/store/TurnQuery.java
  - 测试：`WireFormatTest`、`PerSessionQueryTest` 照旧通过；不调内核
  - _Requirements: R4, 需求 3.1_

- [ ] 17. 索引追赶改用折叠
  - File: shell/store/IndexCatchUp.java
  - 用量来自结果行的差值，不再按用户合并重放
  - 测试：删掉索引库重建，结果和实时写入的相同
  - _Requirements: R4, 需求 3.5_

- [ ] 18. 请求指纹提示
  - File: shell/store/TraceQuery.java（`Call` 加 `fingerprintMatches`）、shell/web 对应接口、console 请求详情组件、console/i18n/zh.ts
  - 测试：指纹相同、不同两种；vitest 组件测试
  - _Requirements: R7, 需求 4.2_

## 阶段 3：清理和端到端

- [ ] 19. 删掉按重放恢复的路径
  - File: shell/store/SessionReplay.java（只留给检查工具和测试）、shell/journal/StoreFormat.java（删 `semantics`）、shell/boot/Storage.java
  - 测试：ArchitectureTest 加一条：`shell/store` 的查询类不依赖 `Kernel`（检查工具除外）
  - _Requirements: R11, 需求 9.3_

- [ ] 20. 端到端
  - File: console/e2e/record-results.spec.ts、tantalum-examples 的 `E2eServer`/`E2eController`（重启、换钩子配置）
  - 改了访问范围的钩子规则后重启，旧对话和执行轨迹不变；执行工具时重启，工具显示“结果未知”、这一轮“因重启中断”；重启前后执行轨迹页一致
  - _Requirements: 需求 11.4_

- [ ] 21. 文档
  - File: README.md、.spec-workflow/steering/tech.md、.spec-workflow/BACKLOG.md、架构页
  - 会话文件格式、恢复方式、检查工具用法、三步实测结果
  - _Requirements: 全部_
