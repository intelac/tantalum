# Tasks Document

工作方式：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志。每个阶段结束：
- 跑全量回归：`mvn -o clean verify`（含 `JarCrashIT`）、跳号回归、vitest、Playwright；
- 做一次变异检查；
- 提交一次；
- 重新打包、更新 8091 上的服务；
- 向 Yi 汇报。

提交前扫描密钥。现有测试只因构造参数变化而改，断言不放宽；现有期望文件（`items.jsonl`、`sse.txt`、`trace.json`、`trace-sse.txt`、`claude-requests.txt`、`wire/*.txt`）不变。设计里写的数字照设计做，偏离时写进汇报。

路径前缀：`value/` = `tantalum-value/src/main/java/tantalum/value/`，`kernel/` = `tantalum-kernel/src/main/java/tantalum/kernel/`，`shell/` = `tantalum-shell/src/main/java/tantalum/shell/`，`console/` = `console/src/`。

## 阶段 1：文件工具

- [x] 1. `TextFiles`
  - File: shell/tool/TextFiles.java（新）
  - 前 8 KB 判断二进制；严格 UTF-8 逐行读（不整读）；去 `\r`；换行风格判断（前 4096 字符里 CRLF 多就算 CRLF）；BOM 识别；原子写（临时文件 600、`fsync`、改名，已有文件保留原权限）
  - 测试：二进制、非 UTF-8、多字节字符跨缓冲区边界、CRLF 和 LF 判断、BOM 往返、原子写后权限、写失败不留临时文件
  - _Requirements: C3, C4, C6, C7_

- [x] 2. `Workspace` 写路径校验
  - File: shell/plugin/Workspace.java
  - 新方法：目标不存在时按“最近一个已存在的上级目录”校验在工作目录里；符号链接越界拒绝；`memory/` 下拒绝
  - 测试：新文件、新的多级目录、`..` 越界、指向外面的符号链接目录、`memory/x.md`
  - _Requirements: C11, 2.4_

- [x] 3. `read_file` 窗口读取
  - File: shell/tool/ReadFileTool.java
  - `offset`、`limit`；2000 行或 50 KB；长行截断说明；续读说明；64 MB 以上拒绝提示用 `grep`；`offset` 超出报错；附件路径照旧
  - 测试：需求 1 的每条验收标准；空文件；最后一行没有换行；刚好 2000 行
  - _Requirements: 1, C2, C3, C4_

- [x] 4. `Observations` 和 `ToolContext.session`
  - File: shell/tool/Observations.java（新）、shell/plugin/ToolContext.java、shell/handler/ToolHandler.java
  - 按 `(用户, 会话, 真实路径)` 记版本（大小、修改时间、文件键）；每文件锁；会话移出内存时清掉
  - 测试：没读过、读过后被改、自己写完接着改、两个线程同时改只有一个成功、不同用户的记录互不相干
  - _Requirements: C5, C8, 5_

- [x] 5. `write_file`
  - File: shell/tool/WriteFileTool.java（新）
  - 建上级目录（700）；10 MB 上限；覆盖已有文件要先读过；结果文字照设计第 3 节
  - 测试：需求 2 的每条验收标准；覆盖没读过的文件被拒；新文件权限 600
  - _Requirements: 2, C5, C6_

- [x] 6. `edit_file`
  - File: shell/tool/EditFileTool.java（新）
  - 唯一匹配；`replace_all`；找不到、多次、空、相同各自报错；CRLF 和 BOM 保留；先读后写
  - 测试：需求 3 的每条验收标准；CRLF 文件改完仍是 CRLF；BOM 保留；参数里带 CRLF 也能匹配
  - _Requirements: 3, C5, C7_

- [x] 7. `list_dir`、`find_files`
  - File: shell/tool/{ListDirTool,FindFilesTool}.java（新）
  - 照 C9、C10；跳过的目录；上限和总数说明；相对路径
  - 测试：需求 4 的每条验收标准；不带 `/` 的模式匹配任意深度；符号链接不跟出工作目录
  - _Requirements: 4, C9, C10_

