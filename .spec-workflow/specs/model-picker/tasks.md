# Tasks Document

工作方式：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志。

每个阶段结束：
- 跑全量回归（`scratchpad/regress.sh`：控制台构建、`mvn -o clean verify` 含 `JarCrashIT`、vitest、Playwright）；
- 提交一次；
- 重新打包，复制到 `scratchpad/app/tantalum.jar`，跑 `up.sh` 更新 8091；
- 用一两句话向 Yi 汇报。

构建：不加新依赖，一律 `mvn -o … -am`；单独跑某个模块的测试也要带 `-am`，否则用到 `~/.m2` 里的旧包。

提交前扫描密钥。搬过来的测试断言不放宽。安全规则（每个任务都适用）：
- API 密钥只从环境变量读，不进配置文件、日志、journal、接口返回、测试资源和输出；
- 给普通用户的接口不返回路线地址和密钥变量名；
- 路线相关的日志只记路线名、模型名、错误类别和状态码。

路径前缀：
- `value/` = `tantalum-value/src/main/java/tantalum/value/`
- `kernel/` = `tantalum-kernel/src/main/java/tantalum/kernel/`
- `kerneltest/` = `tantalum-kernel/src/test/java/tantalum/kernel/`
- `shell/` = `tantalum-shell/src/main/java/tantalum/shell/`
- `shelltest/` = `tantalum-shell/src/test/java/tantalum/shell/`
- `examples/` = `tantalum-examples/src/test/java/tantalum/examples/`
- `console/` = `console/src/`
- `e2e/` = `console/e2e/`

每个任务的 `_Prompt` 开头省略的固定部分：“Implement the task for spec model-picker, first run spec-workflow-guide to get the workflow guide then implement the task: 开始时在 tasks.md 把本任务标成 [-]，做完用 log-implementation 记录，再标成 [x]。”

## 阶段 1：值类型、配置和路线（还不接到 agent）

先加记录选择用的值类型和 journal 字段，再改配置，最后做路线、模型列表和档位。这一阶段的类都能单独测；`Engine` 照旧用一个客户端，8091 上看不到变化。

- [x] 1. 值类型和 journal 字段
  - File: value/model/ModelChoice.java、ModelChangeBlock.java（新），value/model/Prompt.java、ReasoningBlock.java、value/event/UserPrompt.java、FollowUpSubmitted.java、ContextAssembled.java、value/command/AssembleContext.java、value/state/SessionContext.java（改），shell/journal/ 下的编解码（改），对应的测试（改）
  - 照设计“`tantalum-value`”加字段，全部可以为空，保留旧构造函数；编解码：为空不写，`ContextAssembled.model` 走 `MatchedCodec` 只在变化时写，`Prompt.model` 照“请求只写相对上一次的变化”写，`ModelChangeBlock` 写成 `{"type":"model_change","from","to"}`
  - 测试：每个字段写了能读回；为空时写出的行和改动前逐字节相同（用现有的旧格式会话文件资源）；`ContextAssembled.model` 不变时 `diff` 行里没有它（需求 7.1）；`WireFormatTest` 照跑
  - _Leverage: shell/journal/MatchedCodec.java, RecordCodec.java, LineCodec.java_
  - _Requirements: M13, 需求 7.1, 7.4_
  - _Prompt: Role: Java 开发 | Task: 加记录模型选择的值类型和 journal 字段 | Restrictions: 不改内核行为；旧行逐字节不变；不升内核语义版本 | Success: 编解码测试和 WireFormatTest 全过_

