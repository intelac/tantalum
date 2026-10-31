# Tasks Document

工作方式：一次只做一个 task，完成后汇报改了什么、验证结果、是否偏离 spec、下一步，再继续。提交代码要等 Yi 要求。不复制 DSH 的组件代码。

## 阶段 0：后端认证

- [x] 1. 加入 Spring Security 依赖，确认现有测试的影响
  - File: tantalum-shell/pom.xml
  - 验证：依赖无冲突；记录哪些测试因此失败
  - _Requirements: 9, NFR 安全_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“加入 Spring Security 依赖，确认现有测试的影响”，满足需求 9, NFR 安全，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 依赖无冲突；记录哪些测试因此失败_

- [x] 2. `UserStore` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/auth/UserStoreTest.java, tantalum-shell/src/main/java/tantalum/shell/auth/UserStore.java
  - 验证：只存 Argon2 哈希；重开后还在；文件权限 600；停用生效
  - _Requirements: 9.6_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`UserStore` 测试和实现”，满足需求 9.6，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 只存 Argon2 哈希；重开后还在；文件权限 600；停用生效_

- [x] 3. `AdminBootstrap` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/auth/AdminBootstrapTest.java, tantalum-shell/src/main/java/tantalum/shell/auth/AdminBootstrap.java
  - 验证：没有用户且无环境变量时启动失败；没有默认密码
  - _Requirements: 9.9_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`AdminBootstrap` 测试和实现”，满足需求 9.9，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 没有用户且无环境变量时启动失败；没有默认密码_

- [x] 4. `UserCommand` 命令行
  - File: tantalum-shell/src/test/java/tantalum/shell/auth/UserCommandTest.java, tantalum-shell/src/main/java/tantalum/shell/auth/UserCommand.java
  - 验证：添加、重置、停用；输出不含密码
  - _Requirements: 9.10_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`UserCommand` 命令行”，满足需求 9.10，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 添加、重置、停用；输出不含密码_

- [x] 5. `LoginThrottle` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/auth/LoginThrottleTest.java, tantalum-shell/src/main/java/tantalum/shell/auth/LoginThrottle.java
  - 验证：按账号和来源限速；成功后清零；到期恢复
  - _Requirements: 9.7_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`LoginThrottle` 测试和实现”，满足需求 9.7，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 按账号和来源限速；成功后清零；到期恢复_

- [x] 6. `SecurityConfig` 和 `AuthController` 测试
  - File: tantalum-shell/src/test/java/tantalum/shell/web/AuthApiTest.java
  - 验证：先失败：登录、错误提示、401、会话 ID 更换、Cookie 属性、Origin 校验
  - _Requirements: 9.2, 9.3, 9.7, 9.8_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`SecurityConfig` 和 `AuthController` 测试”，满足需求 9.2, 9.3, 9.7, 9.8，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 先失败：登录、错误提示、401、会话 ID 更换、Cookie 属性、Origin 校验_

- [x] 7. 实现 `SecurityConfig`、`AuthController`
  - File: tantalum-shell/src/main/java/tantalum/shell/auth/SecurityConfig.java, tantalum-shell/src/main/java/tantalum/shell/web/AuthController.java
  - 验证：上一个测试通过
  - _Requirements: 9.2, 9.3, 9.7, 9.8_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“实现 `SecurityConfig`、`AuthController`”，满足需求 9.2, 9.3, 9.7, 9.8，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 上一个测试通过_

- [x] 8. `ApiController` 改为从会话取 principal
  - File: tantalum-shell/src/main/java/tantalum/shell/web/ApiController.java
  - 验证：`X-Principal` 被忽略有测试
  - _Requirements: 9.4_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`ApiController` 改为从会话取 principal”，满足需求 9.4，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: `X-Principal` 被忽略有测试_

- [x] 9. `TestServer` 改为先登录，修复现有接口测试
  - File: tantalum-shell/src/test/java/tantalum/shell/web/TestServer.java
  - 验证：全部现有测试通过，不放宽断言
  - _Requirements: NFR 测试_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`TestServer` 改为先登录，修复现有接口测试”，满足需求 NFR 测试，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 全部现有测试通过，不放宽断言_

