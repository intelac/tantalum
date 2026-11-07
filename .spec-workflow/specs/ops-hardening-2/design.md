# Design Document

## Overview

四件事各自独立，共用的只有 `users.db` 和配置。

1. **登录表**：`tantalum.shell.auth.Logins`（`users.db` 里的 `logins` 表）代替 `HttpSession`；`LoginContextRepository` 实现 Spring Security 的 `SecurityContextRepository`，从 Cookie `tantalum.login` 的令牌查表得到用户。
2. **管理员和用户管理**：`users` 表加 `admin` 列；`UserStore` 加列表、启用、设管理员；新 `UsersController`（管理员接口）和 `AuthController` 的改密码；命令行加子命令；控制台“用户”页和“修改密码”。
3. **按用户名额**：`LaneDispatcher` 里每个用户一个计数，达到上限时消费者线程等这个用户的名额。
4. **载入失败自愈**：`Residency` 的 `FAILED` 带时刻，过了 `load-retry` 当作 `COLD`。

## Steering Document Alignment

### Technical Standards (tech.md)

- 凭据不进日志和会话文件（DSH 原则）：令牌只存哈希，密码字段不打印。
- 默认只绑 `127.0.0.1`；Cookie 属性沿用现在的配置项，不加新的安全开关。
- 内核不变；只改 shell 的 `auth`、`web`、`loop`、`boot` 和控制台。
- 索引、用户库都用 SQLite 原生 JDBC（`UserStore` 现有写法），`users.db` 用 `PRAGMA user_version` 升级（和 `SessionIndex` 一样，但用户库不能重建，所以是 `ALTER TABLE`）。

### Project Structure (structure.md)

| 包 | 改动 |
|---|---|
| `shell.auth` | 新 `Logins`、`LoginContextRepository`、`LoginCookie`；`UserStore` 加 `admin`、`list`、`enable`、`setAdmin`、`verifyCurrent`；`LoginCheck` 改按登录行校验；`UserCommand` 加子命令；`AdminBootstrap` 设管理员；`SecurityConfig` 用新仓库，`DisabledUserFilter` 改读登录行 |
| `shell.web` | `AuthController` 登录写表、退出删行、`me` 带 `admin`、`PUT /api/auth/password`；新 `UsersController`；`EventStreamController` 用登录 id 和 `Logins.stillValid`；`ErrorCode` 加 `AUTH_FORBIDDEN`、`USER_*` |
| `shell.loop` | `LaneDispatcher` 每用户名额；`Residency` 失败时刻和重试间隔 |
| `shell.boot` | `TantalumProperties.Loop.maxUserPermits`、`Residency.loadRetry`；`Engine` 传入 |
| `console/` | `settings/UsersPane.tsx`、`settings/PasswordForm.tsx`；`AccountPane` 加改密码；`SettingsDialog` 按 `me.admin` 显示“用户”页；`api/client.ts` 新函数；三种语言 |

## Code Reuse Analysis

- `UserStore` 的 SQLite 连接、`Refused` 异常和 `Principal.isUsable`/`ReservedName` 校验照用。
- `SecurityConfig.OriginFilter`、`LoginThrottle`、`ErrorCode`/`WebJson` 错误格式照用。
- `SseHub.closeLogin(principal, login)` 的 `login` 换成登录 id（表的主键），语义不变。
- `TestServer` 的登录（`set-cookie` 原样带回）不用改：Cookie 名变了但它只是转发。
- `LaneDispatcher.awaitIdle`、`discarded` 和总名额不变。

## Architecture

### 登录

```
POST /api/auth/login ─► 校验 ─► Logins.create(name, stamp) ─► 令牌 ─► Set-Cookie: tantalum.login=<token>; HttpOnly; SameSite=Strict[; Secure]
每个请求 ─► LoginContextRepository.loadContext：Cookie 里的令牌 → sha256 → logins 行
             行不存在 / 过期 / 用户停用 / 口令戳不符 → 清 Cookie，匿名 → /api/** 回 401
             有效 → Authentication(name, ROLE_USER[, ROLE_ADMIN])；最后访问时间距上次写入 ≥ 1 分钟就更新
POST /api/auth/logout ─► Logins.delete(id) ─► closeLogin(principal, id) ─► 清 Cookie
```

