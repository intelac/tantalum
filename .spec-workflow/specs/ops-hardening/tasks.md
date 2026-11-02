# Tasks Document

工作方式：一次一个任务；行为改动先写失败的测试；完成后标记、记录实现日志；每个阶段结束提交一次，并汇报做了什么、验证结果、是否偏离 spec、下一步。提交前扫描密钥。任务只有在验证项真的跑过后才标完成。

**检查点：** 阶段 0 结束后停下，向 Yi 汇报基线和预算，确认后才动存储。其余阶段汇报后继续。

编号：任务 45–51 是 2026-11-02 加的（快照、序号、ID），放在实际执行的位置，原有编号不变。任务 21（拆分旧 journal）已取消，Yi 确认没有旧数据。

路径前缀：`shell/` = `tantalum-shell/src/main/java/tantalum/shell/`，`kernel/` = `tantalum-kernel/src/main/java/tantalum/kernel/`，`value/` = `tantalum-value/src/main/java/tantalum/value/`。

## 阶段 0：改造前基线（代码行为不变）

- [x] 1. 合成数据生成器
  - File: tantalum-examples/src/test/java/tantalum/examples/bench/Corpus.java, CorpusTest.java（profile `bench` 要有入口类才能验证，移到任务 2）
  - 生成 1 万、10 万、50 万事件的 journal：多用户、多会话、含工具轮、压缩、一个 500 条消息的长会话；固定随机种子
  - 验证：生成的 journal 能被现有代码完整回放；同一种子两次生成的事件相同
  - _Requirements: 1.1_

- [x] 2. 读路径基准
  - File: tantalum-examples/src/test/java/tantalum/examples/bench/ReadBench.java, BenchProcess.java；tantalum-examples/pom.xml（profile `bench`）
  - 每个样本一个新进程，测启动时间、堆峰值、会话列表、打开长会话、`/turns`、`/trace`；报告每个样本、中位数和机器信息
  - 验证：`mvn -Pbench` 在 1 万事件上跑通，输出含全部 6 项；另加一个用户有大量事件时测另一用户的 `/turns`
  - _Requirements: 1.1, 5.3_

- [x] 3. 写路径对比
  - File: tantalum-examples/src/test/java/tantalum/examples/bench/WriteBench.java
  - 现有单一 SQLite 对比并行 fsync 的 JSONL（1、10、100 个会话并发），测吞吐和 P50/P99 延迟。JSONL 一侧是最小原型，只放在 bench 里
  - 验证：两侧写入同样数量和大小的事件，完成后读回条数一致
  - _Requirements: 非功能 Performance_

- [x] 4. 核对现有钩子是否满足 H5
  - File: tantalum-examples/、tantalum-shell 中所有 `KernelHook` 实现（只读，发现违反时记录下来，放到任务 28 修）
  - 列出每个钩子读了什么；只能依赖当前运行、当前会话和该用户用量
  - 验证：清单写进 design.md 的新小节
  - _Requirements: H5_

- [x] 5. 跑基线，确认预算（**检查点**，Yi 已于 2026-11-02 确认；50 万事件基线未实测，见需求文档的偏离说明）
  - File: .spec-workflow/steering/tech.md, .spec-workflow/specs/ops-hardening/design.md
  - 跑三个规模，结果写进 tech.md；需求 2.2、2.3、5.3、8.2 的预算写进 design.md；H3 的 30 分钟和 1000 按实测内存校正。如果并行 fsync 明显不够，在这里提出，不继续往下做
  - 验证：向 Yi 汇报数字，得到确认后才开始阶段 1
  - _Requirements: 1.2, H3_

## 阶段 1：每会话文件（只加新类，不接入）

- [x] 6. 文件名和路径
  - File: shell/store/SessionPaths.java, tantalum-shell/src/test/java/tantalum/shell/store/SessionPathsTest.java
  - URL 安全编码，超 120 字节截短加 SHA-256，解析后校验仍在 `sessions/` 内
  - 验证：`../x`、`a/b`、`..`、NUL、超长、Unicode 都不越出目录，不同 ID 不撞名
  - _Requirements: 非功能 Security_

