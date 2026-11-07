# Tasks Document

工作方式同 `waiting-state`：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志（只记结论和数字）。阶段末各跑一次全量回归（`scratchpad/regress.sh`），提交推送、打包更新 8091、几句话汇报。本 spec 不加依赖，本地 `mvn -o … -am`。提交前扫描密钥。

安全规则（每个任务都适用）：令牌只存哈希、不进日志；密码不进日志、不进测试输出；接口错误不透露用户名是否存在（登录和改密码）；默认只绑 `127.0.0.1`。

路径前缀同 `waiting-state`（`shell/`、`shelltest/`、`value/`、`examples/`）。

每个任务的 `_Prompt` 开头省略的固定部分：“Implement the task for spec ops-hardening-2, first run spec-workflow-guide to get the workflow guide then implement the task: 开始时在 tasks.md 把本任务标成 [-]，做完用 log-implementation 记录，再标成 [x]。”

## 阶段 1：登录表和管理员

- [x] 1. `Logins` 表和用户库升级
  - File: shell/auth/Logins.java（新）；shell/auth/UserStore.java（`PRAGMA user_version` 1 → 2：`admin` 列、`logins` 表、索引；没有管理员时最早的账号成管理员并记日志；`Account`、`list`、`add(name, password, admin)`、`enable`、`setAdmin`、`Status.admin`、`Refused.Kind.LAST_ADMIN`）；shell/auth/AdminBootstrap.java（第一个账号是管理员）
  - 测试：shelltest/auth/LoginsTest（新）：创建/查/过期/删/全删/restamp/sweep，表里只有哈希；UserStoreTest 加：版本 1 的库升级后最早账号是管理员、`list`、`enable`、`setAdmin`、最后一个启用的管理员不能被停用或取消、`add(admin)`
  - _Requirements: 1.1, 1.5, 1.6, 2.1_
  - _Prompt: Role: 存储 | Task: 登录表和 users 表升级 | Restrictions: 用户库不能重建，只能 ALTER；升级幂等 | Success: 新旧库都能打开，测试过_

- [x] 2. `LoginContextRepository` 代替 HttpSession
  - File: shell/auth/LoginContextRepository.java、LoginCookie.java（新）；shell/auth/SecurityConfig.java（用新仓库；删 `DisabledUserFilter`；`/api/users/**` 要 `ROLE_ADMIN`，403 回 `auth/forbidden`）；shell/auth/LoginCheck.java（改按登录行；`stillValid(login, now)`）；shell/web/AuthController.java（登录写表、发 Cookie；退出删行、清 Cookie；`me` 带 `admin`）；shell/web/EventStreamController.java（登录 id 从仓库取，心跳查表）；shell/web/ErrorCode.java（`AUTH_FORBIDDEN`）
  - 测试：AuthApiTest 加：登录后关掉服务、同数据目录重开仍 200；退出后 401；Cookie 名、属性；无效令牌清 Cookie；非管理员 `/api/users` 403；LoginStreamsTest 照过（退出、改密码后 SSE 断）；ErrorCodeTest 的字面值比对
  - _Requirements: 1.1–1.4, 2.3, 2.4（403 部分）_
  - _Prompt: Role: 认证 | Task: 登录放进表，Cookie 只有令牌 | Restrictions: 不用 HttpSession；令牌不进日志；每分钟最多写一次最后访问时间 | Success: 重启后登录不掉，AuthApiTest/LoginStreamsTest 全过_

- [x] 3. 用户管理接口和改密码
  - File: shell/web/UsersController.java（新）；shell/web/AuthController.java（`PUT /api/auth/password`）；shell/web/ErrorCode.java（`USER_BAD_NAME`、`USER_RESERVED_NAME`、`USER_EXISTS`、`USER_NOT_FOUND`、`USER_SELF`、`USER_LAST_ADMIN`）；shell/auth/UserCommand.java（`add --admin`、`admin`、`user`、`enable`、`list`）+ `CliText` 三种语言
  - 测试：shelltest/web/UsersApiTest（新）：接口表全部分支（含对自己 400、最后一个管理员 409、管理员重置后对方 401、停用后 401）；AuthApiTest 加自己改密码（当前密码错 401 并计入限速；成功后别处 401、当前 200）；UserCommandTest 加新子命令
  - _Requirements: 2.2, 2.4–2.7_
  - _Prompt: Role: Web | Task: 用户管理接口、改密码、命令行 | Restrictions: 密码不进日志；写操作过 Origin 校验 | Success: UsersApiTest 全过_

阶段 1 结束：回归、提交、推送、打包、更新 8091（部署前在页面上登着，部署后不掉；Yi 的账号成管理员），汇报。

## 阶段 2：名额和自愈

