# Tasks Document

工作方式：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志。每个阶段结束：
- 跑全量回归：`mvn -o clean verify`（含 `JarCrashIT`）、跳号回归、vitest、Playwright；
- 做一次变异检查；
- 提交一次；
- 向 Yi 汇报。

提交前扫描密钥。现有测试只因构造参数变化而改，断言不放宽。设计里写的数字和交互参数照设计做，偏离时写进汇报。

路径前缀：`value/` = `tantalum-value/src/main/java/tantalum/value/`，`kernel/` = `tantalum-kernel/src/main/java/tantalum/kernel/`，`shell/` = `tantalum-shell/src/main/java/tantalum/shell/`，`console/` = `console/src/`。

## 阶段 1：数据

- [x] 1. `HookDecided` 事实
  - File: value/decision/HookDecided.java（新）、kernel/hook/HookChain.java；测试 HookChainTest
  - 设计第 1 节：每个钩子返回拦截、要求工作、带补丁的放行时报一条；只放行、没有补丁时不报；钩子失败导致拦截时，在 `HookReported` 之后再报一条 STOP。字段：钩子名、位置、结果、理由、system 补丁长度、工具名、工具调用 ID、工作类型
  - 测试：表驱动，覆盖 6 个位置 × 3 种结果、空补丁不报、多个钩子的顺序、回放得到同样的事实
  - _Requirements: 5.3–5.8, T1, T2_

- [x] 2. 模型、工具、工作结果事件的计时和请求选项字段
  - File: value/model/RequestOptions.java（新）；value/event/{ModelReplied,ModelFailed,ModelInterrupted,ToolFinished,WorkDone}.java；全部构造调用处
  - 模型结果加 `startedAt`、`firstTokenAt`、`options`；工具和工作结果加 `startedAt`；都可为空，为空时不写出
  - 测试（EventCodecTest）：新字段往返；没有这些字段的旧行读成 null；字段为空时写出的行和改动前逐字节相同
  - _Requirements: T3, 设计第 2、3 节_

- [x] 3. 量首 token 和开始时刻
  - File: shell/model/ChunkListener.java（`onToolInput` 默认方法）、ClaudeClient、OpenAiClient（调用它）、shell/handler/{ModelHandler,ToolHandler,WorkHandler}.java（注入 Clock）
  - 测试：先发文字、先发思考、只发工具输入三种情况，首 token 都等于第一个分片到达的时刻；`startedAt` 在拿到并发名额之后
  - _Requirements: 2.1, 设计第 2、3 节_

- [x] 4. 请求选项，以及拆出 `ClaudeRequest`
  - File: shell/model/ModelClient.java（`options(Prompt)` 默认方法）、ClaudeRequest.java（新，从 ClaudeClient 移出组请求的代码）、OpenAiClient
  - 先对现有请求体取样存成期望文件，重构后同一 prompt 组出的请求体逐字节相同；选项和请求体一致；选项、journal、轨迹 JSON 里都没有密钥
  - _Requirements: 2.1, 2.7, 设计第 2 节_

- [x] 5. 轨迹记录和投影
  - File: shell/trace/{TraceRecord,TracePass,TraceRequests,TraceTools,TraceHooks,Trace,TraceJson}.java（新）；kernel/step/RetrySteps.java（`MAX_RETRIES` 改成公开）
  - 9 种记录；轮、步、压缩组、请求编号、重试合并、请求前记录的“待定步号”、system 和工具变化、分段来源、工具状态、MCP 拆名，都照设计第 4 节
  - 测试 TracePassTest：每种记录、每种工具状态、重试合并（含重试途中的摘要调用、重试等待期间的插话）、请求编号跨运行连续、手动压缩进“轮次之间”、分段来源和“未采用”、`Saved` 恢复后接着处理和不中断时相同
  - _Requirements: 1.1–1.4, 2.3, 3.5–3.7, 4.1–4.6, 5.1–5.8_