`logins` 表：

```sql
CREATE TABLE logins (
  id          TEXT PRIMARY KEY,   -- 令牌的 SHA-256，Base64url
  name        TEXT NOT NULL,
  stamp       INTEGER NOT NULL,   -- 登录时的 password_stamp
  created_at  TEXT NOT NULL,
  last_seen   INTEGER NOT NULL    -- epoch 毫秒
);
CREATE INDEX logins_name ON logins (name);
```

令牌：`SecureRandom` 32 字节 → Base64url（43 字符）。表里存 `sha256(token)`；Cookie 值就是令牌。`id` 作为 `closeLogin` 的登录 id。

有效性（`Logins.check(token, now)` 返回 `Optional<Login>`）：行存在；`now - last_seen ≤ timeout`（`server.servlet.session.timeout`，默认 12 小时）；`users.status(name)` 存在且未停用且 `stamp` 相等。失败的全部一样对待（401 + 清 Cookie），不区分原因。

清理：启动时和之后每小时 `DELETE FROM logins WHERE last_seen < now - timeout`（`Logins.sweep`，由 `TantalumApplication` 的现有定时任务线程或一个虚拟线程做）。

改密码：`UserStore.resetPassword` 口令戳加一 → 所有登录的 `stamp` 不符 → 失效（D3 的“别人改”）。自己改：先 `resetPassword`，再把当前登录行的 `stamp` 更新成新的（`Logins.restamp(id, stamp)`），所以当前保留。停用：`status().disabled()` → 失效；重新启用后要重新登录。

`SecurityConfig`：`securityContextRepository()` 返回 `new LoginContextRepository(logins, users, cookie)`；`DisabledUserFilter` 删掉（校验并进仓库，每个请求本来就要查一次）。`saveContext` 在登录时由 `AuthController` 显式调用（和现在一样），仓库里 `saveContext` 只负责在登录时写 Cookie；之后的请求 `containsContext`/`loadContext` 只读。

`EventStreamController`：从仓库取当前登录 id（`LoginContextRepository.currentLogin(request)`），心跳里 `logins.check(token, now)`，和现在一样每个心跳查一次。

### 管理员

`users` 表加 `admin INTEGER NOT NULL DEFAULT 0`（`PRAGMA user_version` 1 → 2，`ALTER TABLE ADD COLUMN`）。升级时 `UPDATE users SET admin = 1 WHERE name = (SELECT name FROM users ORDER BY created_at, name LIMIT 1)`，且表里没有管理员时才做，记日志。`AdminBootstrap.ensure` 创建的账号 `admin = 1`。

`Authentication` 带 `ROLE_ADMIN` 时才允许 `/api/users/**`：`SecurityConfig` 加 `.requestMatchers("/api/users/**").hasRole("ADMIN")`，被拒时 `accessDeniedHandler` 回 403 `{"error":"forbidden","code":"auth/forbidden"}`。

规则（`UserStore` 里实现，接口和命令行共用）：

- `setAdmin(name, false)`、`disable(name)`：如果这样做之后没有一个“启用的管理员”，抛 `Refused(LAST_ADMIN)`。
- 接口层再加：不能对自己做这两件事（`Refused(SELF)`）——命令行允许（管理员在服务器上有最终控制权）。

### 接口

| 方法 | 路径 | 请求 | 回应 |
|---|---|---|---|
| GET | `/api/auth/me` | | `{username, language, admin}` |
| PUT | `/api/auth/password` | `{current, next}` | 200 `{changed: true}`；当前密码错 401 `auth/invalid-credentials`（走 `LoginThrottle`）；新密码空或超长 400 |
| GET | `/api/users` | | `{users: [{name, admin, disabled, createdAt}]}` |
| POST | `/api/users` | `{name, password, admin}` | 200 `{name}`；名字不合法 400 `user/bad-name`、保留名 400 `user/reserved-name`、已存在 409 `user/exists` |
| PUT | `/api/users/{name}` | `{disabled?, admin?}` | 200 当前状态；不存在 404 `user/not-found`；对自己 400 `user/self`；最后一个管理员 409 `user/last-admin` |
| PUT | `/api/users/{name}/password` | `{password}` | 200；同上的 404/400 |