- [x] 10. 退出和停用时断开 SSE
  - File: tantalum-shell/src/main/java/tantalum/shell/sse/SseHub.java, tantalum-shell/src/test/java/tantalum/shell/sse/SseHubTest.java
  - 验证：`closeAll` 生效；退出后订阅断开
  - _Requirements: 9.5, 9.10_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“退出和停用时断开 SSE”，满足需求 9.5, 9.10，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: `closeAll` 生效；退出后订阅断开_

- [x] 11. 两个账号的隔离测试
  - File: tantalum-shell/src/test/java/tantalum/shell/web/MultiUserTest.java
  - 验证：会话、运行、回放、SSE 互不可见；对方 ID 返回 404
  - _Requirements: 9.11_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“两个账号的隔离测试”，满足需求 9.11，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 会话、运行、回放、SSE 互不可见；对方 ID 返回 404_

- [x] 12. `ToolContext` 和插件接口改动
  - File: tantalum-shell/src/main/java/tantalum/shell/plugin/ToolContext.java, tantalum-shell/src/main/java/tantalum/shell/plugin/Tool.java
  - 验证：编译通过；现有工具和测试同步更新
  - _Requirements: 11.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`ToolContext` 和插件接口改动”，满足需求 11.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 编译通过；现有工具和测试同步更新_

- [x] 13. `Workspaces` 测试和实现
  - File: tantalum-shell/src/test/java/tantalum/shell/tool/WorkspacesTest.java, tantalum-shell/src/main/java/tantalum/shell/tool/Workspaces.java
  - 验证：每人一个目录，权限 700；共享模式有提示
  - _Requirements: 11.1, 11.2, 11.5_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`Workspaces` 测试和实现”，满足需求 11.1, 11.2, 11.5，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 每人一个目录，权限 700；共享模式有提示_

- [x] 14. 工具和 `ContextHandler` 改用用户目录
  - File: tantalum-shell/src/main/java/tantalum/shell/handler/ToolHandler.java, tantalum-shell/src/main/java/tantalum/shell/handler/ContextHandler.java
  - 验证：按运行所属用户取目录；`AGENTS.md` 按用户读取
  - _Requirements: 11.1, 11.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“工具和 `ContextHandler` 改用用户目录”，满足需求 11.1, 11.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 按运行所属用户取目录；`AGENTS.md` 按用户读取_

- [x] 15. 用户名校验
  - File: tantalum-shell/src/main/java/tantalum/shell/auth/UserStore.java, tantalum-shell/src/test/java/tantalum/shell/auth/UserStoreTest.java
  - 验证：`..`、`/`、大写和空白都被拒绝
  - _Requirements: 11.4_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“用户名校验”，满足需求 11.4，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: `..`、`/`、大写和空白都被拒绝_

- [x] 16. 两账号读不到对方文件
  - File: tantalum-shell/src/test/java/tantalum/shell/web/MultiUserTest.java
  - 验证：`..`、绝对路径、符号链接都试过
  - _Requirements: 11.2, 11.6_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“两账号读不到对方文件”，满足需求 11.2, 11.6，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: `..`、绝对路径、符号链接都试过_

- [x] 17. `ArchitectureTest` 加入 `auth`
  - File: tantalum-shell/src/test/java/tantalum/shell/ArchitectureTest.java
  - 验证：Spring 边界规则生效
  - _Requirements: NFR 架构_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`ArchitectureTest` 加入 `auth`”，满足需求 NFR 架构，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: Spring 边界规则生效_

## 阶段 1：对话投影

- [x] 18. `TranscriptItem` 和条目类型
  - File: tantalum-shell/src/main/java/tantalum/shell/conversation/TranscriptItem.java
  - 验证：编译通过；ID 稳定
  - _Requirements: 4, 10.2_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`TranscriptItem` 和条目类型”，满足需求 4, 10.2，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 编译通过；ID 稳定_