- [x] 6. 实时推送、冷载入和补读
  - File: shell/loop/Projections.java（新）、Cleanroom、Evictor、SessionSlice、FactListener；shell/store/{SessionReplay,TurnQuery}.java；shell/sse/SseHub.java
  - 每个事件只经过一次轨迹投影，结果作为 SSE `trace` 消息推送；冷载入时由同一次重放算出；断线补读和实时的编号规则一致
  - _Requirements: 9.1, 设计第 5 节_

- [x] 7. 轨迹接口
  - File: shell/store/{TraceQuery,TracePage}.java（新）、shell/web/TraceController.java（新）、SessionQueries
  - 三个接口：分页取记录（按运行从新到旧，`before` 游标）、按运行定位（`run=`）、单次调用的完整输入（`/trace/calls/{命令}`）。不是本人的会话一律 404
  - 测试：两个账号互查返回 404；分页边界；单次调用的 prompt 和 `/turns/{n}` 的一致
  - _Requirements: 1.5, 1.10, 2.1, 9.4_

- [x] 8. 格式基准和“实时 = 回放”
  - File: WireFormatTest；测试资源 wire/{trace.json,trace-sse.txt}
  - 场景带插件钩子（补丁、拦工具、要工作）、并行工具、重试、自动压缩、手动压缩、重启中断；另加一项：同一串事件在实时投影里中途做一次恢复，合并后和重放出的页面逐字节相同。现有的 `items.jsonl`、`sse.txt` 不变
  - _Requirements: 9.2, 9.3_

- [x] 9. 性能和真实模型
  - `ReadBench` 加 `trace_page_ms`：1890 个事件的会话第一页不超过 1 秒；冷载入耗时比改动前增加不超过 20%；`ConcurrencyStressTest` 对比推送吞吐
  - 真实 Claude 跑一次：`ClaudeSmokeTest` 断言 `startedAt ≤ firstTokenAt ≤ 结果时刻`、选项和配置一致、journal 里没有密钥；跑一次思考签名多轮的场景（因为请求代码被重构过）。报用量
  - _Requirements: 非功能“性能”“测试”_

- [x] 10. 阶段 1 收尾
  - README：journal 新字段、轨迹接口、`HookDecided`。全量回归；变异检查（让 `HookChain` 也为空补丁的放行报事实；让 `ModelHandler` 记最后一个分片）；提交；汇报
  - _Requirements: 设计“阶段”_

## 阶段 2：记录表和检查器

- [x] 11. 依赖和接口类型
  - File: console/package.json（`@tanstack/react-virtual`、`diff`，固定版本）、THIRD_PARTY_NOTICES.md、console/api/{types,client}.ts
  - 检查加上依赖后，gzip 后的总大小不超过 300 KB
  - _Requirements: 设计第 7 节_

- [x] 12. `TraceStore` 和推送合并
  - File: console/trace/{TraceStore,hooks}.ts（新）、console/store/events.ts
  - 记录按 ID 保存、按版本合并，旧版本的推送丢掉；翻页状态；轨迹和统计共用一个去重的“加载更早”
  - 测试：直接读后端的期望文件，页面从中间切开，再按顺序套上推送，结果等于完整页面
  - _Requirements: 1.5, 1.6, 8.4, 9.1_

- [x] 13. 分组、搜索、格式化
  - File: console/trace/{ledger,search,format}.ts（新）
  - 记录分到轮、步、行；收起后的摘要；搜索覆盖收起的内容；时刻在本地时间和 Unix 时间戳之间切换
  - _Requirements: 1.1–1.4, 1.7, 1.8, 2.6_

- [x] 14. 记录表和工具栏
  - File: console/trace/{TracePage,Toolbar,Ledger,LedgerRow}.tsx
  - 虚拟滚动、跟随末尾、滚到顶部加载更早的轮、一键展开收起、搜索、窄屏换行；列和阈值照设计第 7 节
  - _Requirements: 1.1–1.9_