- [x] 2. 路线的配置
  - File: shell/boot/TantalumProperties.java、新增 shell/boot/RemovedKeys.java（`EnvironmentPostProcessor`）、tantalum-shell/src/main/resources/application.properties、META-INF/spring.factories（改），shelltest/boot/TantalumPropertiesTest.java（改）
  - `Claude` 加 `apiKeyEnv`、`models`、`displayName`，`effort` 只收五个档位；新增 `Map<String, OpenAiRoute> openaiRoutes`（字段照设计 `RouteSettings`）；`provider` 必须是已配的路线；删 `OpenAi`；有 `tantalum.openai.` 开头的键时启动失败，消息照设计原文；路线名只用小写字母、数字、`-`，最长 32，`claude` 保留；`thinking` 不认识、`efforts` 为空、`default-effort` 不在档位里都启动失败；`application.properties` 照设计改。这一步 `TantalumProperties` 先保留 `model()`、`vision()` 这些方法，改成取默认路线的值，任务 10 再删
  - 测试：`tantalum.openai.base-url` 还在时失败、消息对（需求 1.4）；路线名大写、重复、叫 `claude` 时失败（需求 1.5）；`effort=extreme` 失败；`thinking=foo` 失败；`overrides[moonshotai/kimi-k2].thinking` 方括号写法能读到
  - _Requirements: M1, M5, M17, 需求 1.4, 1.5, 9.1_
  - _Prompt: Role: Java 后端开发 | Task: 把模型配置改成路线 | Restrictions: 密钥不进配置；不悄悄忽略旧键；默认值和现在的行为一致（默认路线 claude，claude-sonnet-5-5，medium） | Success: 配置测试全过，现有测试不改断言_

- [x] 3. `Levels` 和 `OpenAiThinking`
  - File: shell/route/Levels.java、shell/model/OpenAiThinking.java（新），shelltest/route/LevelsTest.java、shelltest/model/OpenAiThinkingTest.java（新）
  - `Levels.claude(capabilities, defaultEffort)`：五个档位里 `supported` 的，按顺序；默认档位取法照设计；`Levels.openAi(thinking, efforts, defaultEffort)`：五种写法的档位和默认值。`OpenAiThinking.apply(body, level)` 照设计的表写请求体，`NONE` 什么都不写，不认识的档位抛异常
  - 测试：用 `claude-models.json` 实测数据（任务 4 放进测试资源，这里先用其中两个模型的片段），Sonnet 4.6 没有 `xhigh`、默认 `medium`；路线默认 `xhigh` 而模型没有时取最近的；OpenAI 五种写法各一例（需求 2.6、2.7）；四种请求体和期望文件一致
  - _Leverage: scratchpad/model-picker-spike/claude-models.json, qwen-thinking-*.json_
  - _Requirements: M5, 需求 2.6, 2.7_
  - _Prompt: Role: Java 开发 | Task: 定义思考档位和 OpenAI 兼容的思考参数写法 | Restrictions: 不就近换档位；不认识的写法和档位直接报错 | Success: 测试全过_

- [x] 4. `ClaudeModels`
  - File: shell/route/ModelList.java、ModelInfo.java、Listing.java、ClaudeModels.java（新），shelltest/route/ClaudeModelsTest.java（新），tantalum-shell/src/test/resources/route/claude-models.json（新，从 scratchpad 复制，确认不含密钥）
  - 照设计“`ClaudeModels`”：翻完所有页；去掉不支持 `adaptive` 的并记日志（M3）；允许列表；`fallbacks` 开着时逐个 `retrieve` 取最小窗口（搬 `ClaudeWindow.java:33-60` 的做法）；`maxTokens`、`vision`、`levels`；`toolChanges` 来自 `ClaudeRequest.TOOL_CHANGE_MODELS`
  - 测试（假的 Models 接口返回实测数据）：14 个里留 11 个，去掉的 3 个在日志里（需求 2.3）；允许列表只留两个、列表里有接口里没有的一个写进日志（需求 2.2）；两页的列表翻完；备用模型窗口更小时取小的；Haiku 5.5 的 `toolChanges` 为假
  - _Leverage: shell/model/ClaudeWindow.java_
  - _Requirements: M2, M3, M4, 需求 2.2, 2.3, 2.5, 2.6_
  - _Prompt: Role: Java 后端开发 | Task: 从 Claude Models 接口读模型列表和每个模型的信息 | Restrictions: 用现有官方 SDK；不自己维护模型能力表 | Success: 测试全过_

