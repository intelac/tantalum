# Requirements Document

## Introduction

服务器现在装不到 Windows 上（BACKLOG“服务器装在 Windows 上”一行，2026-11-03 Yi 问）。数据目录里的文件和目录创建时就带 POSIX 权限（600/700），Windows 的 NTFS 不支持，JDK 直接抛 `UnsupportedOperationException`。服务启动时第一个打开的就是 `users.db`（`UserStore.java:36`），所以一步都走不下去。pi 在 Windows 上能用，是因为 Node 静默忽略 `mode`（`core/auth-storage.ts:37,71`）。

Yi 定：单独开 spec；要在一台真实的 Windows 机器上跑一遍测试才算完成。顺序在 `web-tools`、`console-i18n` 之后（BACKLOG 第 27、28 行）。

本 spec 让服务器（`tantalum-shell` 和它用到的内核、`tantalum-value`、`tantalum-local` 里的公共代码）在 Windows x64 上照常运行：

- **权限：** 文件系统支持 POSIX 权限时照旧 600/700；不支持时照常创建，靠用户目录的 ACL。不写 ACL。
- **逐项检查：** 路径拼接、Windows 保留名、长路径、大小写、文件锁、SQLite、换行、改名替换、目录落盘、进程、临时目录、控制台构建、可执行 jar、`web-tools` 的浏览器和出口。
- **后台运行：** 一个 PowerShell 脚本登记计划任务，不做 Windows 服务。
- **真实 Windows：** 仓库已经公开在 GitHub（`intelac/tantalum`），加一个在 `windows-latest` 上跑全部测试的工作流，不用任何密钥。

例子：

| 时间 | 发生了什么 | 结果 |
|---|---|---|
| 10:00 | Yi 在一台 Windows 11 电脑上装好 JDK 25，在 PowerShell 里运行 `java -jar tantalum.jar users add yi` | 数据目录建在 `C:\Users\yi\.local\share\tantalum`，文件继承用户目录的 ACL：只有 yi、SYSTEM 和管理员能读 |
| 10:02 | `java -jar tantalum.jar` 启动，浏览器打开 `http://127.0.0.1:8080` | 登录、提问、附件、记忆、插件页和 macOS 上一样 |
| 10:05 | `java -jar tantalum.jar browser install` | 驱动解压出 `node.exe`，Chromium 装进 `%LOCALAPPDATA%\ms-playwright`，浏览器工具能截图 |
| 10:10 | `.\tantalum-task.ps1 install -Jar C:\tantalum\tantalum.jar` | 登记一个计划任务：Yi 登录 Windows 后自动启动，异常退出 1 分钟后重启 |
| 10:30 | 在任务计划程序里结束这个任务 | 进程被结束；下次启动按崩溃收尾，已经“已接受”的提问不丢 |
| 11:00 | 管理员把数据目录改到 `D:\tantalum` | 照常启动，日志有一行 WARN：目录不在用户目录下，别的本机用户可能读得到，附 `icacls` 命令 |
| 11:05 | 有人想把数据目录放到 `\\nas\share\tantalum` | 拒绝启动：SQLite 的 WAL 不能用在网络共享上 |

### 调研

参照 pi-mono `main`（7f47a2e，2026-11-05）和 DSH `main`（fcc0ad4621，2026-11-04）。路径写法：pi 的 `CA` 指 `packages/coding-agent`；DSH 路径相对于 `packages/`；以 `.github/`、`apps/`、`.gitattributes` 开头的相对于各自仓库根目录；两边都没有对应做法的标“自定”。