- [x] 7. 写入
  - File: shell/store/SessionFile.java, SessionFileTest.java
  - 头部、追加、`force(true)`、新文件 fsync 目录、权限 600/700、首次写才创建；句柄 LRU
  - 验证：写后读回一致；权限符合；被 LRU 关闭的句柄再写仍正确
  - _Requirements: 1b（3.1b）, 非功能 Security_

- [x] 8. 读取和残缺处理
  - File: shell/store/SessionFileReader.java, SessionFileReaderTest.java
  - 尾部无换行或末行解析失败时截掉并记日志；中间损坏抛异常并指出行号
  - 验证：逐字节截断一个文件，每种截断都恢复出全部完整事件；中间损坏被拒绝，其他文件不受影响
  - _Requirements: 3.1a_

- [x] 9. 格式版本
  - File: shell/store/StoreFormat.java, StoreFormatTest.java
  - 读写 `format.json`；`storage` 比程序新时拒绝并说明，不改动数据；`semantics` 变化只记警告
  - 验证：未来版本拒绝且文件未变；语义变化能启动并有警告
  - _Requirements: 3.4, 3.5_

- [x] 50. 会话 ID 字符集
  - File: shell/web/ApiController.java, 对应测试
  - 入口校验 `[A-Za-z0-9_-]{1,128}`
  - 验证：不符合的返回 400 且不写事件；控制台生成的 ID 通过
  - _Requirements: 11.4, H14_

- [x] 51. 运行 ID 和命令 ID 格式
  - File: kernel/step/ModelSteps.java, CompactSteps.java, CommandIds.java, Outcome.java；shell/conversation/Transcript.java；shell/web/ApiController.java（`runOrder`、从运行 ID 取会话）；写死 `run-` 的测试和控制台文件
  - `<会话ID>_<序号>`、`<会话ID>_<序号>-<下标>`；这一步序号仍是全局的
  - 验证：`mvn verify`、vitest 通过；格式不对的运行 ID 和别人的运行返回 404
  - _Requirements: 11.5, H14_

## 阶段 2：写入管线（接入后行为仍是全量内存）

- [x] 10. 写入分发
  - File: shell/loop/JournalDispatcher.java, JournalDispatcherTest.java
  - 消费 Input Ring，取放行名额（总数 = Cleanroom Ring 大小），放进用户通道
  - 验证：名额用完时分发等待、Input Ring 满后入口 429；已落盘事件不丢
  - _Requirements: H12_

- [x] 45. 按用户的序号
  - File: shell/loop/Sequence.java, SequenceTest.java
  - 接口和实现：`next(用户)`；第一次见到用户时读他的文件尾部；上限 2^53；测试用一个跳号的实现
  - 验证：重启后新序号大于该用户已有的序号；只读了该用户的文件；超上限时报错而不是静默溢出
  - _Requirements: 11.2, 11.3, H14_

- [x] 11. 用户通道
  - File: shell/loop/UserLane.java, UserLaneTest.java
  - 每用户一个虚拟线程，没事件就退出；按到达顺序取号，按会话分组并行写，全部完成后按序号发布
  - 验证：同一用户并发提交时发布严格按序；写失败置 `halted`；通道退出和新事件到达竞争时不丢事件
  - _Requirements: H12, 3.1b_

- [x] 12. 慢用户隔离
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/LaneIsolationTest.java
  - 注入放慢 fsync 的写入器
  - 验证：A 卡住时 B 的事件正常到达 cleanroom；A 内部仍按序
  - _Requirements: 2.9_

- [x] 13. 切换 cleanroom 和启动
  - File: shell/loop/Cleanroom.java, shell/boot/Engine.java, shell/boot/Startup.java；删除 JournalWriter、Journal；tantalum-examples 的 Corpus 改为生成每会话文件
  - cleanroom 改从 Cleanroom Ring 取事件；启动暂时全量重放所有文件；`TurnQuery` 改读文件；读取不再要求序号连续；有旧 `journal.db` 且无 `format.json` 时拒绝启动
  - 验证：现有后端测试全部通过（必要的改动逐条列出）；两个用户序号相同时运行和命令不串；旧 `journal.db` 的目录启动被拒绝且文件未变；Corpus 生成的数据能完整回放
  - _Requirements: H1, H12, 3.1, 3.1b, 11.1_