- [x] 4. 按用户名额
  - File: shell/loop/UserPermits.java（新）；shell/loop/LaneDispatcher.java（取名额先取用户的；`released(Principal)`、`discarded` 按用户归还）；shell/loop/UserLane.java（`Exit.discarded` 带用户或改为 `discarded(List<EventEnvelope>)`）；shell/boot/Engine.java（`lanes.released(envelope.principal())`）；shell/boot/TantalumProperties.java（`Loop.maxUserPermits`，默认 `inputRingSize / 4`，校验 `maxUserInFlight ≤ maxUserPermits ≤ inputRingSize`）；application.properties
  - 测试：shelltest/loop/LaneDispatcherTest（新）：A 在途数到上限时 A 的下一个事件等、B 的照常通过；A 归还一个后 A 继续；`discarded` 归还；`awaitIdle` 不变；TantalumPropertiesTest 加配置校验；真实服务用例不加：“在途”只是进 cleanroom 前的一瞬，真实服务里凑不出稳定的“到上限”，4.3 由分发器单元测试覆盖；停住的事件等总名额的边角用 UserPermitsTest 直接测
  - _Requirements: 4.1–4.4_
  - _Prompt: Role: 管线 | Task: 每个用户的放行名额上限 | Restrictions: 总名额逻辑不变；不加锁的热路径不变 | Success: 新测试过，MultiUserTest 全过_

- [x] 5. 载入失败自愈
  - File: shell/loop/Residency.java（`Clock`、`retry`、失败时刻；过期后 `pin` 返回 COLD、`failure` 返回 null；`fail` 返回是否第一次）；shell/loop/UserLane.java（第一次 error、之后 warn）；shell/loop/Intake.java 和 shell/web 的 500 文字；shell/boot/TantalumProperties.java（`Residency.loadRetry` 默认 1m）；shell/boot/Engine.java（传入）
  - 测试：shelltest/loop/ResidencyTest（新）：失败后 retry 内 FAILED、之后 COLD；UserLaneTest 加：第一次载入失败、过了 retry 第二次成功；RestartInterruptTest 里“载入失败的会话不拦住启动”照过
  - _Requirements: 5.1–5.4_
  - _Prompt: Role: 管线 | Task: 载入失败过一会儿再试 | Restrictions: 半截状态不能当新会话；日志不刷屏 | Success: 新测试过_

阶段 2 结束：回归、提交、推送、打包、更新 8091、汇报。

## 阶段 3：控制台、端到端、文档

- [x] 6. 控制台：修改密码、用户页
  - File: console/src/settings/PasswordForm.tsx、UsersPane.tsx（新，各带 test）；console/src/settings/AccountPane.tsx（挂改密码）；console/src/settings/SettingsDialog.tsx（按 `me.admin` 显示“用户”）；console/src/api/types.ts（`User.admin`、`Account`）、client.ts（`changePassword`、`listUsers`、`createUser`、`updateUser`、`resetUserPassword`）；console/src/text/{zh,en,ja}.ts、errors.ts（`auth/forbidden`、`user/*`）
  - 测试：vitest：改密码成功/当前密码错；用户页列表、新建、对自己的两个按钮禁用、最后一个管理员的错误提示；非管理员没有“用户”页
  - _Requirements: 3.1–3.4_
  - _Prompt: Role: 前端 | Task: 改密码和用户页 | Restrictions: 文字全部走 useText；密码框 type=password、不回显 | Success: vitest 过，tsc、pnpm build 过_

- [x] 7. 端到端
  - File: console/e2e/users.spec.ts（新）；console/e2e/app.ts（如需加管理员账号常量；E2eServer 的第一个账号本来就是管理员）
  - 测试：管理员新建用户、用新用户登录、非管理员看不到“用户”页；管理员重置密码后旧密码登不上；自己改密码后用新密码登录；现有登录相关 e2e 照过
  - _Requirements: 3.1–3.3, 2.5, 2.6_
  - _Prompt: Role: 端到端 | Task: Playwright 用例 | Restrictions: 结束时把改过的密码改回去，别影响别的用例 | Success: 全部 e2e 过_

- [-] 8. 文档和 8091
  - File: README.md（“登录和用户”改写；“运维”加两个配置项）；README.en.md（一句）；.spec-workflow/steering/tech.md（结果）；.spec-workflow/BACKLOG.md（第 0 节这项完成；第 4 节两条缺口标完成；第 5 节删“登录会话不持久化、没有用户管理界面”）
  - 8091：Yi 是管理员；加一个测试用户 `demo`、改密码、停用、再启用；页面上截一张“用户”页
  - _Requirements: 6.1, 6.2_
  - _Prompt: Role: 文档 | Task: 说明和收尾 | Restrictions: 说人话 | Success: 三处都改到，8091 验过_

阶段 3 结束：回归、提交、推送、打包、更新 8091、汇报。