- [x] 19. `Transcript` 测试：消息和思考顺序
  - File: tantalum-shell/src/test/java/tantalum/shell/conversation/TranscriptTest.java
  - 验证：先失败：多次调用前后的思考都保留，顺序正确
  - _Requirements: 4.2, 4.4_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`Transcript` 测试：消息和思考顺序”，满足需求 4.2, 4.4，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 先失败：多次调用前后的思考都保留，顺序正确_

- [x] 20. `Transcript` 测试：工具链
  - File: tantalum-shell/src/test/java/tantalum/shell/conversation/TranscriptToolTest.java
  - 验证：先失败：并行分组、独占、等待中、拦截、无效参数、合成错误
  - _Requirements: 4.5_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`Transcript` 测试：工具链”，满足需求 4.5，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 先失败：并行分组、独占、等待中、拦截、无效参数、合成错误_

- [x] 21. 实现 `Transcript`（`StepTiming` 并入 `ToolStep`，不单独建类）
  - File: tantalum-shell/src/main/java/tantalum/shell/conversation/Transcript.java, tantalum-shell/src/main/java/tantalum/shell/conversation/StepTiming.java
  - 验证：上面两个测试通过
  - _Requirements: 4.4, 4.5_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“实现 `Transcript` 和 `StepTiming`”，满足需求 4.4, 4.5，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 上面两个测试通过_

- [x] 22. 回放结果与实时结果相同
  - File: tantalum-shell/src/test/java/tantalum/shell/conversation/TranscriptReplayTest.java
  - 验证：相同事件得到相同条目
  - _Requirements: 4.5, 10.5_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“回放结果与实时结果相同”，满足需求 4.5, 10.5，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 相同事件得到相同条目_

- [x] 23. `Cleanroom` 增加投影回调
  - File: tantalum-shell/src/main/java/tantalum/shell/loop/Cleanroom.java, tantalum-shell/src/test/java/tantalum/shell/loop/CleanroomTest.java
  - 验证：回调异常不影响环；环测试继续通过
  - _Requirements: 10.4_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`Cleanroom` 增加投影回调”，满足需求 10.4，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 回调异常不影响环；环测试继续通过_

- [x] 24. `Startup` 回放时重建 `Transcript`
  - File: tantalum-shell/src/main/java/tantalum/shell/boot/Startup.java, tantalum-shell/src/test/java/tantalum/shell/boot/StartupTest.java
  - 验证：重启后条目完整
  - _Requirements: 4.5_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`Startup` 回放时重建 `Transcript`”，满足需求 4.5，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 重启后条目完整_

- [x] 25. SSE 事件 `item` 和补读
  - File: tantalum-shell/src/main/java/tantalum/shell/sse/SseHub.java, tantalum-shell/src/test/java/tantalum/shell/sse/SseHubTest.java
  - 验证：按用户隔离；可补读
  - _Requirements: 10.4_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“SSE 事件 `item` 和补读”，满足需求 10.4，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 按用户隔离；可补读_

## 阶段 2：后端接口

- [x] 26. `GET /api/sessions`
  - File: tantalum-shell/src/test/java/tantalum/shell/web/SessionApiTest.java, tantalum-shell/src/main/java/tantalum/shell/web/ApiController.java
  - 验证：最近活动在前；标题和状态正确
  - _Requirements: 3.1, 10.1_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`GET /api/sessions`”，满足需求 3.1, 10.1，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 最近活动在前；标题和状态正确_

- [x] 27. `GET /api/sessions/{id}/messages`
  - File: tantalum-shell/src/test/java/tantalum/shell/web/MessagesApiTest.java, tantalum-shell/src/main/java/tantalum/shell/web/ApiController.java
  - 验证：含工具链和排队内容；他人返回 404
  - _Requirements: 10.2_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`GET /api/sessions/{id}/messages`”，满足需求 10.2，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 含工具链和排队内容；他人返回 404_