- [x] 5. `OpenAiModels`
  - File: shell/route/OpenAiModels.java（新），shelltest/route/OpenAiModelsTest.java（新）
  - 照设计“`OpenAiModels`”：`GET {base-url}/models`，有密钥带 `Authorization: Bearer`，5 秒，不跟跳转；窗口字段照 T1 的顺序，`overrides` 优先，都没有用配置并标 `CONFIG`；`list-models=false` 不发请求；`vision` 和档位按 `overrides` → 路线
  - 测试（`com.sun.net.httpserver`）：llama.cpp 的实测返回（`meta.n_ctx` 131072）、vLLM `max_model_len`、LM Studio `context_length` 各一份；允许列表；`list-models=false` 时服务器没收到请求；401 时 `Listing` 的错误类别是“401”，日志里没有地址路径和请求头
  - _Leverage: shell/model/OpenAiWindow.java_
  - _Requirements: M2, M4, M5, 需求 2.2, 2.5, 2.7_
  - _Prompt: Role: Java 后端开发 | Task: 从 OpenAI 兼容接口读模型列表和窗口 | Restrictions: 密钥不进日志；不跟跳转 | Success: 测试全过_

- [x] 6. `Routes` 和 `ChoiceCheck`
  - File: shell/route/Route.java、RouteSettings.java、Routes.java、ChoiceCheck.java（新），shelltest/route/RoutesTest.java、ChoiceCheckTest.java（新），shelltest/ArchitectureTest.java（改）
  - `Routes`：每条路线一个虚拟线程读列表，总共等 5 秒；10 分钟刷新；失败 `OK` → `STALE` 保留模型，从没读到是 `UNAVAILABLE`；`refreshSoon(route)`；密钥变量没设的路线不建、日志写变量名不写值；默认路线的密钥变量没设时 `create` 抛异常（列表读不到不算）；`snapshot()`、`model(choice)`、`defaultChoice()`。`ChoiceCheck`：`problem` 覆盖设计 Error Handling 表的前六行，消息照原文；`usable`。ArchitectureTest 加设计里的两条规则
  - 测试：两条路线一快一慢（慢的 8 秒），`start` 在 5 秒内返回，慢的之后变成 `OK`（需求 2.1）；刷新失败保留（2.4）；没设密钥变量不建（1.2）；默认路线没设密钥变量时启动失败、只是列表读不到时照常启动（1.3）；`ChoiceCheck` 表驱动；规则各变异一次
  - _Leverage: shell/model/ContextWindow.java（时序）_
  - _Requirements: M2, M11, M14, M15, 需求 1.2, 1.3, 2.1, 2.4, NFR Reliability_
  - _Prompt: Role: Java 并发开发 | Task: 管理全部路线的模型列表并检查选择 | Restrictions: 一条路线的失败不影响别的；不在取列表时调上游 | Success: 测试和 ArchitectureTest 全过_

## 阶段 2：内核、请求和接线

内核搬运选择并在变化时追加说明；两个客户端按选择发请求；`Engine` 换成 `Routes`。阶段结束时服务器按会话路由模型（还没有画面，提交不带 `model` 时用默认），8091 行为和现在一样。