- [x] 14. SSE 每用户位置
  - File: shell/sse/SseHub.java, SseHubTest.java
  - `recent`、`evictedSeq` 改每用户
  - 验证：先写失败测试：不同用户乱序到达时，现有实现的补读位置算错；修后正确；`Last-Event-ID` 语义不变
  - _Requirements: H12_

- [x] 15. 阶段回归
  - File: 无新文件
  - 验证：`mvn verify` 全量和现有并发压测通过，并用跳号的 `Sequence` 再跑一次；`kill -9` 后事件不丢（手工跑一次，自动化在任务 42）
  - _Requirements: 3.1a, 3.1b, 11.1_

## 阶段 3：索引库

- [x] 16. 索引库和写入线程
  - File: shell/store/SessionIndex.java, IndexWriter.java, SessionIndexTest.java；shell/loop/Cleanroom.java
  - 三张表；cleanroom 只把变化放进内存队列；运行结束和回收时必写，其余 200 事件或 5 秒（可配）；写失败只记日志
  - 验证：节流和必写的时机；写失败时服务继续；cleanroom 没有 I/O（ArchUnit 或测试断言）
  - _Requirements: H6, 非功能 Reliability_

- [x] 17. 用量表
  - File: shell/store/UsageLedger.java, UsageLedgerTest.java；shell/loop/Cleanroom.java
  - 比较 `kernel.step` 前后的 `PrincipalState` 写入；提供“截至序号的累计”
  - 验证：迟到的回复不计；跨天；同一用户多会话交错时累计正确
  - _Requirements: H4, 3.6_

- [x] 18. 归属
  - File: shell/web/ApiController.java, SessionIndex.java, OwnershipTest.java
  - 提交时 `INSERT OR IGNORE` 并读回比较
  - 验证：两个用户并发抢同一新会话 ID，只有一个成功，另一个 404 且不写事件
  - _Requirements: 2.8_

- [x] 19. 去重
  - File: shell/loop/SubmissionLedger.java, SubmissionLedgerTest.java
  - 内存占位 10 万条，未命中查索引；补上 `CompactRequested`
  - 验证：先写失败测试：重启后重复的手动压缩被重复执行；修后去重；超出占位的旧 `requestId` 仍被去重
  - _Requirements: 3.3, H10_

- [x] 20. 启动补齐和重建
  - File: shell/store/IndexRecovery.java, IndexRecoveryTest.java；shell/boot/Startup.java
  - 扫描文件大小，和 `file_offset` 比较，落后的重放尾部；索引缺失、损坏或版本不符时全部重建
  - 验证：删库后重建结果和原库相同；落后的索引被补齐；损坏时有警告且能启动
  - _Requirements: 3.1c, 3.2, 4.2_

## 阶段 4：崩溃收尾（H9）

- [ ] 22. `RunInterrupted` 事件和内核处理
  - File: value/（事件和编解码）, kernel/step/（新步骤）, 对应测试
  - 补“结果未知”，并入未消费的插话，运行以 `CANCELLED` 结束，详情 `interrupted by restart`，排队提交照常开始
  - 验证：每个等待状态（模型、工具、摘要、钩子）下收尾后配对完整；不发命令重新执行模型和工具
  - _Requirements: 2.1a, H9_

- [ ] 23. 删除“待恢复”
  - File: value/（`RunState.pendingRecovery`）, shell/web/ApiController.java, 控制台相关文件, core-skeleton 相关测试
  - 启动时发布 `RunInterrupted`；删除状态、接口字段和界面内容
  - 验证：`grep pendingRecovery` 无结果；vitest 通过
  - _Requirements: H9, 2.1_

- [ ] 24. 真实 Claude 严格校验
  - File: tantalum-examples/src/test/java/tantalum/examples/ClaudeScenarioTest.java
  - 风险：合成的工具结果和中断后的历史只有真实服务能校验。判定：工具调用途中收尾后继续提交，严格校验模式下无 400
  - 验证：真跑一次，报用量，结论写进 tech.md
  - _Requirements: 9.5_

## 阶段 5：按需载入和回收

- [ ] 25. 会话重放
  - File: shell/store/SessionReplay.java, SessionReplayTest.java；kernel/（`KernelState.without`）
  - 读一个文件，从空状态重放，每个事件前注入截至该序号的用量
  - 验证：多用户交错下，结果和全量回放中该会话的部分逐字节相同
  - _Requirements: 5.4, 3.6, H2, H4_