- [x] 15. 检查器
  - File: console/trace/inspector/{Inspector,RequestTabs,ToolTabs,SystemTabs,RecordTabs,JsonTree,LongText,LineDiff}.tsx
  - 各类记录的页签、可拖宽度（双击恢复、键盘每次 16 px、记住宽度）、Esc 关闭、JSON 树和复制、换行开关、按行显示差异、来源标注；请求详情按需加载
  - _Requirements: 2.1–2.7, 3.1–3.8, 4.1–4.6, 5.1–5.6, 7.1–7.3_

- [x] 16. 路由、跳转、替换旧页
  - File: console/app/{router.ts,App.tsx}、console/process/ToolRow.tsx、console/conversation/RunStatusRow.tsx、console/text/zh.ts；后端删 `/api/runs/{run}/trace` 和 `TurnQuery.Trace`
  - 旧链接定位到那个运行；对话页可以跳到轨迹里对应的记录；390 px 宽时检查器盖在记录表上，页面不出现横向滚动
  - _Requirements: 1.10, 9.5_

- [x] 17. 端到端测试和截图
  - File: console/e2e/trace.spec.ts（新）、E2eServer（注册示例插件和一个加补丁的测试钩子）、E2eModel（加一次 429 后成功、一批并行工具加一个 MCP 调用）
  - 每类记录都能从记录表打开；钩子行和工作行互相跳转；刷新后页面相同；手机宽度。在 1280 px 和 390 px、深浅色下截图，和 DSH 对照后发给 Yi
  - _Requirements: 非功能“外观”“测试”_

- [x] 18. 阶段 2 收尾
  - 全量回归；变异检查（让分组把请求前的记录归到当前步；让 `TraceStore` 接受旧版本的推送）；提交；汇报
  - _Requirements: 设计“阶段”_

## 阶段 3：时间概览

- [x] 19. 时间概览的计算
  - File: console/trace/timeline.ts（新）
  - 四条道：输入、模型、工具、插件。两种画法：按实际时长（压掉没有任何活动的空档）、每个操作等宽。助手的条分成首 token 和生成两段；进行中的只画开始标记；按闭区间过滤记录；视口的缩放和平移
  - _Requirements: 6.1, 6.5–6.8, 6.11_

- [x] 20. 时间概览的交互
  - File: console/trace/Timeline.tsx（新）、Toolbar.tsx
  - 悬停 500 ms 出提示；移动小于 3 px 算点击；点中一段就选中，点空白处让记录表滚到最近的记录；拖选区间；滚轮缩放；右键清除；放大后右键拖动平移；靠边自动平移；段可以用 Tab 聚焦、Enter 选中；最小可点宽度
  - _Requirements: 6.2–6.10_

- [x] 21. 阶段 3 收尾
  - vitest 和 Playwright 覆盖上面的交互；全量回归；变异检查（区间改成开区间；去掉最小可点宽度）；提交；汇报
  - _Requirements: 设计“阶段”_

## 阶段 4：统计

- [x] 22. 统计页签
  - File: console/stats/{StatsPage,StatsTable,metrics}.ts(x)（新）、console/app/{router.ts,App.tsx}、console/text/zh.ts
  - 每轮一行，“触发”列取用户提问、排队提交、手动压缩；展开后每步一行，列出上下文 token、输出 token、缓存命中率（按 token 加权）、首 token 延迟、模型用时、工具用时、输出速度、总用时条；没有记录的显示“未记录”；和轨迹共用分页
  - _Requirements: 8.1–8.5_

- [x] 23. 收尾和端到端
  - Playwright 覆盖统计页签；最终全量端到端；变异检查（命中率改成各步平均；共用加载在轨迹加载中时报“已加载完”）；README、tech.md、BACKLOG、架构页；提交；汇报
  - _Requirements: 设计“阶段”，非功能_
