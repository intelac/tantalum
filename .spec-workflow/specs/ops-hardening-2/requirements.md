# Requirements Document

## Introduction

BACKLOG 第 0 节“离 0.1 还差什么”的第二项。四件事，都是企业部署第一周会碰到的：

1. **登录不随重启丢。** 现在身份放在 Tomcat 的内存会话里（`SecurityConfig.securityContextRepository` 返回 `HttpSessionSecurityContextRepository`），服务器一重启所有人被踢回登录页。
2. **网页上管用户。** 现在只有命令行 `users add|reset|disable`（`UserCommand`），管理员要登到服务器上敲命令；也没有“管理员”这个概念——第一个账号由环境变量创建（`AdminBootstrap`），之后人人平等。用户自己也改不了密码。
3. **放行名额按用户限。** `LaneDispatcher` 是一个全局信号量（容量等于 cleanroom 环的大小，1024）；一个用户的会话卡住、大量工具结果在途，理论上能占满全部名额，别的用户的事件进不去（`ops-hardening` 阶段 2 留下的缺口）。
4. **载入失败的会话自愈。** 会话文件读失败一次，这个会话到重启前都拒绝（`UserLane.loadOne`：“refusing it until restart”；`Residency.State.FAILED`）。临时性故障（索引库短暂不可用、磁盘一瞬间忙）也要重启才恢复（`ops-hardening` 阶段 5 留下的缺口）。

例子：

| 时间 | 发生了什么 | 现在 | 做完后 |
|---|---|---|---|
| 周一 09:00 | 管理员升级服务器，重启 | 全公司重新登录 | 大家的页面照常刷新，不用登录 |
| 09:05 | 新同事入职 | 管理员登到服务器上敲 `users add` | 管理员在设置页“用户”里填名字和初始密码 |
| 09:06 | 新同事第一次登录 | 密码只能让管理员再敲命令改 | 设置页“账户”里自己改 |
| 10:00 | 某用户电脑上的工具卡死，几十个工具结果在途 | 可能把名额占满，别人的提交 429 | 他最多占总名额的四分之一，别人不受影响 |
| 11:00 | 索引库被备份软件锁了两秒，正好有人打开旧会话 | 这个会话 500 到重启 | 一分钟后再打开就好了 |

### 调研

参照 pi-mono `main`（7f47a2e，2026-11-05）、DSH `main`（fcc0ad4621，2026-11-04）。

- **pi**：单人本机程序，没有登录（`CA/src` 里没有认证）。
- **DSH**：也是单人程序，“账户”是登录 DeepSeek 平台（浏览器 OAuth/PKCE），`packages/api/account-controller/README.md:12,44`：控制器只暴露登录状态，“凭据影响 HTTP 认证，永远不进模型提示词、会话日志和工具结果”；没有多用户、没有管理员页。
- 两家都没有多用户服务器要解决的这几件事，所以这个 spec 的做法标“自定”，只借 DSH 的两条原则：凭据不进日志和会话；登录状态是服务端的事实，界面只读它。
- 放行名额和载入失败是我们自己的架构（Disruptor 环 + 用户通道）才有的问题，参照 `ops-hardening` 的设计和 BACKLOG 第 4 节的两条记录。

### 范围

做：

- 登录记录进 `users.db`（新表 `logins`），Cookie 里只放随机令牌；重启、崩溃都不丢；12 小时不活动过期（沿用 `server.servlet.session.timeout`）；退出、改密码、停用立即失效（现在的口令戳规则不变）。
- 管理员：`users` 表加 `admin` 列；第一个账号是管理员；命令行 `users add --admin`、`users admin <name>`、`users list`。
- 接口：管理员列出、新建、停用、启用、重置密码、设/取消管理员；每个人改自己的密码。
- 控制台：设置页“账户”加“修改密码”；管理员多一个“用户”页。
- `LaneDispatcher` 每个用户的名额上限（配置项，默认总数的四分之一）。
- 载入失败的会话过一段时间自动再试（配置项，默认 1 分钟）。
- 文档、三种语言、8091。

不做：

