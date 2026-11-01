# Tasks Document

工作方式：一次只做一个 task，先写测试再实现；完成后汇报改了什么、验证结果、是否偏离 spec、下一步，再继续。阶段结束时提交一次（Yi 已批准按阶段提交）。kernel 和 value 不改。

## 阶段 0：配置与格式

- [x] 1. 新增 `tantalum.memory.*` 和 `tantalum.knowledge.*` 配置
  - File: tantalum-shell/src/main/java/tantalum/shell/boot/TantalumProperties.java, tantalum-shell/src/main/resources/application.properties, tantalum-shell/src/test/java/tantalum/shell/boot/TantalumPropertiesTest.java
  - 新增 `Memory(maxEntries, maxIndexBytes, maxEntryBytes)` 与 `Knowledge(Path dir, int maxBytes)`，默认 200 / 16384 / 8192 / 空 / 65536；非正数启动失败
  - 验证：默认值绑定正确；非正数抛 `IllegalArgumentException`；`dir` 为空表示没有共享知识层
  - _Requirements: 4.1, 5.1_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 / Spring Boot 工程师 | Task: 完成“新增 tantalum.memory.* 和 tantalum.knowledge.* 配置”，满足需求 4.1、5.1，遵守 design.md 的“配置”一节 | Restrictions: 只改这三个文件；沿用 `Loop` 的紧凑构造器校验风格 | Success: 测试通过；`application.properties` 有默认值和一行注释 | 完成后在 tasks.md 把本任务标为 [x]，并用 log-implementation 记录_

- [x] 2. `MemoryEntry`、`MemoryType`、`MemoryFormat`：测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/memory/MemoryFormatTest.java, tantalum-shell/src/main/java/tantalum/shell/memory/MemoryEntry.java, MemoryType.java, MemoryFormat.java
  - 纯函数：条目文件 ⇄ `MemoryEntry`（frontmatter：name、description、type、updated）、索引行生成与解析、名称校验 `[a-z0-9-]{1,64}`、描述 1–200 字符单行、正文按 UTF-8 字节数限长
  - 验证：往返相等；坏 frontmatter 返回空；坏索引行跳过；名称边界（空、65 位、大写、`..`、斜杠）全部拒绝
  - _Requirements: 1.2, 1.3, 1.6, 3.3_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“MemoryEntry、MemoryType、MemoryFormat 测试和实现”，满足需求 1.2、1.3、1.6、3.3，遵守 design.md 的“文件格式”和 Data Models | Restrictions: 不做 I/O；不引入 YAML 库，frontmatter 只有四个固定键，手写解析 | Success: 测试通过；类名与 design 一致 | 完成后标 [x] 并 log-implementation_

- [x] 3. `CredentialGuard`：测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/memory/CredentialGuardTest.java, tantalum-shell/src/main/java/tantalum/shell/memory/CredentialGuard.java
  - 特征：`sk-ant-…`、`sk-…`（20 位以上）、`AKIA…`、`ghp_…`、`(password|passwd|secret|token|api[_-]?key)\s*[:=]\s*\S{6,}`、`Bearer <长串>`、PEM 私钥头
  - 验证：每种特征命中；“我们讨论了 password 策略”这类普通文本不误伤；返回的原因不含被拒内容
  - _Requirements: 7.2, 7.3_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: 安全意识强的 Java 工程师 | Task: 完成“CredentialGuard 测试和实现”，满足需求 7.2、7.3 | Restrictions: 纯函数；正则预编译；拒绝原因里不能出现被检查的内容 | Success: 测试通过 | 完成后标 [x] 并 log-implementation_

## 阶段 1：存储

- [x] 4. `MemoryStore` 测试（先失败）
  - File: tantalum-shell/src/test/java/tantalum/shell/memory/MemoryStoreTest.java
  - 覆盖：新建 → 文件和索引行；同名覆盖 → UPDATED、条目数不变；删除 → 文件和索引行都没了；删除不存在 → false；条目数上限、索引字节上限、单条字节上限各自拒绝且不写；凭据拒绝；32 个线程并发写同一用户后每个文件完整、索引与目录一致；`@TempDir`；写后目录里没有 `.tmp`
  - _Requirements: 1.1, 1.2, 1.5, 3.2, 3.5, 4.2, 4.3, 7.2_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: 注重并发正确性的 Java 测试工程师 | Task: 完成“MemoryStore 测试（先失败）”，满足列出的需求，遵守 design.md 的“并发与原子性” | Restrictions: 只写测试；用真实文件系统的 `@TempDir`，不 mock | Success: 测试编译通过并因缺少实现而失败 | 完成后标 [x] 并 log-implementation_