- [x] 28. `GET /api/config`
  - File: tantalum-shell/src/test/java/tantalum/shell/web/ConfigApiTest.java, tantalum-shell/src/main/java/tantalum/shell/web/ApiController.java
  - 验证：响应不含 Key
  - _Requirements: 10.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`GET /api/config`”，满足需求 10.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 响应不含 Key_

- [x] 29. `GET /api/runs/{id}/trace`
  - File: tantalum-shell/src/test/java/tantalum/shell/web/TraceApiTest.java, tantalum-shell/src/main/java/tantalum/shell/web/ApiController.java
  - 验证：时间条和事件列表正确
  - _Requirements: 6.1_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`GET /api/runs/{id}/trace`”，满足需求 6.1，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 时间条和事件列表正确_

- [x] 30. `SpaController`
  - File: tantalum-shell/src/test/java/tantalum/shell/web/SpaControllerTest.java, tantalum-shell/src/main/java/tantalum/shell/web/SpaController.java
  - 验证：前端路径回退到首页；`/api` 不受影响
  - _Requirements: 3.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`SpaController`”，满足需求 3.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 前端路径回退到首页；`/api` 不受影响_

## 阶段 3：前端基础

- [x] 31. 加依赖并固定版本
  - File: console/package.json
  - 验证：构建通过
  - _Requirements: NFR 架构_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“加依赖并固定版本”，满足需求 NFR 架构，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 构建通过_

- [x] 32. 设计变量和深浅色
  - File: console/src/theme/tokens.css, console/src/theme/theme.ts
  - 验证：切换和保留选择；无明显闪烁
  - _Requirements: 2.1, 2.2, 2.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“设计变量和深浅色”，满足需求 2.1, 2.2, 2.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 切换和保留选择；无明显闪烁_

- [x] 33. 文案集中
  - File: console/src/text/zh.ts
  - 验证：类型检查通过
  - _Requirements: NFR 架构_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“文案集中”，满足需求 NFR 架构，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 类型检查通过_

- [x] 34. 基础组件：按钮、标签页
  - File: console/src/ui/Button.tsx, console/src/ui/Tabs.tsx
  - 验证：键盘可用，焦点可见
  - _Requirements: 2.4_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“基础组件：按钮、标签页”，满足需求 2.4，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 键盘可用，焦点可见_

- [x] 35. 基础组件：弹窗和菜单
  - File: console/src/ui/Dialog.tsx, console/src/ui/Menu.tsx
  - 验证：Esc 关闭并归还焦点
  - _Requirements: 2.4_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“基础组件：弹窗和菜单”，满足需求 2.4，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: Esc 关闭并归还焦点_

- [x] 36. REST 客户端和类型
  - File: console/src/api/client.ts, console/src/api/types.ts
  - 验证：401 统一处理；Cookie 带上
  - _Requirements: 9.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“REST 客户端和类型”，满足需求 9.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 401 统一处理；Cookie 带上_

- [x] 37. SSE 客户端改用 Cookie 并支持 `item`
  - File: console/src/api/sse.ts, console/src/api/sse.test.ts
  - 验证：断线重连和补读测试通过
  - _Requirements: 8.1_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“SSE 客户端改用 Cookie 并支持 `item`”，满足需求 8.1，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 断线重连和补读测试通过_

- [x] 38. `ConversationStore`
  - File: console/src/store/ConversationStore.ts, console/src/store/ConversationStore.test.ts
  - 验证：按 ID 覆盖；分片只通知一个条目
  - _Requirements: 4.7, 4.10_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`ConversationStore`”，满足需求 4.7, 4.10，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 按 ID 覆盖；分片只通知一个条目_

- [x] 39. 路由钩子
  - File: console/src/app/router.ts, console/src/app/router.test.ts
  - 验证：三类路径可解析
  - _Requirements: 3.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“路由钩子”，满足需求 3.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 三类路径可解析_

## 阶段 4：登录和框架

