# Tasks Document

工作方式：一次一个任务，测试先行；完成后标记、记录实现日志；每个阶段结束提交一次，并汇报做了什么、验证结果、是否偏离 spec、下一步。kernel 和 value 不改。提交前扫描密钥。

## 阶段 0：依赖和配置

- [x] 1. 加入 SDK 依赖和架构规则
  - File: tantalum-shell/pom.xml, tantalum-shell/src/test/java/tantalum/shell/ArchitectureTest.java, THIRD_PARTY_NOTICES.md
  - mcp-core、mcp-json-jackson3 2.1.0；SDK 类型不得出 `tantalum.shell.mcp`
  - 验证：现有测试全过；Jackson 仍是 Spring Boot 管理的版本；打包后 jar 能启动
  - _Requirements: NFR 架构_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“加入 SDK 依赖和架构规则”，满足需求 NFR 架构，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: 现有测试全过；Jackson 仍是 Spring Boot 管理的版本；打包后 jar 能启动 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [x] 2. `McpSettings` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/mcp/McpSettingsTest.java, tantalum-shell/src/main/java/tantalum/shell/mcp/McpSettings.java, tantalum-shell/src/main/java/tantalum/shell/boot/TantalumProperties.java, tantalum-shell/src/main/resources/application.properties
  - 配置项和启动校验
  - 验证：非法名称、URL、缺环境变量、明文 http 带凭据、timeout 超过 tool-timeout 都启动失败且不含凭据；不配置时无影响
  - _Requirements: 1.1–1.4_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“`McpSettings` 测试和实现”，满足需求 1.1–1.4，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: 非法名称、URL、缺环境变量、明文 http 带凭据、timeout 超过 tool-timeout 都启动失败且不含凭据；不配置时无影响 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [x] 3. `McpToolFilter`、`McpResults` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/mcp/McpToolFilterTest.java, tantalum-shell/src/test/java/tantalum/shell/mcp/McpResultsTest.java, tantalum-shell/src/main/java/tantalum/shell/mcp/McpToolFilter.java, tantalum-shell/src/main/java/tantalum/shell/mcp/McpResults.java
  - 纯函数：放行顺序 deny→allow→只读→拒绝；工具名；文本、非文本说明、按 UTF-8 边界截断
  - 验证：null 注解不放行；名称超长或非法被拒；截断不切坏字符
  - _Requirements: 2.1, 2.2, 3.2, 3.5_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“`McpToolFilter`、`McpResults` 测试和实现”，满足需求 2.1, 2.2, 3.2, 3.5，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: null 注解不放行；名称超长或非法被拒；截断不切坏字符 | 完成后把任务标为 [x] 并用 log-implementation 记录_

## 阶段 1：连接

- [ ] 4. `TestMcpServer` 测试夹具
  - File: tantalum-shell/src/test/java/tantalum/shell/mcp/TestMcpServer.java
  - 内嵌 Tomcat 的真实 MCP 服务器：记录凭据和调用，可停可启，可返回 401、重定向，可增减工具
  - 验证：SDK 客户端能连上
  - _Requirements: 7.1_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“`TestMcpServer` 测试夹具”，满足需求 7.1，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: SDK 客户端能连上 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 5. `McpConnection` 测试（先失败）
  - File: tantalum-shell/src/test/java/tantalum/shell/mcp/McpConnectionTest.java
  - 发现、调用、`isError`、未知工具、中断后仍可用、重启后恢复、失败上限、稳定窗口、`list_changed`、重定向不跟随、401
  - 验证：编译通过，因缺实现失败
  - _Requirements: 3.2–3.4, 5.1–5.3, 1.5_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“`McpConnection` 测试（先失败）”，满足需求 3.2–3.4, 5.1–5.3, 1.5，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: 编译通过，因缺实现失败 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 6. `McpTools`、`McpConnection` 实现
  - File: tantalum-shell/src/main/java/tantalum/shell/mcp/McpTools.java, tantalum-shell/src/main/java/tantalum/shell/mcp/McpConnection.java
  - 状态机、退避、换新客户端、只读工具在会话失效时重试一次、其余不重试
  - 验证：上一任务全过；线程不泄漏
  - _Requirements: 3.2–3.4, 5.1–5.3_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“`McpTools`、`McpConnection` 实现”，满足需求 3.2–3.4, 5.1–5.3，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: 上一任务全过；线程不泄漏 | 完成后把任务标为 [x] 并用 log-implementation 记录_

## 阶段 2：目录和调用

