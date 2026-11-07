# Tasks Document

阶段 1（任务 1–9）按序做，每个任务跑所在模块的测试；阶段末全量回归、部署 8091、提交。阶段 2–5 的任务在各自开始前细化。

- [x] 1. 回执和认领
  - File: `loop/SubmissionLedger.java`、`loop/Receipts.java`、`loop/Intake.java`、`boot/Storage.java`、`boot/Engine.java`、`loop/LaneDispatcher.java`
  - `claim(principal, request, onNew)` 在临界区内登记回执；`Receipts.fail`、`failAll`；发布路径异常全退回；索引失败 503；关机和停机时 `failAll`
  - 测试：`IntakeTest`、`ReceiptsTest`（或并入现有）
  - _Requirements: 1.1–1.5_

- [x] 2. 用户通道兜底
  - File: `loop/UserLane.java`、`loop/LaneDispatcher.java`、`loop/UserPermits.java`
  - `drain` 的 Throwable 兜底、pin 进受保护区、`afterDurable` catch Throwable、`publish` 异常、分发器中断归还、停机后 `discardAll`
  - 测试：`UserLaneTest`、`LaneDispatcherTest`、`UserPermitsTest`
  - _Requirements: 2.1–2.7_

- [x] 3. journal 写失败
  - File: `journal/SessionFile.java`、`journal/SessionFiles.java`、`journal/RecordCodec.java`、`journal/SessionFileReader.java`、`journal/StoreLock.java`、`journal/EventCodec.java`
  - 截回、句柄作废、`deepCopy`、decode 的 catch、行缓冲上限、锁失败关通道、语言全覆盖
  - 测试：`SessionFileTest`、`RecordCodecTest`、`SessionFileReaderTest`、`StoreLockTest`、`EventCodecTest`
  - _Requirements: 3.1–3.6_

- [x] 4. 驻留
  - File: `loop/Residency.java`、`loop/UserLane.java`、`loop/Cleanroom.java`
  - `Pinned`、过期回收、`cold`、已有运行的 `LOAD_FAILED`
  - 测试：`ResidencyTest`、`CleanroomTest`
  - _Requirements: 4.1–4.4_

- [x] 5. 内核：异常与重复结果
  - File: `value/decision/StepFailed.java`（新）、`kernel/Kernel.java`、`step/CancelSteps.java`、`step/ToolSteps.java`、`step/ModelSteps.java`、`journal/RecordCodec.java`
  - 测试：`KernelTest`、`CancelStepsTest`、`ToolStepsTest`、`ModelStepsTest`、`RecordCodecTest`
  - _Requirements: 5.1–5.4_

- [x] 6. 内核：排队模型、首次冻结、续写、附件提问
  - File: `value/state/SessionState.java`、`value/decision/SessionChange.java`、`step/SubmitSteps.java`、`step/RunEnd.java`、`step/ContextSteps.java`、`step/Resume.java`、`compaction/Compactor.java`、`journal/RecordCodec.java`
  - 先 `grep followUpModel` 8091 数据目录决定字段去留
  - 测试：`SubmitStepsTest`/`RunEndTest`、`ContextStepsTest`、`ResumeTest`、`CompactorTest`、`RecordCodecTest`
  - _Requirements: 5.5–5.8_

- [x] 7. 异常文字
  - File: `value/text/Failures.java`（新）、`kernel/hook/HookChain.java`、`loop/UserLane.java`、`handler/ModelHandler.java`、`handler/ToolHandler.java`、`model/OpenAiClient.java`、`tantalum-connect/Calls.java`
  - 测试：`FailuresTest`、各处现有测试的断言改法
  - _Requirements: 6.1–6.3_

- [x] 8. 钩子说明按钩子比较
  - File: `value/state/SessionContext.java`、`kernel/hook/HookPatches.java`、`HookPatchesTest.java`
  - _Requirements: 7.1_

- [x] 9. 阶段 1 收尾
  - 全量回归、8091 部署、tech.md 记一节、提交推送
  - _Requirements: 9.1, 9.2_

- [ ] 10. 阶段 2：看门狗持有流
  - File: `model/StreamWatchdog.java`、`model/ClaudeClient.java`、`model/OpenAiClient.java`、`StreamWatchdogTest.java`（新）
  - _Requirements: 10.1_

- [ ] 11. 阶段 2：Claude 流的块与失败分类
  - File: `model/ClaudeClient.java`、`ClaudeClientTest.java`
  - _Requirements: 10.2, 10.6_

- [ ] 12. 阶段 2：Claude 请求的空块与思考块
  - File: `model/ClaudeRequest.java`、`ClaudeRequestTest.java`
  - _Requirements: 10.3_

- [ ] 13. 阶段 2：OpenAI 流
  - File: `model/OpenAiClient.java`、`OpenAiClientTest.java`
  - _Requirements: 10.4, 10.5_

- [ ] 14. 阶段 2 收尾：全量回归、8091 部署、tech.md、提交

- [ ] 15. 阶段 3：登录与安全加固（开始前细化）
  - _Requirements: 11_

- [ ] 16. 阶段 4：去重与归位（开始前细化）
  - _Requirements: 12_

- [ ] 17. 阶段 5：拆大类与清注释（开始前细化）
  - _Requirements: 13_