- [x] 5. `MemoryStore` 实现
  - File: tantalum-shell/src/main/java/tantalum/shell/memory/MemoryStore.java, MemoryLimits.java
  - `indexText()`、`list()`、`read()`、`write()`、`delete()`、`limits()`；临时文件 + 重命名；写/删在用户锁内；每次写删后由条目文件重建 `MEMORY.md`；最终路径必须在目录内
  - 验证：任务 4 全部通过
  - _Requirements: 同任务 4_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“MemoryStore 实现”让任务 4 通过，遵守 design.md | Restrictions: I/O 只在这个类；`Clock` 注入用于 `updatedAt`；错误文本不含绝对路径 | Success: `MemoryStoreTest` 全过 | 完成后标 [x] 并 log-implementation_

- [x] 6. `MemoryStores`：目录位置、每用户锁
  - File: tantalum-shell/src/test/java/tantalum/shell/memory/MemoryStoresTest.java, tantalum-shell/src/main/java/tantalum/shell/memory/MemoryStores.java
  - 每人模式：`<workspace>/<用户>/memory/`；共用模式：`<data-dir>/memory/<用户>/`；目录权限 700；同一用户两次 `of` 返回同一实例
  - 验证：两种模式的路径断言；共用模式下别人的记忆目录不在工作目录 root 内（用 `Workspace.resolve` 断言到不了）；不安全用户名被拒
  - _Requirements: 1.1, 1.3, 1.4（含 design 的修正）, 2.5_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“MemoryStores 目录位置与每用户锁”，满足需求 1.1、1.3、1.4、2.5，遵守 design.md 的“目录位置”一节 | Restrictions: 复用 `Workspaces.isSafeName` 与它的权限做法 | Success: 测试通过 | 完成后标 [x] 并 log-implementation_

## 阶段 2：进入上下文

- [x] 7. `KnowledgeBase`：测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/memory/KnowledgeBaseTest.java, tantalum-shell/src/main/java/tantalum/shell/memory/KnowledgeBase.java
  - 读目录下 `*.md`（不递归，按文件名排序）拼成一段，总字节上限按文件名顺序截断并记警告；目录未配置或不存在返回空串并记一次警告；按最大修改时间缓存
  - 验证：排序、截断、缺失、缓存命中后文件变化能被读到
  - _Requirements: 4.4, 5.1, 5.2_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“KnowledgeBase 测试和实现”，满足需求 4.4、5.1、5.2 | Restrictions: 只读；不跟随符号链接到目录外（`Files.isRegularFile` + `NOFOLLOW_LINKS`） | Success: 测试通过 | 完成后标 [x] 并 log-implementation_

- [x] 8. `MemoryGuidance` 固定指引文本
  - File: tantalum-shell/src/main/java/tantalum/shell/memory/MemoryGuidance.java
  - 内容按需求 7.1：四种类型各记什么、只记跨会话有用的事实、写前先看索引、同名覆盖、用户让记/让忘、不记一次性细节、不记凭据、正文按需 `memory_read`；英文（与基础提示一致），一个常量
  - 验证：文本里出现三个工具名和四种类型名（单元断言放进任务 9 的测试）
  - _Requirements: 7.1_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: 写过 agent system prompt 的工程师 | Task: 完成“MemoryGuidance 固定指引文本”，满足需求 7.1 | Restrictions: 不带任何会变化的内容（时间、计数）；控制在 25 行以内 | Success: 常量存在、措辞清楚 | 完成后标 [x] 并 log-implementation_

