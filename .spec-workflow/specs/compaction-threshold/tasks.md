# Tasks Document

工作方式：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志。每个阶段结束：
- 跑全量回归（`scratchpad/regress.sh`：控制台构建、`mvn -o clean verify` 含 `JarCrashIT`、跳号回归、vitest、Playwright）；
- 做一次变异检查；
- 提交一次；
- 重新打包、更新 8091 上的服务；
- 向 Yi 汇报。

提交前扫描密钥。现有测试只因构造参数变化而改，断言不放宽；现有期望文件不变（估算系数只对新事件生效，旧录制不受影响；如果某个期望文件因为新字段必须变，写进汇报）。

路径前缀：`value/` = `tantalum-value/src/main/java/tantalum/value/`，`kernel/` = `tantalum-kernel/src/main/java/tantalum/kernel/`，`shell/` = `tantalum-shell/src/main/java/tantalum/shell/`，`console/` = `console/src/`。

## 阶段 1：值和内核

- [x] 1. `CompactionSettings` 加 `point`、`bytesPerToken`
  - File: value/state/CompactionSettings.java
  - `threshold()`：`point > 0` 用 `point`，否则 `window − reserve`；校验 `point` 为 0 或在 `(0, window − reserve]`，`bytesPerToken` 为 0 或正数；`estimate()`
  - 测试：新旧两种 `threshold()`；非法值拒绝
  - _Requirements: T4, T7, 需求 3.4_

- [x] 2. `TokenEstimate` 按系数算
  - File: kernel/compaction/TokenEstimate.java，及 `Compactor`、`CompactSteps`、`SummaryPrompt` 的调用处
  - 记录 `(double bytesPerToken)`，实例方法；`LEGACY`（3）；系数 0 按 3 算；图片、PDF 不受系数影响
  - 测试：1.5、2.0、LEGACY 的文字估算；图片和 PDF 不变；已有压缩测试不改断言
  - _Requirements: T7, 需求 4.1_

- [x] 3. `EventCodec` 兼容旧事件
  - File: shell/journal/EventCodec.java（如需要）
  - 测试：没有新字段的旧 `ContextAssembled` JSON 解码为 `point = 0`、`bytesPerToken = 0`，`threshold()` 等于旧值；新字段往返
  - _Requirements: 需求 3.4_

- [x] 4. 内核每次提问换上新设置
  - File: kernel/step/ContextSteps.java、value/state/SessionContext.java
  - `point > 0` 且和会话里的不同时换上；不追加消息；新设置 null 不换；旧事件不换
  - 测试 `CompactionSwitchTest`：换设置后请求前缀不变；旧事件不换；null 不换；改小后下一次调用前压缩；运行中途不换（同一运行的第二次调用仍用原设置）
  - _Requirements: T6, 需求 3.1–3.6_

- [x] 5. 阶段 1 收尾：回放测试、全量回归、变异检查、提交、部署、汇报

## 阶段 2：shell

- [ ] 6. `WindowSource`：`ClaudeWindow`、`OpenAiWindow`
  - File: shell/model/WindowSource.java、ClaudeWindow.java、OpenAiWindow.java（新）
  - Claude：`models().retrieve` 的 `maxInputTokens`；开着备用模型时 beta 接口取 `allowed_fallback_models`，各取窗口取最小，失败只用主模型。OpenAI：`/models` 里同名模型的 `meta.n_ctx`、`max_model_len`、`context_length`
  - 测试（`HttpStub`）：各字段、顺序、名字对不上、缺字段、非 JSON、HTTP 错误；备用模型最小值和备用失败
  - _Requirements: T1, T3, 需求 1.1, 1.2, 1.6_

- [ ] 7. `ContextWindow`
  - File: shell/model/ContextWindow.java（新）
  - 启动最多等 5 秒；每 10 分钟后台读；失败保留；≤ R 当失败；`refreshSoon` 合并；日志不含响应体和请求头
  - 测试（假的 `WindowSource`、可控时钟）：上面每条
  - _Requirements: T2, 需求 1.3–1.5, 1.7_