- [x] 8. 注册和限制
  - File: shell/boot/Engine.java、tantalum-examples/.../ReadOnlyHook.java
  - 共用工作目录模式不注册写工具；`ReadOnlyHook` 加上新的写工具名
  - 测试：共用模式下工具列表没有写工具；`PolicyHooksTest` 覆盖新名字
  - _Requirements: C11, 安全_

- [x] 9. 阶段 1 收尾
  - 性能：64 MB 文件读一个窗口 1 秒内；一万个文件的目录树 `find_files` 1 秒内；全量回归；变异检查（去掉先读后写的版本检查；去掉 `memory/` 拒绝）；提交；汇报
  - _Requirements: 非功能“性能”_

## 阶段 2：开关和过滤入口

- [x] 10. 插件 SDK 的 `plugin()` 和 `description()`
  - File: kernel/hook/KernelHook.java、shell/plugin/{WorkType,AfterToolHook}.java
  - 默认方法 `plugin()` 返回 `name()`，`description()` 默认空
  - 测试：现有示例插件的默认值；同一插件三种接口返回同一名字时归为一项
  - _Requirements: C13_

- [x] 11. `PluginSwitches`
  - File: shell/plugin/PluginSwitches.java（新）、shell/auth/UserStore.java（新表）
  - `plugin_settings(user, kind, name, enabled, updated_at)`；只存关掉的；按用户缓存
  - 测试：默认全开；关掉再打开；两个用户互不影响；重启后保留
  - _Requirements: C12, C15, 7.6_

- [x] 12. `PluginGate` 和 ArchUnit 规则
  - File: shell/plugin/PluginGate.java（新）、shell/handler/{ToolHandler,WorkHandler,ContextHandler}.java、tantalum-shell/src/test/java/tantalum/shell/ArchitectureTest.java
  - 名单；`runTool`、`afterTool`、`runWork`；关掉的工具返回设计里的“不可用”文字；必须开启的插件不能关
  - 规则：除 `PluginGate` 外不得调用 `Tool.run`、`AfterToolHook.afterToolExecution`、`WorkType.run`；内核里除 `HookChain` 外不得调用 `KernelHook` 的钩子方法
  - 测试：关掉的工具、插件工作、工具后处理都不执行；规则对一个故意绕过的测试类报错
  - _Requirements: C16, 8.3_

- [x] 13. 插件接口
  - File: shell/web/PluginController.java（新）
  - `GET /api/plugins`、`PUT /api/plugins/<kind>/<name>`；字段照设计第 2 节
  - 测试（Web 测试）：两个账号各自开关互不影响；必须开启的 400；不存在的 404；没登录 401
  - _Requirements: 7, 7.6_

- [x] 14. 阶段 2 收尾
  - 全量回归；变异检查（`ToolHandler` 直接调 `Tool.run`；开关不按用户分）；提交；汇报
  - _Requirements: C16_

## 阶段 3：会话中途换工具

- [x] 15. 值类型
  - File: value/model/{Role,ToolChangeBlock,ToolSpec}.java、value/event/ContextAssembled.java、value/state/SessionContext.java、journal/EventCodec
  - `Role.SYSTEM`；`ToolChangeBlock(added, removed)`；`ToolSpec.deferred`；`enabledTools`、`enabledPlugins`；新字段为空或默认时不写出
  - 测试（EventCodecTest）：往返；旧行读成“全部开着”；写出的旧形状行和改动前逐字节相同
  - _Requirements: C15, 8.6_

- [x] 16. 内核换名单
  - File: kernel/step/ContextSteps.java、shell/handler/ContextHandler.java
  - 第一次组装记全集和初始名单；之后每次运行开始比名单，有变化追加 `Role.SYSTEM` 消息；插件名单只更新不发消息；运行中途不换；不在全集里的工具忽略
  - 测试：打开、关掉、同时；没变化不追加；运行中途改开关不影响本次运行；回放和当时相同
  - _Requirements: C14, 8.1, 8.2, 8.4_

- [x] 17. 钩子按会话名单过滤
  - File: kernel/hook/HookChain.java
  - 只跑 `activePlugins` 里的；名单为空（旧会话）时全跑
  - 测试：关掉的钩子插件不跑；运行中途关掉的本次运行照跑；旧会话全跑
  - _Requirements: 8.5, 8.6_