- [x] 9. `ContextHandler` 扩展：测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/handler/ContextHandlerTest.java, tantalum-shell/src/main/java/tantalum/shell/handler/ContextHandler.java
  - system = 基础 + 指引 + 共享知识 + `AGENTS.md` + 索引，每段有才加，标题固定；构造器加 `MemoryStores`、`KnowledgeBase`
  - 验证：顺序正确；索引为空时没有记忆段；两个用户各自只拿到自己的索引；用 `PromptProjection.project` 断言钩子追加文字落在索引之后；现有测试仍过
  - _Requirements: 2.1, 2.2, 2.5, 2.6, 5.4_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“ContextHandler 扩展”，满足需求 2.1、2.2、2.5、2.6、5.4，遵守 design.md 的“system 组装顺序” | Restrictions: 只改这两个文件和必要的测试夹具；不改 kernel | Success: 测试通过 | 完成后标 [x] 并 log-implementation_

- [x] 10. `Engine` 装配 `MemoryStores`、`KnowledgeBase`
  - File: tantalum-shell/src/main/java/tantalum/shell/boot/Engine.java
  - 在 `assemble()` 创建两者并传给 `ContextHandler`；`TestServer`、`E2eServer` 不需要改（走属性默认值）
  - 验证：`mvn -o -q -pl tantalum-shell test` 全过；`StartupTest`/`TenLoopIntegrationTest` 不受影响
  - _Requirements: 2.1_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 / Spring Boot 工程师 | Task: 完成“Engine 装配 MemoryStores、KnowledgeBase”，满足需求 2.1 | Restrictions: 不改构造器签名之外的东西；记忆工具的注册放任务 12 | Success: shell 测试全过 | 完成后标 [x] 并 log-implementation_

## 阶段 3：工具

- [x] 11. 三个记忆工具的测试（先失败）
  - File: tantalum-shell/src/test/java/tantalum/shell/memory/MemoryToolsTest.java
  - 覆盖：`memory_write` 新建/覆盖的返回文案；类型错、描述空、超长、记忆已满、像凭据各自的错误结果；`memory_read` 存在/不存在；`memory_delete` 存在/不存在；三者 schema 的 `required`；结果不含绝对路径；两个用户的 `ToolContext` 互不可见
  - _Requirements: 3.1–3.6, 4.2, 7.2, 8.4_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 测试工程师 | Task: 完成“三个记忆工具的测试（先失败）”，满足列出的需求，风格参照 `BuiltinToolsTest` | Restrictions: 只写测试 | Success: 编译通过、因缺实现失败 | 完成后标 [x] 并 log-implementation_

- [x] 12. `MemoryWriteTool`、`MemoryReadTool`、`MemoryDeleteTool` 实现并注册
  - File: tantalum-shell/src/main/java/tantalum/shell/memory/MemoryWriteTool.java, MemoryReadTool.java, MemoryDeleteTool.java, tantalum-shell/src/main/java/tantalum/shell/boot/Engine.java
  - `server()`=`memory`，并发 16，`isConcurrencySafe` true；用 `ToolArguments.schema(required, optional)`；在 `Engine.assemble()` 注册到 `ToolCatalog`（内置工具之后、插件之前）
  - 验证：任务 11 全过；`ToolCatalogTest`、`ConcurrencyStressTest` 仍过（它的 `ReadOnlyHook` 名单没有 memory 工具，拦下属正常）
  - _Requirements: 3.1–3.7_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“三个记忆工具实现并注册”，满足需求 3.1–3.7 | Restrictions: 工具无状态；错误文案与 `read_file`、`grep` 风格一致 | Success: 测试通过；shell 与 examples 全量通过 | 完成后标 [x] 并 log-implementation_

## 阶段 4：接口

- [-] 13. `MemoryApiTest`（先失败）
  - File: tantalum-shell/src/test/java/tantalum/shell/web/MemoryApiTest.java
  - 覆盖：列表（含 `used`/`max`、倒序）、详情、新增（201；400 的每种原因；凭据过滤）、删除（204；404）；alice 访问 bob 的名字和不存在的名字同为 404、同一文案；未登录 401；POST/DELETE 缺 `Origin` 被拒
  - _Requirements: 6.1–6.5, 7.3_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 测试工程师 | Task: 完成“MemoryApiTest（先失败）”，满足需求 6.1–6.5、7.3，用 `TestServer` | Restrictions: 只写测试 | Success: 编译通过、因缺实现失败 | 完成后标 [x] 并 log-implementation_