- [x] 7. 内核：选择怎么生效
  - File: kernel/step/SubmitSteps.java、ContextSteps.java、CompactSteps.java、RunEnd.java、kernel/projection/PromptProjection.java（改），value/ 下状态视图（改，`toolChangesInHistory`），kerneltest/step/ModelChoiceStepsTest.java（新）
  - 照设计“一次提问怎么选模型”第 3、5、6 步和“换到窗口更小的模型”：运行记下 `requested`；`AssembleContext` 带 `requested`、`current`；路线或模型变了追加 `ModelChangeBlock`，只换档位不追加，旧的为空不追加；`modelBefore` 只给这个运行第一次调用前的自动压缩用，第一次正常回复后清掉；摘要失败时 `RunEnd` 还原并追加反方向的说明；`toolChangesInHistory` 按设计的定义。先确认 follow-up 怎样开始新运行（`SubmitSteps`），据此决定它的选择在哪里生效
  - 测试（零 mock）：设计“Unit Testing → 内核”的四条；`steer` 不改选择（需求 5.2）；已有消息逐字节不变（5.3）
  - _Requirements: M8, M10, M11, M12, M13, 需求 5.1–5.5, 7.4_
  - _Prompt: Role: Java 内核开发 | Task: 让会话的模型选择在每次提问时生效并追加说明 | Restrictions: 只追加，不改已有消息；内核不读 shell；不升语义版本 | Success: 新测试和全部内核测试全过_

- [x] 8. Claude 请求按选择发
  - File: shell/model/ClaudeRequest.java、ClaudeClient.java（改），shelltest/model/ClaudeRequestTest.java（改）、期望文件（改，只加新用例）
  - `model`、`effort` 从 `prompt.model()` 取，为空用默认；`Settings` 改名照设计；回复里的推理块填 `route`、`model`（实际模型）；`ModelChangeBlock` 写成这次用户消息的第一个文字块；思考块照旧凡是 `claude` 的都发
  - 测试：`claude-requests.txt` 里原有的请求体逐字节不变；`effort` 跟着选择变；历史里两个 Claude 模型的思考块都发、OpenAI 的不发（需求 6.1、6.3）；`ModelChangeBlock` 的样子；推理块记下实际模型（备用模型接手时是备用模型）
  - _Requirements: M9, M12, 需求 6.1, 6.3_
  - _Prompt: Role: Java 后端开发 | Task: 让 Claude 请求按会话的选择发 | Restrictions: 不删改历史里的思考块；默认选择的请求体不变 | Success: 测试全过_

- [x] 9. OpenAI 兼容请求按选择发
  - File: shell/model/OpenAiClient.java（改），shelltest/model/OpenAiClientTest.java（改）
  - 模型名从 `prompt.model()` 取；构造参数加“模型 → `OpenAiThinking`”的函数，按档位写思考参数，`options().extra` 里带上；`reasoning_content` 只发同路线同模型的，`route` 为空不发；推理块填 `route`、`model`；`ModelChangeBlock` 照工具变化写成文字放进这次用户消息开头
  - 测试：四种思考写法的请求体（任务 3 的期望文件）；路线 A 模型 X、路线 A 模型 Y、路线 B 模型 X 的推理块只发第一个（需求 6.2）；旧块不发；`vision=false` 时历史图片换成文字（M15，现有断言照跑）
  - _Requirements: M5, M9, M12, M15, 需求 6.2, 6.3_
  - _Prompt: Role: Java 后端开发 | Task: 让 OpenAI 兼容请求按会话的选择发 | Restrictions: model 包不依赖 route 包 | Success: 测试和 ArchitectureTest 全过_

- [x] 10. 接线：`Engine` 换成 `Routes`
  - File: shell/boot/TantalumApplication.java、Engine.java、TantalumProperties.java、shell/handler/ContextHandler.java、ModelHandler.java、shell/settings/CompactionPolicy.java（改），删 shell/model/ClaudeWindow.java、OpenAiWindow.java、WindowSource.java、ContextWindow.java，测试里注入模型的地方（改，加 `TestRoutes`）
  - `createRoutes` 照设计；`Engine(Routes)`；`ContextHandler` 第 4 步（`requested` → `current` → 用户默认 → 管理员默认，按 `ModelInfo` 算压缩设置）；`ModelHandler` 按路线找客户端和 `CallLimiter`，超长时 `refreshSoon(route)`；`CompactionPolicy.settings(Principal, ModelInfo)`；删掉 `TantalumProperties` 里按 `usesClaude()` 分支的方法；启动日志每条路线一行
  - 测试：两条脚本化路线，`Prompt.model` 指哪条就调哪条；一条路线并发上限 1 时另一条照常并行（需求 5.8）；压缩点按选的模型的窗口算（M10）；现有测试注入改成 `TestRoutes` 后断言不变
  - _Requirements: M7, M10, M14, M16, 需求 5.1, 5.8_
  - _Prompt: Role: Java 后端开发 | Task: 把服务器从一个模型客户端改成按路线调用 | Restrictions: 不改压缩规则本身；回放不经过 Routes | Success: mvn -o verify 全过_