- **pi**（单用户命令行，正式支持 Windows）：
  - 权限：`mode: 0o600` 只在创建时给，注释写明“保留管理员设的 mode 和 ACL”（`CA/src/core/auth-storage.ts:36-37`），目录 `mode: 0o700`（`:71`）；Windows 上 Node 忽略它们，靠继承的 ACL。
  - 锁：Windows 上刚删掉的锁目录可能处于 “delete pending”，`mkdir` 报 `EPERM`，短暂重试 5 次（`auth-storage.ts:19-29`）。
  - 数据目录：所有平台都是 `~/.pi/agent`（`CA/src/config.ts:630-636`）。
  - 路径：Windows 上比较路径前先转小写（`CA/src/utils/paths.ts:42-50`）；会话目录名把 `/`、`\`、`:` 换成 `-` 并包上 `--`（`CA/src/core/session-manager.ts:590-595`），所以不会撞上保留名，但没有专门处理保留名。
  - 命令行：找 Git Bash（`CA/src/utils/shell.ts:95-126`）、PowerShell（`:145-170`），用 `taskkill /F /T` 结束进程树（`:221-237`）。这些是在用户电脑上执行命令用的，归 Windows 连接程序那个 spec。
  - 换行：`.gitattributes` 默认 LF，`.bat`、`.cmd`、`.ps1` 用 CRLF（`.gitattributes:1-9`）。
  - CI：测试只在 `ubuntu-latest` 上跑（`.github/workflows/ci.yml:58`）；`windows-latest` 只冒烟测打好的程序（`--help`、`--version`，`.github/workflows/build-binaries.yml:131-140,203-208`）；action 固定到提交哈希（`build-binaries.yml:124`）。
- **DSH**（单用户桌面和命令行，正式支持 Windows）：
  - 改名替换：`rename` 在 Windows 上就是 `MoveFileExW(…, MOVEFILE_REPLACE_EXISTING)`，原子替换；Windows 上跳过目录落盘，因为 Windows 不允许只读打开目录（`storage/storage-json/src/atomic.ts:4-10,42-56`）。
  - 会话日志：新文件用 `MoveFileExW(…, MOVEFILE_WRITE_THROUGH)` 发布，代替 POSIX 的目录落盘（`session/session-persistence-jsonl/src/win32.ts:1-9`），要经 koffi 调 Win32。
  - 写锁：Node 没有 `flock`，Windows 上用命名信号量（`session/session-persistence-jsonl/src/lease.ts:73-85`、`win32.ts:151-161`）。
  - 权限：凭据文件“只有本人可读”的检查只在 POSIX 上做，Windows 上跳过，“文件的保护就是创建和替换接口给的那样”（`credentials/credentials-local/src/index.ts:121-123,137`）。替换用户自己的文件时把原文件的 DACL 复制过去（`fs/fs-local/src/win32.ts:103-122`），这是用户文件，不是数据目录。
  - 路径包含判断照顾 8.3 短名和大小写（`fs/fs-sandbox/src/containment.ts:1-5,45-53`）；数据目录所有平台都是 `~/.dsh`（`util/home-paths/src/index.ts:71`）。
  - 沙箱：子进程用受限令牌加 ACL 限制写入（`sandbox/sandbox-windows-acl/README.md:12`）。我们的服务器不执行命令，用不上。
  - 后台运行：用计划任务，以当前用户、登录时启动、不限运行时间、`IgnoreNew`（`apps/desktop/scripts/windows-runner-task.ps1:61-69`）；另有一个不弹窗口的启动器（`.github/workflows/ci-windows.yml:91-93`）。
  - CI：每个 PR 在 Windows 上跑构建和测试，`permissions: contents: read`，`persist-credentials: false`（`.github/workflows/ci-windows.yml:9-12,46-48`）。
  - 换行：`* text=auto eol=lf`，只有 `.cmd` 用 CRLF（`.gitattributes:1-7`）。
  - 浏览器和抓网页的包里没有 Windows 专门的代码（`experimental/browser-use-playwright-mcp/`、`web/web-fetch-http/` 里查不到 `win32`）。
- **两边都没有：** Windows 保留设备名、网络共享上的数据目录、数据目录不在用户目录下的提示。这三项是自定。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| 服务器在 Windows 10/11、Windows Server 2022/2025 x64 上运行 | Windows 连接程序：`powershell`、MXC 的 Windows 后端、安装包（BACKLOG 第 29 行，下一个 spec） |
| 数据目录的文件创建、替换、落盘、锁在 Windows 上可用 | 主动给数据目录写只有本人的 ACL（pi、DSH 都不写；没有 Windows 机器无法验证，见 N3） |
| `tantalum-local` 里服务器和连接程序共用的文件代码改成跨平台，让它的测试在 Windows 上能跑 | Windows 服务（要第三方包装程序或自己写服务代码；计划任务够用，DSH 也这样，见 N13） |
| 浏览器工具、`web_fetch` 在 Windows x64 上可用 | Windows arm64（Playwright 的 `driver-bundle` 只有 `win32_x64/node.exe`，没测过就不保证） |
| 计划任务脚本 | 安装程序、MSI |
| GitHub Actions 的 `windows-latest` 工作流，不用密钥 | `MOVEFILE_WRITE_THROUGH`（要经 FFM 调 Win32；DSH 做了，断电丢最新文件的风险写进 README，见 N6） |
| README 的 Windows 一节 | 数据目录放在网络共享上（SQLite 的 WAL 不支持，拒绝启动）；把 macOS 上的数据目录搬到 Windows |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 |
|---|---|---|
| N1 | 支持哪些 Windows | Windows 10 22H2、Windows 11、Windows Server 2022/2025，x64，JDK 25，本地 NTFS 或 ReFS。CI 用 `windows-latest`（Windows Server 2025）。arm64 不保证（见范围）。自定 |
| N2 | 权限怎么办 | 新类 `PrivateFiles` 统一创建私有文件和目录：文件系统支持 POSIX 权限（`posix` 属性视图）时照旧带 600/700，已有文件照旧收紧；不支持时照常创建，不带属性，新文件继承所在目录的 ACL。不写 ACL。照 pi（`CA/src/core/auth-storage.ts:36-37,71`，Node 在 Windows 上忽略 `mode`）和 DSH（`credentials/credentials-local/src/index.ts:121-123,137`，Windows 上不检查也不模拟） |
| N3 | 怎么知道数据目录安全 | 默认数据目录在用户目录下，Windows 给用户目录的 ACL 只有本人、SYSTEM 和管理员，新文件都继承它。数据目录不在用户目录下时（比如 `D:\tantalum`，非系统盘根目录默认给“已验证的用户”修改权），启动时 WARN 一行，附用 SID 写法的 `icacls` 命令（不受系统语言影响）；卷不支持 ACL（FAT32、exFAT）也 WARN。只提示不拒绝：管理员可能有意给备份账号读权限。按用户目录判断而不读 ACL，因为 JDK 给的 ACL 条目只有本地化的账号名、拿不到 SID，中文、德文系统上的组名都不一样。自定（DSH 在 POSIX 上拒绝，Windows 上跳过） |
| N4 | 数据目录放哪 | 默认不变：`${user.home}/.local/share/tantalum`，Windows 上是 `C:\Users\<用户>\.local\share\tantalum`（JDK 认 `/` 也认 `\`）。pi 用 `~/.pi/agent`（`CA/src/config.ts:630-636`），DSH 用 `~/.dsh`（`util/home-paths/src/index.ts:71`），都是所有平台同一个位置。数据目录是 UNC 网络路径（`\\server\share`）时拒绝启动：SQLite 的 WAL 不能用在网络文件系统上。自定 |
| N5 | 文件名和路径 | (1) 路径一律用 `Path.resolve` 拼，现有代码已经如此，`"/"` 只出现在日志和内存里的键（清单见设计）。(2) Windows 保留设备名（`con`、`prn`、`aux`、`nul`、`com0`–`com9`、`lpt0`–`lpt9`，带不带扩展名都算）：注册用户名、记忆名拒绝；会话 ID 在文件名里编码成别的名字。所有平台同一条规则，数据目录换平台也一样。pi 靠包上 `--` 避开（`session-manager.ts:590-595`），DSH 查不到，自定。(3) 大小写：现有编码只保留小写字母，大写写成 `%xx`（`SessionPaths.java:16-19`），NTFS 上不撞名，不改。(4) 长路径：JDK 对超过 247 字符的路径自动加 `\\?\` 前缀，不改代码，在 Windows 上测超过 260 字符的会话文件 |
| N6 | 目录落盘 | Windows 上跳过（打开目录本身就报 `AccessDeniedException`）。照 DSH `storage/storage-json/src/atomic.ts:42-56`。NTFS 的元数据有日志，进程崩溃不受影响；断电可能丢掉刚建的那个会话文件或格式文件的目录项。DSH 的会话日志用 `MOVEFILE_WRITE_THROUGH` 补这一点（`win32.ts:1-9`），我们不做（范围表），README 写明 |
| N7 | 改名替换 | 照旧 `ATOMIC_MOVE`：JDK 在 Windows 上用 `MoveFileEx(MOVEFILE_REPLACE_EXISTING)`，和 DSH 依赖的语义一样（`atomic.ts:4-10`）。Windows 上目标被别的句柄（杀毒软件、索引服务）短暂占用时报 `AccessDeniedException` 或共享冲突：重试，总共最多 1 秒，还不行就报原来的错。照 pi 对 `EPERM` 的短暂重试（`auth-storage.ts:19-29`）。POSIX 上不重试 |
| N8 | 文件锁和 SQLite | 存储锁照旧 `FileChannel.tryLock`：JDK 在 Windows 上用 `LockFileEx`，进程退出时系统释放。DSH 用命名信号量（`lease.ts:73-85`）只是因为 Node 没有文件锁。SQLite 用 `sqlite-jdbc` 自带的 Windows x86_64 原生库（`~/.m2` 里的 3.54.1.0 已经带着），不改；在含空格和中文的路径下测一次 |
| N9 | 换行 | 加 `.gitattributes`：`* text=auto eol=lf`，`.cmd`、`.bat` 用 CRLF，图片等标成二进制。照 pi（`.gitattributes:1-9`）、DSH（`.gitattributes:1-7`）。不加的话 Windows 上的 Git 默认把文本检出成 CRLF，`WireFormatTest` 这类逐字节比较的期望文件全会失败。服务器写的文件一律 `\n`；读用户写的文本已经认 CRLF（`MemoryFormat.java:77`、`String.lines()`），不改 |
| N10 | 进程和关闭 | 服务器只启动 Playwright 的 `node.exe` 驱动和 Chromium，不执行命令。Windows 没有 `SIGTERM`：控制台里 Ctrl+C 走 JVM 关闭钩子，和现在一样优雅关闭；结束计划任务、任务管理器结束进程都是直接结束，等于崩溃，靠现有的崩溃恢复（`JarCrashIT` 在 Windows 上要通过）。自定 |
| N11 | 浏览器和出口 | 不改设计：`driver-bundle` 带 `win32_x64/node.exe`，`BrowserDriver` 已经按系统选 `node.exe`（`BrowserDriver.java:140`）；Chromium 装在 `%LOCALAPPDATA%\ms-playwright`；Chromium 沙箱在普通用户下照开；出口只用 JDK 套接字，和平台无关。只改两处：并发解压时 Windows 上目标目录已存在报的是 `AccessDeniedException` 而不是 `FileAlreadyExistsException`；`--with-deps` 只对 Linux 有用，Windows 上提示后忽略。DSH 的浏览器包没有 Windows 专门代码 |
| N12 | 控制台编码 | `users` 子命令读密码时按控制台的编码解码（中文 Windows 的控制台是 GBK），否则含中文的密码和浏览器登录时的 UTF-8 对不上。其余文件读写已经写明 UTF-8。自定 |
| N13 | 后台运行 | 一个 PowerShell 脚本 `docs/windows/tantalum-task.ps1`，`install`、`status`、`uninstall`：以当前用户登记计划任务，登录时启动，不限运行时间，已在运行就不再起，异常退出 1 分钟后重启、最多 10 次；用 `javaw.exe`，不弹控制台窗口；日志写到数据目录的 `logs/`。不存密码，不需要管理员权限。照 DSH `apps/desktop/scripts/windows-runner-task.ps1:61-69`。不做 Windows 服务：要第三方包装程序或自己写服务代码，而且服务账号的用户目录不是管理员的，N3 的前提就不成立 |
| N14 | 怎么在 Windows 上测 | 新工作流 `.github/workflows/windows.yml`，`windows-latest`：控制台构建和 vitest、`mvn verify`（含 IT、`JarCrashIT` 这些启动可执行 jar 的测试）、`browser install` 后的浏览器测试、Playwright 端到端。不用任何密钥：`permissions: contents: read`，`persist-credentials: false`，只用 `push`、`pull_request`、`workflow_dispatch`，不用 `pull_request_target`；action 固定到提交哈希。照 DSH `.github/workflows/ci-windows.yml:9-12,46-48` 和 pi `build-binaries.yml:124`。CI 联网下载 Maven 依赖（runner 没有 `~/.m2`），不用 `-o`。不引入 Jimfs：Windows 上真正出问题的是 JDK 的 Windows 实现（属性不支持、目录打不开、共享冲突、保留名），Jimfs 一样都不模拟；改用 ArchUnit 规则在 macOS 上就拦住新写的 POSIX 权限代码 |
| N15 | 只适用于 macOS 的测试 | MXC、`/bin/sh` 这类测 macOS 连接程序的测试，在 Windows 上跳过并写明原因（同 `MxcIT` 的做法）；检查 600/700 的断言只在 POSIX 文件系统上做，Windows 上的 ACL 由一个只在 Windows 上跑的测试检查。断言不放宽；Windows 上 `@TempDir` 删不掉，说明测试没关文件，要改测试关掉它 |
| N16 | 什么算完成 | `windows-latest` 上整个工作流通过，记下运行链接；macOS 上的全量回归照常通过；Yi 有 Windows 电脑时再手工跑一遍可执行 jar（见 tasks 最后一个任务） |
| N17 | 新的依赖 | 不加任何第三方依赖。Windows 专门的行为都用 JDK 自带的接口 |

## Requirements

### Requirement 1：在 Windows 上启动和使用

**User Story:** 作为在 Windows 电脑上自托管的管理员，我想用 `java -jar` 直接启动 Tantalum，这样不用另找一台 Mac 或 Linux。

#### Acceptance Criteria

1. WHEN 在 Windows 的 NTFS 上第一次启动、数据目录还不存在 THEN 系统 SHALL 建好数据目录和其中所有文件，SHALL NOT 因为 POSIX 权限抛异常（N2）。
2. WHEN 在 Windows 上运行 THEN 登录、提问、工具调用、附件上传和下载、记忆、插件开关，以及 `users`、`check`、`browser install` 子命令 SHALL 和 macOS 上行为相同。
3. WHEN 文件系统支持 POSIX 权限（macOS、Linux） THEN 新文件 SHALL 仍是 600、新目录 700，`users.db`、`store.lock`、会话文件这些已有文件 SHALL 照旧收紧（N2）。
4. WHEN 没有配置数据目录 THEN Windows 上 SHALL 用 `%USERPROFILE%\.local\share\tantalum`（N4）。
5. WHEN 数据目录的路径含空格和中文（如 `C:\Users\张 三\…`） THEN 账号库、索引、会话文件 SHALL 正常读写（N8）。

### Requirement 2：数据目录的访问范围

**User Story:** 作为管理员，我想知道数据目录是不是只有我能读，因为里面有所有用户的提问、工具结果和密码哈希。

#### Acceptance Criteria

1. WHEN 数据目录在当前用户的用户目录下 THEN 系统 SHALL NOT 改 ACL，新文件 SHALL 继承用户目录的 ACL；`Everyone`、`Users`、`Authenticated Users` SHALL NOT 有读权限（N2、N3）。
2. WHEN 数据目录不在当前用户的用户目录下 THEN 系统 SHALL 在启动时记一行 WARN，写明别的本机用户可能读得到，附 `icacls` 命令，然后照常启动（N3）。
3. WHEN 数据目录所在的卷不支持 ACL（FAT32、exFAT） THEN 系统 SHALL 记一行 WARN（N3）。
4. WHEN 数据目录是 UNC 网络路径 THEN 系统 SHALL 拒绝启动，写明“SQLite 不能用在网络共享上，请用本地磁盘”（N4）。
5. 子命令 `users`、`check`、`browser install` SHALL 做同样的检查。

### Requirement 3：文件名和路径

**User Story:** 作为用户，我想让任何合法的用户名、会话、记忆名在 Windows 上都存得进去、读得回来，不会悄悄写进设备或撞名。

#### Acceptance Criteria

1. WHEN 注册的用户名是 Windows 保留设备名（N5 列出的那些） THEN 系统 SHALL 拒绝并写明原因；所有平台都一样。
2. WHEN 会话 ID 是保留设备名（如 `nul`） THEN 会话文件名 SHALL 编码成别的名字；写入、重启后读回 SHALL 正确，SHALL NOT 和其他会话撞名（N5）。
3. WHEN 模型用保留设备名作记忆名 THEN 系统 SHALL 拒绝并写明原因（N5）。
4. WHEN 数据目录里一个文件的完整路径超过 260 字符 THEN 会话文件、附件、记忆的读写和替换 SHALL 正常（N5）。
5. WHEN 两个用户名只差大小写，或数据目录写成不同的大小写、8.3 短名 THEN 系统 SHALL NOT 撞名，路径检查 SHALL 照常通过（N5）。

### Requirement 4：写文件可靠

**User Story:** 作为管理员，我想让 Windows 上的存储和 macOS 上一样可靠：崩溃不丢已接受的提问，两个进程不会同时用一份数据。

#### Acceptance Criteria

1. WHEN 替换一个文件（记忆、附件、格式文件） AND 目标被别的句柄短暂占用 THEN 系统 SHALL 在 1 秒内重试；仍然失败 SHALL 报原来的错，SHALL NOT 留下临时文件（N7）。
2. WHEN 在 Windows 上新建会话文件或格式文件 THEN 系统 SHALL 跳过目录落盘，SHALL NOT 报错（N6）。
3. WHEN 第二个进程打开同一个数据目录 THEN 它 SHALL 拒绝启动；第一个进程被强制结束后 SHALL 能打开（N8）。
4. WHEN 服务进程在模型调用或工具调用中被强制结束 THEN 重启后 SHALL 和 macOS 上一样：已提交的不丢、索引补齐、运行收尾、模型和工具不被再次调用（`JarCrashIT`，N10）。
5. 仓库里的文本文件 SHALL 在所有平台上以 LF 检出；逐字节比较的期望文件在 Windows 上 SHALL 照样通过（N9）。

### Requirement 5：浏览器和联网

**User Story:** 作为 Windows 上的管理员，我想让 `web-tools` 的浏览器和抓网页也能用。

#### Acceptance Criteria

1. WHEN 在 Windows x64 上运行 `browser install` THEN 系统 SHALL 解压出 `node.exe`，装好 Chromium headless shell（N11）。
2. 浏览器工具和 `web_fetch` 在 Windows 上 SHALL 通过 `UserBrowserIT`、`BrowserToolsIT`、`FetcherIT`、`EgressTest`，且浏览器测试 SHALL NOT 因“没装浏览器”被跳过（web-tools 需求 2、4 不变）。
3. WHEN 在 Windows 上带 `--with-deps` THEN 系统 SHALL 说明这个参数只用于 Linux 并忽略它（N11）。
4. WHEN 两个进程同时解压同一个版本的驱动 THEN 后完成的那个 SHALL 用先完成的结果，SHALL NOT 报错（N11）。

### Requirement 6：后台运行

**User Story:** 作为 Windows 上的管理员，我想让 Tantalum 登录后自动在后台运行，崩溃了自己重启。

#### Acceptance Criteria

1. WHEN 运行 `tantalum-task.ps1 install` THEN 系统 SHALL 以当前用户登记一个计划任务：登录时启动、不限运行时间、已在运行就不再起、异常退出 1 分钟后重启、最多 10 次，不弹控制台窗口（N13）。
2. `status` SHALL 显示任务状态、端口和日志位置；`uninstall` SHALL 删除任务，SHALL NOT 删除数据目录。
3. WHEN 结束这个任务 THEN 服务进程 SHALL 被结束，下次启动按崩溃收尾（N10）。
4. 脚本 SHALL NOT 要求管理员权限，SHALL NOT 保存密码；API 密钥从用户的环境变量读，不写进任务。
5. 服务的日志 SHALL 写到数据目录的 `logs/` 下（计划任务没有控制台）。

### Requirement 7：在真实的 Windows 上跑测试

**User Story:** 作为 Yi，我想每次推代码都在真实的 Windows 上跑一遍全部测试，这样 Windows 不会被改坏。

#### Acceptance Criteria

1. 仓库 SHALL 有一个 GitHub Actions 工作流，在 `windows-latest` 上跑：控制台构建和 vitest、`mvn verify`（含集成测试和启动可执行 jar 的测试）、装好浏览器后的浏览器测试、Playwright 端到端（N14）。
2. 工作流 SHALL NOT 使用任何 secret；仓库权限 SHALL 只读；SHALL NOT 用 `pull_request_target`；第三方 action SHALL 固定到提交哈希（N14）。
3. 只对 macOS 有意义的测试 SHALL 在 Windows 上跳过并写明原因；SHALL NOT 放宽断言（N15）。
4. 本 spec SHALL 以一次全部通过的运行为完成标准，链接记进 tasks.md 和 BACKLOG（N16）。

### Requirement 8：说明文档

**User Story:** 作为管理员，我想照着 README 在一台新的 Windows 电脑上装好 Tantalum。

#### Acceptance Criteria

1. README SHALL 有“在 Windows 上运行”一节：要装什么（JDK 25；从源码构建时要 Maven、Node、pnpm）、启动和 `users` 命令、默认数据目录、`browser install`、计划任务脚本、API 密钥放在用户环境变量里、配置文件里路径的写法（`.properties` 里 `\` 是转义符，要写 `/` 或 `\\`）、绑定 `0.0.0.0` 时 Windows 防火墙会询问。
2. README SHALL 写明风险：数据目录不在用户目录下时别的本机用户可能读得到（附 `icacls` 命令）；结束任务等于崩溃；断电可能丢最新建的文件；不支持网络共享；arm64 没测过。
3. tech.md、BACKLOG SHALL 更新：Windows CI、决定记录、完成状态。

## Non-Functional Requirements

### Performance

- macOS、Linux 上不多做任何 I/O：`PrivateFiles` 在 POSIX 上发出的系统调用和现在一样。
- `windows-latest` 上整个工作流（有缓存时）≤ 45 分钟，才能每次推代码都跑。
- `windows-latest` 上空数据目录从启动到能登录 ≤ 20 秒。实际数字记进 tech.md；runner 和 Yi 的 Mac 硬件不同，不和 macOS 比。

### Security

- 工作流不用任何密钥，不能写仓库；上传的失败报告里只有测试日志（测试本来就不用密钥）。
- macOS、Linux 上的权限不变；Windows 上不放宽 ACL，只继承。
- 不新增第三方依赖。
- 计划任务里不写密码和 API 密钥。

### Reliability

- 替换重试有上限（1 秒），不会卡住提交。
- Windows 上的崩溃恢复和 macOS 一样（`JarCrashIT`）。

### Usability

- 拒绝启动和 WARN 都写明原因和下一步（用本地磁盘；`icacls` 命令）。