- 角色更细的权限（只有“管理员 / 普通用户”两种）、SSO、密码策略（长度上限照旧 1024，不强制复杂度）、“忘记密码”邮件。
- 删除用户（会话文件归属要跟着处理，另开）；现在是“停用”。
- 多实例共享登录（多实例 spec 的事）。

### 待批准的决定

| # | 决定 | 理由 |
|---|---|---|
| D1 | **自己存登录，不用 Tomcat 的持久化会话。** Cookie 里是 32 字节随机令牌（Base64url），服务端存它的 SHA-256、用户名、登录时的口令戳、创建和最后访问时间；每次请求更新最后访问时间（最多每分钟写一次）。不再用 `HttpSession` | Tomcat 的 `server.servlet.session.persistent` 只在正常关闭时写文件，崩溃就丢，而且要把 Spring Security 的对象 Java 序列化进文件。自己存一张表，`kill -9` 也不丢，SSE 的“按这次登录断开”直接用令牌 id |
| D2 | **管理员是 `users` 表的一列。** 第一个账号（`AdminBootstrap`）自动是管理员；管理员不能把自己取消、不能停用自己（防止没人能管）；至少保留一个管理员 | 简单、能命令行恢复；不做角色表 |
| D3 | **管理员重置别人的密码后，那个人的所有登录立即失效**（口令戳加一，和现在命令行 `reset` 一样）；**自己改密码后别处的登录失效、当前这个保留** | 改密码通常是因为怀疑泄露 |
| D4 | **每个用户的名额上限默认是总数的四分之一**（`tantalum.loop.max-user-permits`，默认 `input-ring-size / 4` = 256），超过时这个用户的事件在输入环里等，别的用户照常；上限不能小于 `max-user-in-flight` | 一个用户最多拖慢自己；四分之一是经验值，可调 |
| D5 | **载入失败后 `tantalum.residency.load-retry`（默认 1 分钟）内拒绝，之后下一个事件再试**；再失败再等一分钟 | 和现在一样不把半截状态当新会话；临时故障自愈，持续故障有日志、不刷屏 |
| D6 | **停用的用户在插件页、会话列表里不删任何数据**；重新启用后一切照旧 | 会话文件是审计记录 |

## Requirements

### Requirement 1：登录不随重启丢

**User Story:** 作为用户，我希望服务器维护重启后不用重新登录。

#### Acceptance Criteria

1. WHEN 登录成功 THEN 服务器 SHALL 生成随机令牌，在 `users.db` 的 `logins` 表记下它的 SHA-256、用户名、口令戳、创建时间和最后访问时间，Cookie `tantalum.login` 里只放令牌（`HttpOnly`、`SameSite=Strict`，`server.servlet.session.cookie.secure=true` 时带 `Secure`）。
2. WHEN 带着有效令牌的请求到来 THEN 服务器 SHALL 认出用户；WHEN 令牌不存在、已过期（最后访问距今超过 `server.servlet.session.timeout`，默认 12 小时）、用户已停用、口令戳不符 THEN SHALL 回 401；不动 Cookie（带旧令牌的请求可能和刚登录的新令牌并行到达，清掉会把新登录一起清掉——windows-server 任务 13 的实测），退出登录时才清。
3. WHEN 服务器重启（包括 `kill -9`）THEN 之前的登录 SHALL 照常有效；重启后第一个请求不出现登录页。
4. WHEN 用户退出 THEN 这条登录 SHALL 删除，它的 SSE 连接断开（现在 `closeLogin` 的行为）；别处的登录不受影响。
5. 过期的登录 SHALL 在启动时和每次登录成功时清理；表不随历史增长。
6. `logins` 表 SHALL 只存令牌的哈希；令牌不进日志。

### Requirement 2：管理员和用户管理接口

**User Story:** 作为管理员，我希望在网页上加人、停用、重置密码，不用登到服务器上。

#### Acceptance Criteria