### 按用户名额

```java
// LaneDispatcher.onEvent
while (!halted) {
    if (userPermits.tryAcquire(principal, POLL)) {        // 这个用户的计数 < maxUserPermits
        if (permits.tryAcquire(POLL)) { offer; return; }
        userPermits.release(principal);
    }
}
```

`UserPermits`：`ConcurrentHashMap<Principal, Semaphore>`，每个用户一个 `Semaphore(maxUserPermits)`；`released()`、`discarded(count)` 时按 envelope 的用户归还（`UserLane.Exit` 的回调带 principal：`released(Principal)`；现在 `released()` 无参，改签名，调用方 `Cleanroom`/`UserLane` 跟着改）。内部事件（`RunInterrupted`、`DeadlineTimers` 的 `TimerFired`）也归某个用户，但数量很少，不单独放行——需求 4.4 的“不受用户上限约束”改成：它们和用户事件一样计数，但不会被卡住，因为重启收尾在开门之前、定时器一个会话一个。这一点写进 tasks 的测试：用户 A 占满自己的名额时，他的会话的 `TimerFired` 要等（符合“最多拖慢自己”）。

配置：`tantalum.loop.max-user-permits`，默认 `inputRingSize / 4`；校验 `maxUserInFlight ≤ maxUserPermits ≤ inputRingSize`。

### 载入失败自愈

`Residency.Entry` 加 `failedAt`（毫秒）；`Residency` 构造带 `Clock` 和 `Duration retry`（默认 1 分钟）。`pin()` 看到 `FAILED` 且 `now - failedAt ≥ retry` 时返回 `COLD`（并把条目改回 `COLD`，失败原因清掉），通道就会重新 `loadOne`；`failure(session)` 同理返回 null。`UserLane.loadOne` 的日志：第一次失败 `error`，同一会话再次失败 `warn`（`Residency.fail` 返回是否第一次）。`Intake` 的 500 回复文字改成 “session cannot be loaded right now; try again in a minute”。

## Components and Interfaces

### `shell.auth.Logins`

```java
public final class Logins {                       // 用 UserStore 的连接
    public record Login(String id, String name, long stamp) {}
    public record Issued(String token, String id) {}
    Issued create(String name, long stamp, long now)
    Optional<Login> check(String token, long now, Duration timeout)   // 不查用户状态，只查行和过期
    void touch(String id, long now)              // 最后访问时间，调用方控制频率
    void delete(String id)
    void deleteAll(String name)                  // 管理员重置密码 / 停用时用（口令戳已让它们失效，这里顺手清掉）
    void restamp(String id, long stamp)          // 自己改密码后当前登录保留
    int sweep(long now, Duration timeout)
    static String hash(String token)
}
```

### `shell.auth.LoginContextRepository`

实现 `SecurityContextRepository`：`loadDeferredContext`/`loadContext` 读 Cookie → `logins.check` → `users.status`（停用、口令戳）→ `Authentication`；每分钟最多 `touch` 一次（记在请求属性里避免重复）。`saveContext`：上下文里有已认证用户且请求属性里有刚发出的令牌 → 写 Cookie；匿名 → 清 Cookie。`currentLogin(HttpServletRequest)` 返回这次请求的登录 id（请求属性）。

### `shell.auth.UserStore` 新增

`record Account(String name, boolean admin, boolean disabled, Instant createdAt)`；`list()`、`add(name, password, admin)`、`enable(name)`、`setAdmin(name, boolean)`、`isAdmin(name)`、`Status` 加 `admin`。`Refused.Kind` 加 `LAST_ADMIN`。

### `shell.web.UsersController`、`AuthController`

见接口表。密码规则同登录接口：非空、≤ 1024。

### `shell.loop.LaneDispatcher`、`UserPermits`、`Residency`