- [-] 14. `MemoryController` 实现
  - File: tantalum-shell/src/main/java/tantalum/shell/web/MemoryController.java
  - 四个接口；身份 `SecurityContextHolder`；名称不合法按 404；校验与工具同源（`MemoryFormat`、`CredentialGuard`、`MemoryStore.write` 的拒绝原因）
  - 验证：任务 13 全过；`AuthApiTest` 等现有接口测试仍过
  - _Requirements: 6.5_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Spring Boot 工程师 | Task: 完成“MemoryController 实现”让任务 13 通过，参照 `ApiController` 的 `current()`、`json()`、`error()` | Restrictions: 不改 `SecurityConfig`（现有规则已覆盖 `/api/**`） | Success: 测试通过 | 完成后标 [x] 并 log-implementation_

## 阶段 5：前端

- [ ] 15. 类型、客户端函数、查询 hook、文案
  - File: console/src/api/types.ts, console/src/api/client.ts, console/src/settings/useMemories.ts, console/src/text/zh.ts, console/src/api/client.test.ts
  - `MemorySummary`、`MemoryEntry`、`MemoryType`；`listMemories`、`getMemory`、`createMemory`、`deleteMemory`；查询键 `["memory"]`、`["memory", name]`，写后 invalidate；`settings.memory.*` 文案（标题、类型名、空状态、已用 n/N、删除确认、表单标签、错误）
  - 验证：`client.test.ts` 覆盖四个函数的路径、方法和 404 处理；`tsc` 通过
  - _Requirements: 6.1–6.5_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: React 19 / TypeScript 工程师 | Task: 完成“类型、客户端函数、查询 hook、文案”，满足需求 6.1–6.5，参照 `client.ts` 现有函数和 `useConfig` | Restrictions: 文案只放 `zh.ts`；不改其它面板 | Success: 单测和 `tsc` 通过 | 完成后标 [x] 并 log-implementation_

- [ ] 16. `MemoryPane`：列表与查看
  - File: console/src/settings/MemoryPane.tsx, console/src/settings/settings.test.tsx
  - 列表：名称、类型标签、描述、相对时间（`relativeTime`），按更新时间倒序；头部“已用 n/N 条”；空状态文案；点击一条展开正文（`Markdown` 组件）；加载和错误状态
  - 验证：渲染测试：空状态、三条倒序、点击展开显示正文
  - _Requirements: 6.1, 6.2_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: React 19 工程师 | Task: 完成“MemoryPane 列表与查看”，满足需求 6.1、6.2，风格参照 `AccountPane`、`ModelPane` | Restrictions: 只用现有设计变量和组件；键盘可操作、列表项是按钮 | Success: 测试通过 | 完成后标 [x] 并 log-implementation_

- [ ] 17. `MemoryPane`：删除确认与手工新增
  - File: console/src/settings/MemoryPane.tsx, console/src/settings/settings.test.tsx
  - 删除：`Dialog` 确认，Esc 取消，确认后调用 `deleteMemory` 并刷新；新增：名称、类型（下拉）、描述、正文，提交 `createMemory`，400 的原因原样显示在表单下
  - 验证：渲染测试：确认对话框出现/取消/确认；新增成功后列表刷新；400 文案显示
  - _Requirements: 6.3, 6.4, 7.3_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: React 19 工程师 | Task: 完成“MemoryPane 删除确认与手工新增”，满足需求 6.3、6.4、7.3 | Restrictions: 复用 `Dialog`、`Button`；表单校验只做必填，规则以服务端为准 | Success: 测试通过 | 完成后标 [x] 并 log-implementation_

- [ ] 18. `SettingsDialog` 接入“记忆”面板
  - File: console/src/settings/SettingsDialog.tsx, console/src/settings/settings.test.tsx
  - 新增 `memory` 面板项（图标 `Brain`），顺序：外观、模型、记忆、账号
  - 验证：切换到记忆面板显示 `MemoryPane`；`tsc`、`vitest`、`vite build` 通过
  - _Requirements: 6.1_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: React 19 工程师 | Task: 完成“SettingsDialog 接入记忆面板”，满足需求 6.1 | Restrictions: 不改其它面板 | Success: 测试和构建通过 | 完成后标 [x] 并 log-implementation_

## 阶段 6：集成验证与收尾