- [x] 18. 请求投影
  - File: kernel/projection/PromptProjection.java、kernel/compaction/Trimming.java
  - 初始没开的工具标延后加载；后面没有助手回复的工具变化合并进下一条；压缩时保留段里的工具变化消息去掉
  - 测试：合并规则（含连续三次没有回复）；压缩后不剩工具变化消息；没有任何变化的会话投影结果和改动前相同
  - _Requirements: C14, 设计第 1 节_

- [x] 19. Claude 请求格式
  - File: shell/model/ClaudeRequest.java、ClaudeClient.java
  - `defer_loading`；系统消息 `tool_addition`、`tool_removal`；beta 头；支持模型名单，不在名单时不发并退回“新会话生效”；出现过工具变化的会话不带 `fallbacks`
  - 测试：新期望文件 `claude-tool-change-requests.txt` 逐字节钉住；不在名单的模型不出现系统消息；`fallbacks` 规则；现有 `claude-requests.txt` 不变
  - _Requirements: C14, 设计第 1 节“两种模型的翻法”_

- [x] 20. OpenAI 请求格式
  - File: shell/model/OpenAiClient.java
  - `tools` 发当前开着的；工具变化翻成文字系统消息
  - 测试：新期望文件 `openai-tool-change-requests.txt`；现有期望文件不变
  - _Requirements: C14_

- [x] 21. 线路场景
  - File: tantalum-shell 的 `WireFormatTest`、期望文件 `wire/tool-change.txt`（新）
  - 一个会话：第一问没有 `write_file`；改开关；第二问出现工具变化消息、模型调用 `write_file`
  - _Requirements: 8_

- [x] 22. 真实 Claude 测试
  - File: tantalum-examples/.../ClaudeSmokeTest.java 或新的 `CoreToolsClaudeTest.java`
  - 三项，判定标准照设计“真实模型”：长文件窗口找词；先读后改函数、改完测试通过；中途打开 `write_file`、带思考历史、严格校验模式不报错
  - 跑完报用量
  - _Requirements: 非功能“真实模型”_

- [x] 23. 阶段 3 收尾
  - 全量回归；变异检查（工具变化不合并；直接改 `tools` 而不用延后加载，严格模式真实测试应失败）；提交；汇报
  - _Requirements: C14_

## 阶段 4：控制台

- [ ] 24. 插件页
  - File: console/plugins/{PluginsPage.tsx,usePlugins.ts,plugins.test.tsx}（新）、console/shell/Sidebar.tsx、console/app/router.ts、console/text/zh.ts
  - 三块列表；MCP 服务器可展开、显示连接状态；必须开启的禁用并说明；页头生效说明（两种文字）；手机宽度
  - 测试（vitest）：开关调用接口；失败时恢复原状并提示；必须开启的不能点
  - _Requirements: 7, C13_

- [ ] 25. 对话页显示
  - File: console/conversation/{EditDiff.tsx,WriteFilePreview.tsx}（新）、ToolStep 相关
  - `edit_file` 差异；`write_file` 前 20 行可展开；`read_file` 窗口行号范围
  - 测试（vitest）：差异内容；预览折叠；没有窗口参数时不显示范围
  - _Requirements: 6_

- [ ] 26. 轨迹页显示
  - File: console/trace/inspector/{ToolTabs,RecordTabs}.tsx
  - `edit_file` 的“差异”页签；上下文记录显示工具变化
  - 测试（vitest）
  - _Requirements: 6_

- [ ] 27. 端到端
  - File: console/e2e/{plugins,file-tools}.spec.ts（新）
  - 插件页开关；关掉 `write_file` 后当前会话调用被拒；下一问模型拿不到、再打开后下一问能用；差异和写入预览；两个用户各自的开关；手机宽度
  - _Requirements: 7, 8, 非功能“端到端”_

- [ ] 28. 文档和收尾
  - File: README.md、tech.md、BACKLOG.md、架构页
  - 去掉“内置工具只读”；文件工具和插件页说明；BACKLOG 加“钩子补丁改用系统消息追加”、core-tools 标完成
  - 全量回归；变异检查；提交；打包更新 8091；端到端手动走一遍；汇报
  - _Requirements: 非功能“文档”_