- [x] 11. `ModelSwitchReplayTest`
  - File: examples/ModelSwitchReplayTest.java（新），tantalum-examples/src/test/resources/ 下一个本 spec 之前的会话文件（新，从现有资源里挑一个带思考块的）
  - 照设计“Integration Testing → `ModelSwitchReplayTest`”：两条脚本化路线（Claude 协议 1M、OpenAI 协议 8000），一个会话里换三次，其中一次换到小窗口触发压缩，摘要调用到了上一个模型；system 和工具定义每次逐字节相同；重启后回放 prompt 与实时逐个相同，路线全部下线后回放照样成功；旧会话文件打开逐字节不变，继续提问不追加说明
  - _Requirements: M10, M11, M13, 需求 5.4, 6.4, 7.2, 7.3, 7.4_
  - _Prompt: Role: Java 测试 | Task: 测换模型在 journal 和回放里是确定的 | Restrictions: 不访问外网 | Success: 测试全过_

## 阶段 3：接口和控制台

提交带选择、存“上次的选择”、模型列表接口；控制台的选择器、对话页一行、设置页和状态栏。阶段结束时 8091 上能在画面上选模型和档位。

- [x] 12. 接口
  - File: shell/loop/Intake.java、shell/web/ApiController.java、SettingsController.java、ModelController.java（新）、shell/settings/UserSettings.java、会话视图的 Controller 和 `TranscriptPass`（改），shelltest/web/ModelApiTest.java（新）、SubmitApiTest、SettingsApiTest（改）、shelltest/settings/UserSettingsTest.java（改）
  - 提交读 `model`，`ChoiceCheck.problem` 不为空回 400，图片检查按选的模型；`Intake.submit` 带选择；`UserSettings.modelChoice`；`GET /api/settings` 加 `model`、`PUT /api/settings/model`；`GET /api/models` 照设计“数据模型”；会话视图加 `model`、`toolChangesInHistory`，消息列表加 `modelChange` 项；`/api/config` 的模型字段改成默认选择的
  - 测试：设计 Error Handling 表每一种 400（需求 5.6、5.7）；`/api/models` 里没有 `base-url`、密钥变量名（1.6）；两个用户的 `model_choice` 互不影响（4.4）；上次的选择不可用时 `mine` 为空（4.3）；`steer` 带了 `model` 不进 journal；未登录 401
  - _Requirements: M6, M7, M11, M14, M15, 需求 1.6, 4.1–4.4, 5.6, 5.7_
  - _Prompt: Role: Java 后端开发 | Task: 提供选模型的接口并检查提交里的选择 | Restrictions: 不返回地址和密钥变量名；不合格的提交不进 journal | Success: 接口测试全过_

- [x] 13. 控制台的数据层
  - File: console/api/types.ts、console/api/client.ts、console/composer/useModels.ts（新）、console/composer/drafts.ts（改），console/composer/useModels.test.ts（新）
  - 类型照设计；`getModels`、`setModelChoice`；提交函数带 `model`；`useModels`（`staleTime` 1 分钟）；`useSessionChoice(session)`：没发出的选择（存在 drafts 里）→ 会话的当前选择 → `mine` → `default`，选了就存成上次的选择，提交成功后清掉没发出的；换模型时档位的取法照 M5
  - 测试：四级取值顺序；换到没有当前档位的模型取默认档位、有就留着（需求 3.4）；刷新后没发出的选择还在；提交成功后清掉
  - _Requirements: M5, M6, M7, 需求 3.4, 4.1, 4.2_
  - _Prompt: Role: 前端开发 | Task: 做选模型的数据层 | Restrictions: 不轮询 /api/models | Success: vitest 全过_