- [ ] 46. 快照编解码
  - File: shell/store/SessionSnapshot.java, SessionSnapshotTest.java；shell/conversation/Transcript.java（导出和恢复内部状态）
  - 含 `SessionState`、`RunState` 和 `Transcript` 内部状态；校验和
  - 验证：覆盖压缩、工具轮、记忆变化的状态，编码再解码后相等；恢复的 `Transcript` 接着处理事件，结果和未中断时相同
  - _Requirements: 10.2, H13_

- [ ] 47. 快照载入和校验
  - File: shell/store/SessionReplay.java, SessionReplayTest.java
  - 读快照，从 `offset` 重放尾部；校验校验和、版本、`offset` 和该位置的序号
  - 验证：只读了尾部事件（计数）；截断、改字节、超前、版本不符都回到全量重放并有警告；删除 `snapshots/` 后结果不变
  - _Requirements: 10.1, 10.3, H13_

- [ ] 26. 钉住计数
  - File: shell/loop/Residency.java, ResidencyTest.java
  - 通道判断前加一，cleanroom 处理完减一
  - 验证：计数不为零时不能回收；并发增减后归零
  - _Requirements: 2.7_

- [ ] 27. 载入
  - File: shell/loop/UserLane.java, Cleanroom.java, shell/conversation/Transcript.java
  - 通道载入冷会话，切片随事件进环，cleanroom 处理前并入；载入失败时该运行以 FAILED 结束
  - 验证：载入大会话时其他会话的事件不被阻塞；载入失败不影响其他会话
  - _Requirements: 2.4, 2.6, H7_

- [ ] 28. 回收
  - File: shell/loop/Evictor.java, EvictorTest.java；shell/loop/Cleanroom.java；任务 4 发现的违规钩子
  - 钉住为零、无活动运行、无排队提交，且空闲超时或超过上限；先写投影缓存再移除
  - 验证：有活动运行或排队的不被回收；超上限回收最久未用的
  - _Requirements: 2.5, H3, H5_

- [ ] 48. 快照写入
  - File: shell/store/SnapshotWriter.java, SnapshotWriterTest.java；shell/loop/Cleanroom.java, Evictor.java
  - 回收前必写，运行结束且超过 200 事件时写，有活动运行不写；写临时文件再重命名；权限 600/700
  - 验证：触发时机；写失败时运行不受影响、旧快照还在；写到一半崩溃时旧快照或全量重放仍正确；cleanroom 没有文件 I/O
  - _Requirements: 10.4, 10.5, 非功能 Security_

- [ ] 29. 不变量测试
  - File: tantalum-shell/src/test/java/tantalum/shell/loop/EvictionInvarianceTest.java
  - “从不回收”“每个事件后回收”“每次都从快照载入”三种配置
  - 验证：prompt、对话视图、钩子结果逐字节相同，覆盖压缩、工具轮、记忆变化、额度钩子
  - _Requirements: 9.1, 2.4, 10.2_

- [ ] 30. 冷会话只读查询和启动
  - File: shell/store/ColdReads.java, ColdReadsTest.java；shell/loop/StateView.java；shell/boot/Startup.java
  - 查询重放到私有状态，LRU 16 个，文件大小判断变化；启动只载入未结束的会话
  - 验证：查询不改变常驻；文件变化后不读到旧结果；别人的会话 404 且没有读文件；启动后常驻数等于未结束会话数
  - _Requirements: 2.1, 2.8_

- [ ] 31. 会话列表
  - File: shell/web/ApiController.java, 对应测试
  - 只读索引并补读落后部分
  - 验证：列表不载入内容；崩溃后和文件一致
  - _Requirements: 4.1, 4.2_

- [ ] 32. 回收压测
  - File: tantalum-examples 或 tantalum-shell 现有并发压测
  - 回收和载入不断发生，含迟到的工具结果
  - 验证：已有不变量全部成立，事件不丢不重复
  - _Requirements: 9.2, 2.7_

## 阶段 6：回放接口