- [ ] 19. 快照语义的集成测试
  - File: tantalum-examples/src/test/java/tantalum/examples/MemorySnapshotTest.java
  - 脚本化模型：第一轮调用 `memory_write`，第二轮回答；断言同一运行第二次 `CallModel.prompt().system()` 不含新索引行；下一个运行的 `ContextAssembled.system` 含它；回放（`Replay.run`）得到的 prompt 与实时逐个相同且不读文件（删掉记忆目录后回放仍相同）
  - _Requirements: 2.3, 2.4_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 测试工程师 | Task: 完成“快照语义的集成测试”，满足需求 2.3、2.4，参照 `TenLoopIntegrationTest` 和 `ScriptedModelClient` | Restrictions: 不用真实模型 | Success: 测试通过 | 完成后标 [x] 并 log-implementation_

- [ ] 20. e2e：脚本化模型写记忆，面板里看、删
  - File: tantalum-examples/src/test/java/tantalum/examples/E2eModel.java, console/e2e/memory.spec.ts
  - `E2eModel` 新增关键词 `remember`：调用 `memory_write` 后回答“已记住”；e2e：发送含 `remember` 的消息 → 打开设置 › 记忆 → 看到条目 → 展开正文 → 删除确认 → 列表为空
  - 验证：`pnpm e2e` 全过（原 9 条 + 新增）
  - _Requirements: 8.5_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Playwright 测试工程师 | Task: 完成“e2e：脚本化模型写记忆，面板里看、删”，满足需求 8.5，参照 `console/e2e/chat.spec.ts` | Restrictions: 文本断言用唯一内容，避开 strict mode 冲突 | Success: e2e 全过 | 完成后标 [x] 并 log-implementation_

- [ ] 21. 真实模型场景：跨会话记住、让它忘记
  - File: tantalum-examples/src/test/java/tantalum/examples/ClaudeScenarioTest.java, .spec-workflow/steering/tech.md
  - 场景 1：会话 A “以后回答一律用 Java 25 语法并记住” → `memory_write` 被调用、文件存在；会话 B 新运行 `ContextAssembled.system` 含索引行，问“写一个打印 Hello 的最小 Java 程序”，断言回答含 Java 25 写法（`void main()` 或 `IO.println`）。场景 2：“忘掉关于 Java 版本的记忆” → `memory_delete` 被调用、文件消失、下一运行 system 不含。每个场景写明对应需求、判定标准、结论和用量
  - 验证：两场景通过；tech.md 场景表新增两行
  - _Requirements: 8.2, 8.3_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: 做过真实模型验收的工程师 | Task: 完成“真实模型场景：跨会话记住、让它忘记”，满足需求 8.2、8.3，门控与用量汇总沿用 `ClaudeScenarioTest` | Restrictions: 判定标准要能机械断言；用量报回 | Success: 通过并记录 | 完成后标 [x] 并 log-implementation_

- [ ] 22. 文档：README、能力对照表、插件说明
  - File: README.md, .spec-workflow/steering/tech.md
  - README：记忆一节（目录、格式、三个工具、面板、配置、共享知识层）；能力对照表“长期记忆”改为“有”；插件说明补“按名单放行的策略要把 memory_* 加进名单”；tech.md 决策日志加一条（四层模型、目录位置修正、索引以文件为准）
  - 验证：文档与实现一致，命令可运行
  - _Requirements: NFR 可用性_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: 技术写作者 | Task: 完成“文档：README、能力对照表、插件说明” | Restrictions: 不写实现里没有的东西 | Success: 文档更新 | 完成后标 [x] 并 log-implementation_

- [ ] 23. 全量验证与提交
  - File: （无新文件）
  - `mvn -o -q verify`、`pnpm test`、`tsc`、`vite build`、`pnpm e2e`；按阶段提交；汇报：改了什么、验证结果、是否偏离 spec（含对需求 1.4 的修正）、下一步
  - 验证：全部通过；工作树干净
  - _Requirements: All_
  - _Prompt: Implement the task for spec memory, first run spec-workflow-guide to get the workflow guide then implement the task: Role: 发布工程师 | Task: 完成“全量验证与提交” | Restrictions: 测试失败不得放宽断言 | Success: 全过并提交 | 完成后标 [x] 并 log-implementation_