- [x] 14. `ModelPicker`
  - File: console/composer/ModelPicker.tsx（新）、console/composer/Composer.tsx、ComposerDock.tsx、console/text/zh.ts（改），console/composer/ModelPicker.test.tsx（新）、Composer.test.tsx（改）
  - 照设计“控制台 → `ModelPicker`”：按钮“模型 · 档位”；两栏，模型按路线分组、多于 8 个有搜索；没有档位不显示右栏；“暂时连不上”的一组灰色带原因；`toolChangesInHistory` 时 `toolChanges` 为假的模型灰掉带原因；选择与会话已生效的不同时写“下一次提问生效”；运行中能改；全部文字在 `zh.composer.picker`
  - 测试：上面每一条各一例（需求 3.1–3.7）；200 个模型时打开到能点 ≤ 100 ms（NFR，vitest 里用 `performance.now` 粗测）
  - _Requirements: M2, M5, M8, M11, M18, 需求 3.1–3.7_
  - _Prompt: Role: 前端开发 | Task: 做输入框旁的模型和档位选择器 | Restrictions: 照现有输入框样式；文字放 zh.ts | Success: vitest 全过_

- [x] 15. 对话页、状态栏和设置页
  - File: console/conversation/ModelChangeNotice.tsx（新）、MessageList.tsx、console/composer/StatusBar.tsx、console/settings/ModelPane.tsx、CompactionPicker.tsx、console/text/zh.ts（改），console/conversation/ModelChangeNotice.test.tsx（新）、console/settings/settings.test.tsx、compaction.test.tsx（改）
  - 对话页 `modelChange` 项显示“换成 {显示名} · {档位}”；状态栏的窗口和压缩点按当前会话选择的模型；设置页按路线列出模型（名字、窗口和来源、图片），压缩点 token 数按当前会话的模型的 `points`
  - 测试：换了模型的那一轮上面有一行（需求 8.4）；只换档位时也显示新档位；状态栏占比按 qwen 的 131072 算（8.3）；设置页列出两条路线、来源写对（8.1）；压缩点的 token 数随模型变（8.2）
  - _Requirements: M4, M10, M12, 需求 8.1–8.4_
  - _Prompt: Role: 前端开发 | Task: 在对话页、状态栏和设置页显示模型选择 | Restrictions: 不改压缩点选项本身 | Success: vitest 全过_

- [x] 16. 控制台端到端
  - File: e2e/model-picker.spec.ts（新）、examples/E2eServer.java（改）
  - `E2eServer` 配两条脚本化路线（一条 Claude 协议，含一个不在 `TOOL_CHANGE_MODELS` 里的模型名；一条 OpenAI 协议，`thinking=chat-template`）。用例：照设计“End-to-End Testing → Playwright”的五条
  - _Requirements: 需求 3, 4, 8.4_
  - _Prompt: Role: 测试开发 | Task: 写选模型的端到端测试 | Restrictions: E2E 不访问外网 | Success: Playwright 全过_

## 阶段 4：真实模型、文档、8091

用真实 Claude 和本地 qwen 在严格校验下验证换模型不破坏思考签名，量 NFR，写文档，8091 加本地路线，最后在 8091 上演示。