- [x] 40. 原创品牌标志
  - File: console/src/shell/Brand.tsx
  - 验证：“Ta” 方格加字样，深浅色都清楚
  - _Requirements: U2_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“原创品牌标志”，满足需求 U2，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: “Ta” 方格加字样，深浅色都清楚_

- [x] 41. 登录页
  - File: console/src/auth/LoginPage.tsx
  - 验证：截图；错误提示不透露哪项错
  - _Requirements: 9.1, 9.2_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“登录页”，满足需求 9.1, 9.2，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 截图；错误提示不透露哪项错_

- [x] 42. 当前用户和未登录跳转
  - File: console/src/auth/useCurrentUser.ts, console/src/app/App.tsx
  - 验证：登录后回到原页面
  - _Requirements: 9.2, 9.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“当前用户和未登录跳转”，满足需求 9.2, 9.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 登录后回到原页面_

- [x] 43. `AppFrame`
  - File: console/src/shell/AppFrame.tsx
  - 验证：拖动范围、断点、抽屉；390 px 无横向滚动
  - _Requirements: 1.1–1.4_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`AppFrame`”，满足需求 1.1–1.4，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 拖动范围、断点、抽屉；390 px 无横向滚动_

- [x] 44. 侧栏和会话行
  - File: console/src/shell/Sidebar.tsx, console/src/shell/SessionRow.tsx
  - 验证：运行中和待恢复状态可见
  - _Requirements: 3.1, 3.4, 3.5_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“侧栏和会话行”，满足需求 3.1, 3.4, 3.5，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 运行中和待恢复状态可见_

- [x] 45. 新会话和切换
  - File: console/src/shell/Sidebar.tsx, console/src/app/App.tsx
  - 验证：第一次提交才创建；刷新回到原会话
  - _Requirements: 3.2, 3.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“新会话和切换”，满足需求 3.2, 3.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 第一次提交才创建；刷新回到原会话_

## 阶段 5：对话区

- [x] 46. `Markdown` 和安全测试
  - File: console/src/markdown/Markdown.tsx, console/src/markdown/Markdown.test.tsx
  - 验证：原始 HTML 不渲染；`javascript:` 链接被拒
  - _Requirements: 4.3, NFR 安全_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`Markdown` 和安全测试”，满足需求 4.3, NFR 安全，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 原始 HTML 不渲染；`javascript:` 链接被拒_

- [x] 47. `CodeBlock`
  - File: console/src/markdown/CodeBlock.tsx
  - 验证：语言标签、复制、高亮
  - _Requirements: 4.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`CodeBlock`”，满足需求 4.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 语言标签、复制、高亮_

- [x] 48. `UserBubble` 和空状态
  - File: console/src/conversation/UserBubble.tsx, console/src/conversation/EmptyState.tsx
  - 验证：截图
  - _Requirements: 4.1, 4.2_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`UserBubble` 和空状态”，满足需求 4.1, 4.2，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 截图_

- [x] 49. `ThinkingBlock`
  - File: console/src/process/ThinkingBlock.tsx
  - 验证：流式显示；没有内容时不显示空块
  - _Requirements: 4.4_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`ThinkingBlock`”，满足需求 4.4，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 流式显示；没有内容时不显示空块_

- [x] 50. `ToolRow`
  - File: console/src/process/ToolRow.tsx
  - 验证：展开看参数和结果；错误有明显标记
  - _Requirements: 4.6_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`ToolRow`”，满足需求 4.6，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 展开看参数和结果；错误有明显标记_

- [x] 51. `ToolChain` 和 `ToolGroup`
  - File: console/src/process/ToolChain.tsx, console/src/process/ToolGroup.tsx
  - 验证：并行成组、等待中、拦截原因、耗时
  - _Requirements: 4.5_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`ToolChain` 和 `ToolGroup`”，满足需求 4.5，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 并行成组、等待中、拦截原因、耗时_