- [ ] 7. `ToolCatalog` 接入动态工具，`ContextHandler` 加说明
  - File: tantalum-shell/src/test/java/tantalum/shell/tool/ToolCatalogTest.java, tantalum-shell/src/main/java/tantalum/shell/tool/ToolCatalog.java, tantalum-shell/src/main/java/tantalum/shell/handler/ContextHandler.java, tantalum-shell/src/test/java/tantalum/shell/handler/ContextHandlerTest.java
  - `ToolSource`、按名称排序；固定的不可信内容说明，只在配了服务器时出现
  - 验证：内置工具行为不变；没配服务器时 system 与现在逐字节相同
  - _Requirements: 2.3, 6.1_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“`ToolCatalog` 接入动态工具，`ContextHandler` 加说明”，满足需求 2.3, 6.1，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: 内置工具行为不变；没配服务器时 system 与现在逐字节相同 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 8. `McpRegistry` 和 `McpTool`
  - File: tantalum-shell/src/test/java/tantalum/shell/mcp/McpRegistryTest.java, tantalum-shell/src/main/java/tantalum/shell/mcp/McpRegistry.java, tantalum-shell/src/main/java/tantalum/shell/mcp/McpTool.java
  - 管理连接；工具恒为独占；未放行工具调用返回错误
  - 验证：重名和超上限被跳过；未放行工具服务器收不到调用
  - _Requirements: 2.1–2.4, 3.1, 6.2, P10_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“`McpRegistry` 和 `McpTool`”，满足需求 2.1–2.4, 3.1, 6.2, P10，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: 重名和超上限被跳过；未放行工具服务器收不到调用 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 9. `Engine` 装配和关闭
  - File: tantalum-shell/src/main/java/tantalum/shell/boot/Engine.java
  - 创建 `McpRegistry`，加入目录，关闭时释放
  - 验证：shell 全量通过；服务器不可用时服务正常启动
  - _Requirements: 5.1, 5.4_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“`Engine` 装配和关闭”，满足需求 5.1, 5.4，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: shell 全量通过；服务器不可用时服务正常启动 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 10. 多用户、回放集成测试
  - File: tantalum-shell/src/test/java/tantalum/shell/mcp/McpMultiUserTest.java, tantalum-shell/src/test/java/tantalum/shell/mcp/McpReplayTest.java
  - 两用户并发结果不串；经过钩子和 journal；停掉服务器后回放和重启视图逐字节相同且没有调用
  - 验证：全过
  - _Requirements: 3.1, 3.6, 7.2, 7.6_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“多用户、回放集成测试”，满足需求 3.1, 3.6, 7.2, 7.6，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: 全过 | 完成后把任务标为 [x] 并用 log-implementation 记录_

## 阶段 3：验证和收尾

- [ ] 11. e2e
  - File: tantalum-examples/src/test/java/tantalum/examples/E2eServer.java, tantalum-examples/src/test/java/tantalum/examples/E2eModel.java, console/e2e/mcp.spec.ts
  - 进程内 MCP 服务器；工具链显示 MCP 工具
  - 验证：Playwright 全过
  - _Requirements: 7.5_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“e2e”，满足需求 7.5，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: Playwright 全过 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 12. 真实 Claude 场景
  - File: tantalum-examples/src/test/java/tantalum/examples/ClaudeScenarioTest.java, .spec-workflow/steering/tech.md
  - 严格校验模式下多步任务；注入场景：`delete_all` 未放行，服务器未收到调用，模型是否尝试只记录
  - 验证：判定标准、结论、用量写进 tech.md
  - _Requirements: 7.3, 7.4_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“真实 Claude 场景”，满足需求 7.3, 7.4，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: 判定标准、结论、用量写进 tech.md | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 13. 文档
  - File: README.md, .spec-workflow/steering/tech.md
  - MCP 一节；写明共享身份、提示注入风险、取消后结果未知、工具独占、新工具只对新会话可见、不支持 stdio 和 OAuth；更新能力对照表
  - 验证：与实现一致
  - _Requirements: 6.3_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“文档”，满足需求 6.3，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: 与实现一致 | 完成后把任务标为 [x] 并用 log-implementation 记录_

- [ ] 14. 全量验证和提交
  - File: （无新文件）
  - `mvn verify`、vitest、tsc、build、e2e；提交前扫描密钥
  - 验证：全过；汇报对照表和仍缺的能力
  - _Requirements: All_
  - _Prompt: Implement the task for spec mcp-client, first run spec-workflow-guide to get the workflow guide then implement the task: Role: Java 25 工程师 | Task: 完成“全量验证和提交”，满足需求 All，遵守 design.md | Restrictions: 只做这一件事；不改 kernel 和 value；凭据不进日志、journal、响应 | Success: 全过；汇报对照表和仍缺的能力 | 完成后把任务标为 [x] 并用 log-implementation 记录_