- [x] 17. `ModelSwitchScenario`（真实模型）
  - File: examples/ModelSwitchScenario.java（新），.spec-workflow/steering/tech.md（改，记结果）
  - 风险：换模型后 Claude 因历史（思考块、工具变化、换模型的说明）拒绝请求，或者丢了上下文。`TANTALUM_SMOKE_CLAUDE=1` 门控，Claude 严格校验（`prefix_mismatch_behavior: error`）加本地 qwen（`chat-template`）。照设计“真实模型”那一条的五步和判定标准；先估算用量（预计 15 次调用以内，几十万缓存读），跑一次，结论写进 tech.md：每一步的模型、档位、调用数、输入、输出、缓存读写、用时
  - _Requirements: M9, M11, 需求 6.5_
  - _Prompt: Role: 测试开发 | Task: 用真实模型验证换模型不破坏思考签名 | Restrictions: 只跑一次，失败先查原因再重跑；输出里不出现密钥 | Success: 五步都没有 400，暗号答对，用量记进 tech.md_

- [x] 18. NFR 测量
  - File: shelltest/web/ModelApiTest.java、shelltest/route/RoutesTest.java（改）、.spec-workflow/steering/tech.md（改）
  - `GET /api/models` 200 个模型 100 次取 P95 ≤ 50 ms；提交接口带不带 `model` 的 P95 差 ≤ 5 ms；启动时三条路线（一条 8 秒才回）等待 ≤ 5 s；结果写进 tech.md
  - _Requirements: NFR Performance_
  - _Prompt: Role: 测试开发 | Task: 量选模型相关的处理时间 | Success: 数字达标并记进 tech.md_

- [x] 19. 说明文档
  - File: README.md、.spec-workflow/steering/tech.md、.spec-workflow/BACKLOG.md（改）
  - README 新的一节“模型路线”和“能力对照”一行，照设计“文档”；“数据目录”里 journal 那段补一句选择也照“只在变化时记”；tech.md 照设计“改动的已有代码”里那三处；BACKLOG 两行照设计
  - _Requirements: 需求 9.2_
  - _Prompt: Role: 技术写作 | Task: 写模型路线的说明 | Restrictions: 不写真实的密钥、内网地址以外的私有地址 | Success: Yi 照着能在一台新服务器上配好 Claude 和一条 OpenAI 兼容路线_

- [x] 20. 8091 加本地路线
  - File: scratchpad/app/start.sh（改）
  - 加 `--tantalum.openai-routes.local.*`（显示名“本地”、`base-url=http://127.0.0.1:8090/v1`、qwen 模型名、`thinking=chat-template`、`context-window=131072`、`bytes-per-token=1.75`、`concurrency=1`）；部署后确认 `/api/models` 有两条路线、本地路线 `status` 为 `ok`；不动别的启动参数
  - _Requirements: 需求 9.3_
  - _Prompt: Role: 运维 | Task: 让 8091 同时提供 Claude 和本地模型 | Restrictions: 不改密钥文件；不动别的启动参数 | Success: 8091 的选择器里能看到两条路线_

- [x] 21. 8091 真实演示
  - 8091 上用 Yi 的账号，一个会话里：Claude Sonnet 5.5 · 中 问一个要读文件的问题（调工具）→ 运行中把选择换成 本地 · qwen3.8 · 开，看到“下一次提问生效”，这个运行照旧用 Sonnet → 下一次提问用 qwen 接着问，对话页出现“换成 …”，状态栏按 131072 算 → 换成 Claude Opus 5.5 · 高 再问，回答用上了前面几轮的内容 → 新开会话，选择器显示 Opus 5.5 · 高。看轨迹页每次调用的模型和档位；截图给 Yi；报告 Claude 用量（每次调用的输入、输出、缓存命中，换模型那次的缓存没命中）、qwen 用时和发现的问题，写进本任务的进度
  - _Requirements: 需求 3, 4, 5, 8 的例子, 9.3_
  - _Prompt: Role: 测试开发 | Task: 在 8091 上演示同一会话里换模型和思考档位 | Restrictions: 截图和报告里不出现密钥、令牌 | Success: 三次换模型都按下一次提问生效，没有请求被拒，Yi 收到截图和用量_