- [ ] 33. `/turns`、`/trace` 按会话读
  - File: shell/journal/TurnQuery.java, 对应测试
  - 从运行 ID 得到会话，只读该文件和用量表
  - 验证：统计读取的文件，只有一个；结果和全量回放相同
  - _Requirements: 5.1, 5.4_

- [ ] 34. SSE 超范围补读
  - File: shell/sse/SseHub.java 或新类 shell/sse/Catchup.java，对应测试
  - 取该用户 `last_seq` 大于位置的会话和常驻会话，分别重放，按（序号，下标）合并
  - 验证：索引落后时不缺事件；只读该用户的文件
  - _Requirements: 5.2_

## 阶段 7：历史分页

- [ ] 35. 分页接口
  - File: shell/web/ApiController.java, 对应测试
  - `?before=<seq>`、`hasMore`；按运行取，至少 50 条且跨 2 个运行，最多 500 条
  - 验证：边界（单运行超 500 条、不足一页、第一页）；各页拼接等于完整历史
  - _Requirements: 8.1_

- [ ] 36. 控制台分页
  - File: ConversationStore 及对话视图组件，vitest 测试
  - `prependItems`，滚到顶部加载，用高度差保持位置
  - 验证：分页和 SSE 交错不缺不重复；断线重连同样
  - _Requirements: 8.2, 8.3_

## 阶段 8：连接和关闭

- [ ] 37. 按登录会话断开
  - File: shell/sse/SseHub.java, shell/web/AuthController.java, 对应测试
  - `subscribe` 带 `HttpSession` ID，退出只断这一个，停用才断全部
  - 验证：一个用户两个登录，退出一个，另一个继续
  - _Requirements: 6.1_

- [ ] 38. 过期和停用
  - File: shell/sse/SseHub.java, shell/auth/UserStore.java, 对应测试
  - 口令戳列；`pump` 每个心跳查三件事
  - 验证：过期、停用、改密码（含命令行在服务外修改）都在一个心跳内断开
  - _Requirements: 6.2, 6.3_

- [ ] 39. 关闭顺序
  - File: shell/boot/Engine.java, TantalumProperties.java, application 配置, 对应测试
  - 按设计 6 步；每步失败不跳过后面的；`shutdown-wait` 默认 30 秒
  - 验证：已返回 200 的提交重启后可见；挂起的模型调用不拖过上限；中间一步抛错后面仍执行
  - _Requirements: 7.1, 7.2, 7.3, H8_

## 阶段 9：验收

- [ ] 40. 预算进 bench
  - File: tantalum-examples/src/test/java/tantalum/examples/bench/
  - 任务 5 确认的预算写进代码，不能用环境变量放宽；对比基线写进 tech.md
  - 验证：故意调低预算时 `bench` 失败；真实预算下通过
  - _Requirements: 1.3, 2.2, 2.3, 5.3, 8.2_

- [ ] 49. 快照效果
  - File: tantalum-examples/src/test/java/tantalum/examples/bench/
  - 长会话冷载入，有无快照各测一次；同时量写快照的耗时和文件大小
  - 验证：两组数字进 tech.md 并汇报；如果快照没有明显提速，汇报给 Yi，不默认保留
  - _Requirements: 10.6_

- [ ] 41. 安全端到端
  - File: tantalum-examples 集成测试
  - 验证：恶意 ID 经 HTTP 不越出目录；权限 600/700
  - _Requirements: 非功能 Security_

- [ ] 42. `kill -9` 崩溃测试
  - File: tantalum-examples 集成测试（打包的 jar）
  - 验证：已提交事件不丢，缓存补齐，运行收尾，模型和工具调用次数没有增加
  - _Requirements: 9.3_

- [ ] 43. Playwright
  - File: 控制台 e2e
  - 两个上下文同一用户，一个退出另一个仍收事件；回收的会话打开完整；长会话分页滚动不跳；崩溃后的运行显示已中断且能继续
  - 验证：真跑通过，截图看过
  - _Requirements: 9.4, 8.2_

- [ ] 44. 文档和全量回归
  - File: README.md, .spec-workflow/steering/tech.md
  - 回收配置、钩子约束、回放承诺的边界、旧 `journal.db` 被拒绝时怎么处理、启动日志
  - 验证：`mvn verify`、vitest、Playwright 全量通过；报真实模型用量
  - _Requirements: 非功能 Usability, H5_