- [x] 52. `ProcessGroup`
  - File: console/src/process/ProcessGroup.tsx
  - 验证：运行中展开，结束后折叠并显示用时
  - _Requirements: 4.4_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`ProcessGroup`”，满足需求 4.4，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 运行中展开，结束后折叠并显示用时_

- [x] 53. `MessageList` 和回到底部
  - File: console/src/conversation/MessageList.tsx, console/src/conversation/JumpToBottom.tsx
  - 验证：自动滚动规则；500 条消息流式更新仍可用
  - _Requirements: 4.7, 4.8, 4.10_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`MessageList` 和回到底部”，满足需求 4.7, 4.8, 4.10，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 自动滚动规则；500 条消息流式更新仍可用_

- [x] 54. 中断和失败的显示
  - File: console/src/conversation/RunStatusRow.tsx
  - 验证：超长、拒答、限流有各自说明
  - _Requirements: 4.9_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“中断和失败的显示”，满足需求 4.9，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 超长、拒答、限流有各自说明_

## 阶段 6：输入栏

- [x] 55. `Composer` 测试
  - File: console/src/composer/Composer.test.tsx
  - 验证：先失败：Enter、Shift+Enter、输入法组字、拒绝后保留、同一 requestId
  - _Requirements: 5.1, 5.2, 5.5_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`Composer` 测试”，满足需求 5.1, 5.2, 5.5，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 先失败：Enter、Shift+Enter、输入法组字、拒绝后保留、同一 requestId_

- [x] 56. 实现 `Composer`
  - File: console/src/composer/Composer.tsx
  - 验证：上一个测试通过
  - _Requirements: 5.1, 5.2, 5.5_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“实现 `Composer`”，满足需求 5.1, 5.2, 5.5，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 上一个测试通过_

- [x] 57. 排队和停止
  - File: console/src/composer/QueueDock.tsx, console/src/composer/Composer.tsx
  - 验证：排队可见；停止产生 `Cancel`；界面没有 `steer` 入口
  - _Requirements: 5.3, 5.4_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“排队和停止”，满足需求 5.3, 5.4，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 排队可见；停止产生 `Cancel`；界面没有 `steer` 入口_

- [x] 58. 草稿按会话保留
  - File: console/src/composer/drafts.ts, console/src/composer/drafts.test.ts
  - 验证：切换和登录过期后保留
  - _Requirements: 5.6, 9.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“草稿按会话保留”，满足需求 5.6, 9.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 切换和登录过期后保留_

- [x] 59. 状态行
  - File: console/src/composer/StatusBar.tsx
  - 验证：token 和上下文占比；窄屏收紧
  - _Requirements: 6.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“状态行”，满足需求 6.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: token 和上下文占比；窄屏收紧_

## 阶段 7：轨迹和设置

- [x] 60. `TracePage` 和标签页切换
  - File: console/src/trace/TracePage.tsx
  - 验证：地址栏可定位
  - _Requirements: 1.1, 6.1_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`TracePage` 和标签页切换”，满足需求 1.1, 6.1，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 地址栏可定位_

- [x] 61. `TimeBars`
  - File: console/src/trace/TimeBars.tsx
  - 验证：三行时间条，用 CSS 实现
  - _Requirements: 6.1_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`TimeBars`”，满足需求 6.1，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 三行时间条，用 CSS 实现_

- [x] 62. `EventList`
  - File: console/src/trace/EventList.tsx
  - 验证：类型标签和 Handler
  - _Requirements: 6.1_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`EventList`”，满足需求 6.1，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 类型标签和 Handler_

- [x] 63. `ModelCallDetail`
  - File: console/src/trace/ModelCallDetail.tsx
  - 验证：显示回放得出的完整 prompt
  - _Requirements: 6.2, 6.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“`ModelCallDetail`”，满足需求 6.2, 6.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 显示回放得出的完整 prompt_

- [x] 64. 设置弹窗和外观页
  - File: console/src/settings/SettingsDialog.tsx, console/src/settings/AppearancePane.tsx
  - 验证：三张主题卡片
  - _Requirements: 7.1, 7.2_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“设置弹窗和外观页”，满足需求 7.1, 7.2，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 三张主题卡片_