- [ ] 8. `UserSettings`
  - File: shell/settings/UserSettings.java（新）
  - `users.db` 表 `user_settings`；默认 100；四个值之外拒绝；内存缓存
  - 测试：默认、保存后重开、两个用户互不影响、非法值、和 `plugin_settings` 同库共存
  - _Requirements: T5, 需求 2.3, 2.4_

- [ ] 9. `CompactionPolicy`
  - File: shell/settings/CompactionPolicy.java（新），shell/boot/TantalumProperties.java（`bytes-per-token`）
  - `settings(principal)`、`points()`；R 的算法从 `TantalumProperties.compactionSettings()` 搬过来
  - 测试：需求 T4 的两组例子；W 未知；W ≤ R；系数按供应商
  - _Requirements: T4, T7, 需求 4.2_

- [ ] 10. 接线
  - File: shell/boot/Engine.java、shell/handler/ContextHandler.java、shell/handler/ModelHandler.java、shell/web/ApiController.java
  - `ContextHandler` 每次按用户算设置；超长时 `refreshSoon()`；`/api/config` 的 `contextWindow`、`windowSource`
  - 测试：同一会话改了设置后下一次 `ContextAssembled` 带新设置；`/api/config` 的来源
  - _Requirements: 需求 1.8, 3.1_

- [ ] 11. `SettingsController`
  - File: shell/web/SettingsController.java（新）
  - `GET /api/settings`、`PUT /api/settings/compaction`
  - 测试 `SettingsApiTest`：默认、保存、400、401、两个用户
  - _Requirements: 需求 2.2–2.5_

- [ ] 12. 阶段 2 收尾：全量回归、变异检查、提交、部署（确认 8091 上 `/api/config` 是 1000000、来源 model）、汇报

## 阶段 3：控制台

- [ ] 13. 接口和查询
  - File: console/api/types.ts、console/api/client.ts、console/settings/useCompaction.ts（新）
  - 乐观更新、失败回滚、成功后让设置查询失效
  - 测试：保存失败回滚、服务连不上也回滚
  - _Requirements: 需求 2.2_

- [ ] 14. 设置页“模型”一栏
  - File: console/settings/ModelPane.tsx、console/text/zh.ts
  - 来源、四个选项和 token 数、生效说明、窗口未知时不可选
  - 测试：显示、选择、未知窗口
  - _Requirements: T8, 需求 2.1, 2.5_

- [ ] 15. 状态栏
  - File: console/composer/status.ts、console/composer/StatusBar.tsx
  - 按压缩点的 90% 提醒；悬停说明“到 X% 时自动压缩”
  - 测试：`statusParts` 边界；改设置后不刷新就变
  - _Requirements: T9, 需求 5_

- [ ] 16. e2e `compaction-settings.spec.ts`
  - File: console/e2e/compaction-settings.spec.ts（新）
  - 设计里的 4 项；用 `e2e2`，结束时恢复
  - _Requirements: 需求 2, 3, 5_

- [ ] 17. 阶段 3 收尾：全量回归、变异检查、提交、部署、汇报

## 阶段 4：真实模型、文档、收尾

- [ ] 18. 估算样本
  - File: tantalum-kernel/src/test/resources/estimate-samples.txt（新）、对应测试
  - 用 `count_tokens` 和 qwen 的 `prompt_tokens` 测 Java、中文、英文、JSON 样本；断言新估算不小于真实值
  - _Requirements: 需求 4.4_

- [ ] 19. 真实模型测试
  - File: tantalum-examples/src/test/java/tantalum/examples/WindowCompactionTest.java（新）
  - Claude 窗口 1M；Claude 50% 下 16 个十六进制文件分两次读的压缩；qwen 同样流程。判定和花费记进实现日志
  - _Requirements: 测试（真实模型）_

- [ ] 20. 文档
  - File: README.md、.spec-workflow/steering/tech.md、.spec-workflow/BACKLOG.md、context-compaction 需求的术语更正说明
  - _Requirements: 全部_

- [ ] 21. 收尾：全量回归、提交、部署、架构页更新、汇报（含用量）