见架构。`UserLane.Exit.released()` 改为 `released(Principal)`。

### 控制台

- `settings/PasswordForm.tsx`：三个输入框 + 按钮，`changePassword({current, next})`，成功后清空并显示“已修改”。
- `settings/UsersPane.tsx`：表格 + 新建表单 + 每行操作（停用/启用、设/取消管理员、重置密码弹一个输入框）。用 `useQuery(["users"])`，操作后 `invalidate`。
- `SettingsDialog`：`PANES` 按 `me.admin` 过滤；`AccountPane` 挂 `PasswordForm`。
- `api/types.ts`：`User.admin`、`Account`；`api/client.ts`：`changePassword`、`listUsers`、`createUser`、`updateUser`、`resetUserPassword`。
- 文案：`settings.users`、`settings.password`、`users.*`（标题、列名、按钮、确认、错误）；`errors.ts` 加 `auth/forbidden`、`user/*`。

## Data Models

见上面的 `logins` 表、`users.admin` 列、接口表。配置：

| 配置项 | 默认 | 说明 |
|---|---|---|
| `tantalum.loop.max-user-permits` | `input-ring-size / 4`（256） | 一个用户同时在途的事件上限 |
| `tantalum.residency.load-retry` | `1m` | 会话载入失败后多久再试 |

## Error Handling

| 情况 | 处理 |
|---|---|
| Cookie 里的令牌无效 | 401 + `Set-Cookie` 清掉；不区分原因 |
| `users.db` 升级到版本 2 时已有 `admin` 列 | `ALTER TABLE` 失败就跳过（幂等） |
| 没有管理员（旧库） | 最早的账号设为管理员，记日志 |
| 管理员停用自己 / 取消自己 | 400 `user/self` |
| 只剩一个启用的管理员要被停用或取消 | 409 `user/last-admin`（命令行同样拒绝） |
| 自己改密码当前密码错 | 401，并计入登录限速 |
| 用户在途事件达到上限 | 他的下一个事件等在输入环；输入环满时入口 429（现有规则） |
| 载入失败 | 1 分钟内 500 `session/unavailable`（文字改成稍后再试）；之后重试 |

## Testing Strategy

### Unit
- `LoginsTest`：创建/查/过期/删除/全删/restamp/sweep；只存哈希。
- `UserStoreTest` 加：`admin` 列升级（版本 1 的库 → 2，最早账号成管理员）、`list`、`enable`、`setAdmin`、最后一个管理员、`add(admin)`。
- `UserCommandTest` 加新子命令。
- `LaneDispatcherTest`（新）或 `UserLaneTest` 加：用户 A 占满上限时 B 照常；A 归还后 A 继续；配置校验。
- `ResidencyTest`（新）：失败后 `retry` 内是 `FAILED`，之后 `COLD`；`UserLaneTest` 加重试成功的用例。
- 控制台 vitest：`PasswordForm`、`UsersPane`（列表、新建、对自己禁用按钮、错误提示）、`SettingsDialog` 按 admin 显示。

### Integration（真实服务）
- `AuthApiTest` 加：登录后重启服务（同数据目录）仍然 200；退出后 401；管理员重置密码后对方 401；自己改密码后别处 401、当前 200；停用后 401；非管理员访问 `/api/users` 403。
- `LoginStreamsTest`：SSE 在退出、改密码后断开（现有用例改用新机制照过）。
- `UsersApiTest`（新）：接口表全部分支。
- `RestartInterruptTest`/`WaitingRestartTest` 照过。
- 名额：`MultiUserTest` 加一个“A 卡住 B 不受影响”的用例（A 的工具挂起，在途数达到上限）。

### End-to-End
- `console/e2e/users.spec.ts`：管理员在“用户”页新建用户、重置密码；用新用户登录；非管理员看不到“用户”页；自己改密码后用新密码登录。
- 现有登录相关 e2e 照过（Cookie 名变了对 Playwright 透明）。

### 8091
- 升级后 Yi 的登录不掉（部署前先在页面上登着）；Yi 成为管理员；加一个测试用户、改密码、停用；记在 tech.md。