1. `users` 表 SHALL 有 `admin` 列；`AdminBootstrap` 创建的第一个账号是管理员；旧库升级时如果没有管理员，SHALL 把最早创建的账号设为管理员并记日志。
2. 命令行 SHALL 支持 `users add [--admin] <name>`、`users admin <name>`（设为管理员）、`users user <name>`（取消管理员）、`users enable <name>`、`users list`（名字、管理员、停用、创建时间）。
3. `GET /api/auth/me` SHALL 多返回 `admin: true|false`。
4. 管理员接口（非管理员回 403 `auth/forbidden`）：`GET /api/users`（列表）、`POST /api/users {name, password, admin}`、`PUT /api/users/{name} {disabled?, admin?}`、`PUT /api/users/{name}/password {password}`。规则：不能停用或取消自己；不能让系统没有管理员；用户名规则同命令行（`Principal.isUsable`、保留名）。
5. WHEN 管理员重置某人密码或停用某人 THEN 那个人的全部登录 SHALL 立即失效（D3）。
6. 每个人：`PUT /api/auth/password {current, next}` SHALL 校验当前密码，改后别处的登录失效、当前保留（D3）；连续失败受登录限速约束。
7. 以上写操作 SHALL 校验 `Origin`（现在的 `OriginFilter`）；密码不进日志。

### Requirement 3：控制台

#### Acceptance Criteria

1. 设置页“账户” SHALL 有“修改密码”：当前密码、新密码、确认；成功提示；错误按代码翻译。
2. 管理员 SHALL 多一个“用户”页：列表（名字、管理员、停用、创建时间）、新建（名字、初始密码、是否管理员）、停用/启用、设/取消管理员、重置密码；对自己的停用和取消管理员按钮不可用；错误按代码翻译。
3. 非管理员 SHALL 看不到“用户”页。
4. 三种语言。

### Requirement 4：放行名额按用户限

#### Acceptance Criteria

1. `LaneDispatcher` SHALL 为每个用户另记一个名额计数，上限 `tantalum.loop.max-user-permits`（默认 `input-ring-size / 4`）；WHEN 某用户的在途事件达到上限 THEN 他的下一个事件 SHALL 在输入环里等（消费者不取下一个），别的用户的事件照常通过。
2. 上限 SHALL 大于等于 `tantalum.loop.max-user-in-flight`，否则拒绝启动并说明两个配置项。
3. WHEN 一个用户独占了自己的上限 THEN 另一个用户的提交 SHALL 在正常时间内被处理（测试：用户 A 的事件在途数等于上限，用户 B 的提交仍完成）。
4. 内部事件（重启收尾、期限定时器）SHALL 不受用户上限约束，只受总名额约束。

### Requirement 5：载入失败自愈

#### Acceptance Criteria

1. WHEN 会话载入失败 THEN `Residency` SHALL 记下失败时刻；`tantalum.residency.load-retry`（默认 1 分钟）内这个会话的事件和接口照现在拒绝（500 / `session/unavailable`）。
2. WHEN 超过这段时间后有新事件 THEN 通道 SHALL 重新载入；成功就恢复正常，再失败再等一段。
3. 失败 SHALL 记一条错误日志（含原因），同一会话重试再失败只记一条警告，不刷屏。
4. 接口的 500 回复 SHALL 写明“过一会儿再试”，不再说“到重启前”。

### Requirement 6：文档和 8091

#### Acceptance Criteria

1. README“登录和用户” SHALL 改写：登录存在哪、过期规则、管理员、命令行新子命令、网页上怎么管；“运维”加两个配置项；BACKLOG 第 4 节两条缺口标完成。
2. 8091 SHALL 升级后 Yi 的登录不掉；Yi 的账号成为管理员；能在网页上加一个测试用户、改密码、停用。

## Non-Functional Requirements

### Performance
- 每个请求多一次 `logins` 表的查询（SQLite，索引主键），最后访问时间最多每分钟写一次。
- 名额按用户计数是内存里的 Map，不加锁的热路径不变。

### Security
- 令牌 32 字节随机数（`SecureRandom`），只存 SHA-256；Cookie 属性同现在。
- 密码仍是 Argon2id；接口的密码字段不进日志；错误不透露用户名是否存在（登录接口照旧）。
- 管理员接口的 403 和 401 分开；非管理员拿不到用户列表。
- 自己改密码要当前密码，防止被人借用未锁屏的电脑改掉。

### Reliability
- 登录表损坏时拒绝启动并说明（同 `users.db` 现在的规则）。
- 重启收尾、期限定时器等内部事件不会被用户名额卡住。

### Usability
- 三种语言；“用户”页的操作都有确认文字和错误提示。