- [x] 65. 模型页和账号页
  - File: console/src/settings/ModelPane.tsx, console/src/settings/AccountPane.tsx
  - 验证：只读，没有输入 Key 的地方；可退出
  - _Requirements: 7.3, 9.5_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“模型页和账号页”，满足需求 7.3, 9.5，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 只读，没有输入 Key 的地方；可退出_

- [x] 66. 断线和 503 提示
  - File: console/src/shell/ConnectionBanner.tsx
  - 验证：重连后内容与 journal 一致
  - _Requirements: 8.1–8.3_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“断线和 503 提示”，满足需求 8.1–8.3，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 重连后内容与 journal 一致_

## 阶段 8：验证和收尾

- [x] 67. Playwright 基础设施和脚本化模型启动
  - File: console/playwright.config.ts, tantalum-examples/src/test/java/tantalum/examples/E2eServer.java
  - 验证：能启动并登录，不访问外网
  - _Requirements: NFR 测试_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“Playwright 基础设施和脚本化模型启动”，满足需求 NFR 测试，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 能启动并登录，不访问外网_

- [x] 68. 端到端：登录、提问、工具链
  - File: console/e2e/chat.spec.ts
  - 验证：工具链和思考都显示；刷新后还原
  - _Requirements: 4, 9_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“端到端：登录、提问、工具链”，满足需求 4, 9，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 工具链和思考都显示；刷新后还原_

- [x] 69. 端到端：排队、停止、切换会话
  - File: console/e2e/run-control.spec.ts
  - 验证：通过
  - _Requirements: 3, 5_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“端到端：排队、停止、切换会话”，满足需求 3, 5，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 通过_

- [x] 70. 端到端：断线重连、退出
  - File: console/e2e/connection.spec.ts
  - 验证：通过
  - _Requirements: 8, 9.5_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“端到端：断线重连、退出”，满足需求 8, 9.5，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 通过_

- [x] 71. 真实模型走查
  - File: （不改文件）
  - 验证：strata 和 Claude 各跑一次带工具的对话
  - _Requirements: 4_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“真实模型走查”，满足需求 4，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: strata 和 Claude 各跑一次带工具的对话_

- [x] 72. 截图对照
  - File: （不改文件）
  - 验证：1280、390 px，深浅色，和 DSH 截图一起发给 Yi
  - _Requirements: NFR 测试_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“截图对照”，满足需求 NFR 测试，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 1280、390 px，深浅色，和 DSH 截图一起发给 Yi_

- [x] 73. 体积检查
  - File: （不改文件）
  - 验证：JS gzip 不超过 300 KB，超过时说明原因
  - _Requirements: NFR 性能_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“体积检查”，满足需求 NFR 性能，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: JS gzip 不超过 300 KB，超过时说明原因_

- [x] 74. 第三方声明
  - File: THIRD_PARTY_NOTICES.md
  - 验证：列出依赖的许可证和对照 DSH 的说明
  - _Requirements: U1_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“第三方声明”，满足需求 U1，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 列出依赖的许可证和对照 DSH 的说明_

- [x] 75. 更新 README 和 `tech.md`
  - File: README.md, .spec-workflow/steering/tech.md
  - 验证：登录、用户管理、`X-Principal` 已移除
  - _Requirements: NFR_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“更新 README 和 `tech.md`”，满足需求 NFR，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: 登录、用户管理、`X-Principal` 已移除_

- [x] 76. 完整验证
  - File: （不改文件）
  - 验证：`mvn verify`、`pnpm test`、端到端全部通过；逐条核对需求
  - _Requirements: 全部_
  - _Prompt: Role: 全栈工程师（Java 25、React 19） | Task: 完成“完整验证”，满足需求 全部，遵守 design.md | Restrictions: 只做这一件事，不改无关文件；kernel 和 value 不改 | Success: `mvn verify`、`pnpm test`、端到端全部通过；逐条核对需求_
