# Tasks Document

工作方式：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志。

每个阶段结束：
- 跑 macOS 全量回归（`scratchpad/regress.sh`：控制台构建、`mvn -o clean verify` 含 `JarCrashIT`、跳号回归、vitest、Playwright）；
- 提交一次，推到 GitHub 的 `windows-server` 分支，等 `Windows` 工作流跑完，记下运行链接和结果（阶段 1 结束时允许失败，但失败只能比任务 2 的基线少，不能多）；
- 有服务器代码改动时重新打包，复制到 `scratchpad/app/tantalum.jar`，跑 `up.sh` 更新 8091；
- 用一两句话向 Yi 汇报，附 Windows 运行链接。

构建：本 spec 不加依赖，本地一律 `mvn -o … -am`；单独跑某个模块的测试也要带 `-am`，否则用到 `~/.m2` 里的旧包。CI 的 runner 没有 `~/.m2`，联网下载。

提交前扫描密钥。搬过来的测试断言不放宽。安全规则（每个任务都适用）：
- 工作流里不用任何 `secrets.`、不用 `pull_request_target`，`permissions` 只有 `contents: read`，检出 `persist-credentials: false`；
- API 密钥不进计划任务、日志、上传的报告；
- macOS、Linux 上的 600/700 不变；Windows 上不写、不放宽 ACL；
- 服务器默认只绑 `127.0.0.1`。

路径前缀：
- `shell/` = `tantalum-shell/src/main/java/tantalum/shell/`
- `shelltest/` = `tantalum-shell/src/test/java/tantalum/shell/`
- `local/` = `tantalum-local/src/main/java/tantalum/local/`
- `localtest/` = `tantalum-local/src/test/java/tantalum/local/`
- `value/` = `tantalum-value/src/main/java/tantalum/value/`
- `valuetest/` = `tantalum-value/src/test/java/tantalum/value/`
- `examples/` = `tantalum-examples/src/test/java/tantalum/examples/`
- `connecttest/` = `tantalum-connect/src/test/java/tantalum/connect/`

每个任务的 `_Prompt` 开头省略的固定部分：“Implement the task for spec windows-server, first run spec-workflow-guide to get the workflow guide then implement the task: 开始时在 tasks.md 把本任务标成 [-]，做完用 log-implementation 记录，再标成 [x]。”

## 阶段 1：基线和 `PrivateFiles`

先让仓库在 Windows 上以 LF 检出，把工作流跑起来，拿到不改代码时的真实失败清单；再做 `PrivateFiles`，把所有 POSIX 权限、目录落盘、替换的代码换成它，加 ArchUnit 规则。这一阶段结束时服务器应该已经能在 Windows 上启动，8091 上看不到变化。

- [x] 1. `.gitattributes`
  - File: .gitattributes（新）
  - 内容照设计“`.gitattributes`”；`git add --renormalize .` 后 `git status` 应该没有内容变化；有变化的文件逐个列进实现日志，确认只是 CRLF 转 LF
  - 测试：`git ls-files --eol` 里没有 `i/crlf` 的文本文件；`mvn -o -q verify` 照过（`WireFormatTest` 的期望文件不变）
  - _Requirements: N9, 需求 4.5_
  - _Prompt: Role: 构建 | Task: 加 .gitattributes，确认仓库里的文本都以 LF 存、以 LF 检出 | Restrictions: 不改任何文件的内容，CRLF 转 LF 除外并逐个列出 | Success: 没有 i/crlf 的文本文件，verify 通过_

- [x] 2. Windows 工作流和基线
  - File: .github/workflows/windows.yml（新）
  - 照设计“`.github/workflows/windows.yml`”写，先不含“Browser tests were not skipped”“Scheduled task”“Console end-to-end”三步（任务 12–14 加）；每个 action 查当前稳定版，固定到完整提交哈希，后面注释版本号
  - 主会话推到 `windows-server` 分支，在**不改产品代码**的情况下跑一次。把失败按类记进实现日志：POSIX 属性的 `UnsupportedOperationException`、目录落盘的 `AccessDeniedException`、`@TempDir` 删不掉、只适用于 macOS 的测试、超时、其他（逐个写测试名和第一行错误）
  - 逐行核对设计“JDK 在 Windows 上的行为”；有不符的，先改设计、告诉 Yi，再做任务 3
  - 测试：`grep -nE "secrets\.|pull_request_target" .github/workflows/windows.yml` 为空；工作流从头跑到尾（结果是红的，预期内），记下运行链接
  - _Leverage: DSH `.github/workflows/ci-windows.yml:9-12,46-48`、pi `.github/workflows/build-binaries.yml:124`_
  - _Requirements: N14, 需求 7.1, 7.2_
  - _Prompt: Role: CI 工程师 | Task: 写 Windows 工作流，拿到不改代码时的失败清单 | Restrictions: 不用任何 secret；不用 pull_request_target；不改产品代码和测试 | Success: 工作流跑完，失败分类和运行链接记进日志，JDK 行为表核对过_

- [x] 3. `PrivateFiles`
  - File: local/fs/PrivateFiles.java（新），localtest/fs/PrivateFilesTest.java（新）
  - 照设计“`tantalum.local.fs.PrivateFiles`”：`posix`（看 `supportedFileAttributeViews` 里有没有 `posix`）、`fileAttributes`、`createFile`、`createDirectories`（只给新建的那几级 700）、`createTempFile`、`restrict`、`copyPermissions`、`replace`、`syncDirectory`；“是否 Windows”是常量；`replace` 的重试照设计“一次替换”1–5，包内重载接受 `Mover`、`windows`、`Sleeper`
  - 测试：设计“`PrivateFilesTest`”各条（POSIX 上的 600/700、已有上级不变、`restrict`、`copyPermissions`；Windows 模式下重试两次后成功、一直失败在 1 秒预算内放弃并抛最后一次的异常、`NoSuchFileException` 不重试；非 Windows 模式不重试；测试不真睡）
  - _Leverage: shell/tool/Workspaces.java:78-84（“不支持就靠默认 ACL”的写法）_
  - _Requirements: N2, N6, N7, 需求 1.3, 4.1, 4.2_
  - _Prompt: Role: Java 开发 | Task: 实现跨平台的私有文件工具类 | Restrictions: POSIX 上发出的系统调用和现在一样；不读写 ACL；只按类判断异常，不看本地化的消息 | Success: PrivateFilesTest 全过_

- [x] 4. 存储和账号改用 `PrivateFiles`
  - File: shell/journal/StoreLock.java、StoreFormat.java、SessionFile.java，shell/store/SessionIndex.java，shell/auth/UserStore.java，shell/access/PluginSwitches.java，shell/local/LocalTokens.java，shell/settings/UserSettings.java（改）
  - 设计“现状清单”里标（4）的每一处：创建带 600/700 改成 `createFile`、`createDirectories`、`createTempFile`、`fileAttributes`；打开时收紧改成 `restrict`；`SessionFile.syncDir` 和 `StoreFormat` 的目录落盘改成 `syncDirectory`；`StoreFormat` 的替换改成 `replace`
  - 测试：`SessionFileTest`、`StoreFormatTest`、`StoreLockTest`、`UserStoreTest`、`PluginSwitchesTest`、`LocalTokensTest`、`IndexStoreTest` 照跑，断言不变；推一次 CI，确认基线里这些类的 UOE 和目录落盘失败没了
  - _Requirements: N2, N6, N7, N8, 需求 1.1, 1.3, 4.2_
  - _Prompt: Role: Java 后端开发 | Task: 把存储、索引、账号库的私有文件代码换成 PrivateFiles | Restrictions: 只换调用，不改行为；不碰 web-tools 正在改的类 | Success: 现有测试全过，CI 上这几类失败消失_

- [x] 5. 附件、记忆、工作目录、连接程序代码改用 `PrivateFiles`，加 ArchUnit 规则
  - File: shell/attachment/AttachmentStore.java、shell/memory/MemoryStore.java、shell/tool/Workspaces.java、local/tool/TextFiles.java、local/sandbox/Mxc.java（改），shelltest/ArchitectureTest.java（改）
  - 设计“现状清单”里标（5）的每一处；`TextFiles.writeAtomically` 用 `copyPermissions` 保留已有文件的权限，替换用 `replace`；`MemoryStore`、`Workspaces` 去掉接 UOE 的写法
  - ArchitectureTest 加规则：除了 `tantalum.local.fs.PrivateFiles`，没有类依赖 `PosixFilePermissions`、`PosixFilePermission`，也没有类调用 `Files.setPosixFilePermissions`、`Files.getPosixFilePermissions`
  - 测试：`AttachmentStoreTest`、`MemoryStoreTest`、`WorkspacesTest`、`TextFilesTest`、`WriteEditToolsTest` 照跑，断言不变；变异：在 `MemoryStore` 里临时直接调 `Files.setPosixFilePermissions`，确认规则抓到后删掉
  - _Requirements: N2, N7, N14, 需求 1.3, 4.1_
  - _Prompt: Role: Java 开发 | Task: 把剩下的权限和替换代码换成 PrivateFiles，并用 ArchUnit 拦住以后绕过它的代码 | Restrictions: 连接程序的行为在 macOS 上不变；规则没有例外 | Success: 测试和 ArchitectureTest 全过，变异被抓到_

## 阶段 2：Windows 上的名字、数据目录和测试

保留名、数据目录检查、浏览器驱动和 `users` 命令的小改动；然后让整个测试套件在 Windows 上能跑（权限断言只在 POSIX 上做、macOS 专用的测试跳过、关掉漏开的文件），再加只在 Windows 上跑的测试。阶段结束时 CI 上 `mvn verify` 全绿。

- [x] 6. `ReservedName` 和三处名字规则
  - File: value/id/ReservedName.java（新）、value/id/Principal.java、shell/auth/UserStore.java、shell/journal/SessionPaths.java、shell/memory/MemoryFormat.java（改），valuetest/id/ReservedNameTest.java（新），shelltest/journal/SessionPathsTest.java、shelltest/auth/UserStoreTest.java、shelltest/memory/MemoryFormatTest.java（改）
  - 照设计“`ReservedName`”“名字规则”；`UserStore.add` 先判断保留名，给出设计里那句消息；`SessionPaths.encode` 的结果是保留名时把第一个字符写成 `%xx`
  - 部署前再查一次 8091：`~/.local/share/tantalum`（`sessions/`、`attachments/`）和 `~/tantalum-workspace`（各用户的 `memory/`）下有没有名字正好是保留名的文件或目录（2026-11-06 写 spec 时查过，没有）；有就停下来问 Yi
  - 测试：设计“`ReservedNameTest`”“`SessionPathsTest`”“`UserStoreTest`、`MemoryFormatTest`”各条
  - _Requirements: N5, 需求 3.1, 3.2, 3.3_
  - _Prompt: Role: Java 开发 | Task: 拒绝或避开 Windows 保留设备名 | Restrictions: 所有平台同一条规则；不是保留名的名字编码结果不变 | Success: 测试全过，8091 检查结果记进日志_

- [x] 7. `DataDirCheck`
  - File: shell/boot/DataDirCheck.java（新）、shell/boot/TantalumApplication.java（改），shelltest/boot/DataDirCheckTest.java（新）
  - 照设计“`DataDirCheck`”：只在 Windows 上查；网络路径拒绝；最近一个已存在的上级 `toRealPath` 后不在用户目录下 WARN；卷不支持 `AclFileAttributeView` WARN；三句话照设计原文，`icacls` 里填真实用户名。`userStore` bean 打开数据目录之前调用，拒绝时抛 `IllegalStateException`；`users`、`check`、`browser` 子命令也调用，拒绝时退出码 1
  - 测试：设计“`DataDirCheckTest`”各条（传入事实的那个重载）；真实路径的情况放在任务 11
  - _Requirements: N3, N4, 需求 2.2, 2.3, 2.4, 2.5_
  - _Prompt: Role: Java 后端开发 | Task: 启动时检查数据目录的位置和卷 | Restrictions: 不读不改 ACL；只拒绝网络路径，其余只 WARN；macOS、Linux 上什么都不做 | Success: 测试全过，macOS 上启动日志没有新行_

- [x] 8. `BrowserDriver` 在 Windows 上的两处
  - File: shell/browser/BrowserDriver.java、shell/browser/BrowserCommand.java（改），shelltest/browser/BrowserDriverTest.java（改）
  - 照设计“`BrowserDriver`、`BrowserCommand`”：解压后改名接 `FileSystemException`，目标完整就用它；`installCommand` 加 `windows` 参数，Windows 上不加 `--with-deps`，`BrowserCommand` 打印那一行说明
  - 测试：目标目录已完整、改名抛 `AccessDeniedException` 时用现成的；目标不完整时抛原来的异常；Windows 模式下命令里没有 `--with-deps`
  - _Requirements: N11, 需求 5.3, 5.4_
  - _Prompt: Role: Java 开发 | Task: 让浏览器驱动的解压和安装命令在 Windows 上正确 | Restrictions: macOS、Linux 上的行为不变 | Success: BrowserDriverTest 全过_

- [x] 9. `users` 命令的输入编码和缺密钥的提示
  - File: shell/boot/TantalumApplication.java（改）
  - 照设计“`TantalumApplication`、`UserCommand`”：`System.console()` 不为空且 `isTerminal()` 时按 `Console.charset()` 读密码，否则 UTF-8；缺密钥的提示在 Windows 上写用户环境变量的设法
  - 测试：这一处没有自动测试（要真实的中文控制台，单元测试只能测到 `InputStreamReader` 本身）；验证放在任务 17 的手工检查第 1 条
  - _Requirements: N12, 需求 1.2_
  - _Prompt: Role: Java 开发 | Task: 按控制台编码读密码，Windows 上给对的设密钥提示 | Restrictions: 管道和重定向时照旧 UTF-8 | Success: UserCommandTest 照过，macOS 上行为不变_

- [x] 10. 测试套件在 Windows 上能跑
  - File: 设计“现状清单 › 测试代码”列出的测试类，加上任务 2 基线里列出的其他失败
  - 检查 600/700 的断言前加 `assumeTrue(PrivateFiles.posix(dir), "Windows 上权限靠继承的 ACL，见 WindowsFilesTest")`；一个测试方法里既有权限断言又有别的断言时，拆成两个，别的断言在 Windows 上照跑
  - `MxcPolicyTest`、`MxcTest` 这类依赖 `/bin/sh`、`/usr/bin/false` 的，用 `@EnabledOnOs(value = OS.MAC, disabledReason = "…")`，原因写明“macOS 连接程序；Windows 连接程序另开 spec”
  - `@TempDir` 删不掉的：找到没关的 SQLite 连接、通道、流，在测试里关掉；漏关的在产品代码里时，修产品代码并记进日志
  - Windows 上超时的：先找原因；只是 runner 慢时，只给那个测试加超时并写明 CI 上量到的时间
  - 测试：CI 上 `mvn verify` 通过，跳过的只有本任务列出的那些，每个都有原因
  - _Requirements: N15, 需求 7.3_
  - _Prompt: Role: Java 测试 | Task: 让全部测试在 Windows 上能跑 | Restrictions: 不放宽断言；不整类跳过平台无关的测试；不靠调大全局超时 | Success: CI 上 mvn verify 全绿，跳过清单记进日志_

- [x] 11. `WindowsFilesTest` 和 `DataDirPathTest`
  - File: shelltest/journal/WindowsFilesTest.java（新）、shelltest/journal/DataDirPathTest.java（新）
  - `WindowsFilesTest` 用 `@EnabledOnOs(OS.WINDOWS)`，照设计“Windows 上才跑的测试”六组：ACL 继承、约 300 字符的长路径、保留名真的落到文件上、替换和占用（NIO 读着、`FileInputStream` 占 300 毫秒、一直占着）、两个进程一份数据（子 JVM 拿着 `store.lock`）、数据目录检查的真实情况（`RUNNER~1` 短名算在用户目录内、`C:\` 下 WARN、UNC 拒绝）
  - `DataDirPathTest` 所有平台都跑：数据目录 `数据 目录`，四种存储打开、写入、重开读回
  - 测试：macOS 上 `WindowsFilesTest` 跳过、`DataDirPathTest` 通过；CI 上两者都通过（任务 12 的脚本确认 `WindowsFilesTest` 没被跳过）
  - _Requirements: N3, N5, N7, N8, 需求 1.5, 2.1, 2.2, 2.4, 3.2, 3.4, 3.5, 4.1, 4.3_
  - _Prompt: Role: Java 测试 | Task: 在真实 Windows 上钉住权限、长路径、保留名、替换和锁的行为 | Restrictions: 不访问网络共享（UNC 只解析路径）；测试结束不留子进程和文件 | Success: CI 上两个测试类全过_

## 阶段 3：浏览器、端到端、后台运行、文档和完成

工作流补上浏览器、端到端和计划任务三步；写计划任务脚本和 README；记下 Windows 上的数字；最后以一次全绿的运行收尾。

- [x] 12. 浏览器测试在 Windows 上跑到
  - File: .github/workflows/windows.yml（改）、.github/scripts/assert-not-skipped.ps1（新）
  - 脚本读 `**/target/surefire-reports/TEST-*.xml` 和 `**/target/failsafe-reports/TEST-*.xml`，对给定的类名：用例数大于 0、没有跳过，否则列出来并退出码 1。工作流加“Browser tests were not skipped”一步，检查 `UserBrowserIT`、`BrowserToolsIT`、`WindowsFilesTest`
  - 修浏览器测试在 Windows 上的失败（例如子进程检查、Chromium 路径）；修法记进日志
  - 测试：故意把一个类名写错，确认脚本失败后改回；CI 上这一步通过
  - _Requirements: N11, N14, 需求 5.1, 5.2_
  - _Prompt: Role: CI 工程师 | Task: 确认浏览器测试和 Windows 专用测试在 CI 上真的跑了 | Restrictions: 不改 web-tools 的断言 | Success: CI 上浏览器 IT 通过且没有跳过_

- [x] 13. 控制台端到端
  - File: .github/workflows/windows.yml（改），按需 console/playwright.config.ts（改）
  - 加“Console end-to-end”一步：`pnpm exec playwright install chromium`、`pnpm e2e`（`E2eServer` 经 Maven 启动，vite 开发服务器）；失败时上传 `console/test-results/`
  - `playwright.config.ts` 的启动命令在 Windows 上要改时，只按平台改命令，不改测试
  - 测试：CI 上这一步通过
  - _Requirements: 需求 7.1_
  - _Prompt: Role: 测试开发 | Task: 在 Windows 上跑控制台端到端 | Restrictions: E2E 不访问外网；不改 e2e 用例的断言 | Success: CI 上 Playwright 全过_

- [x] 14. 计划任务脚本
  - File: docs/windows/tantalum-task.ps1（新）、.github/workflows/windows.yml（改）
  - 照设计“`docs/windows/tantalum-task.ps1`”：`install`、`status`、`uninstall`、`selftest`；`javaw.exe`、`--logging.file.name`、当前用户、登录时触发、不限时、`IgnoreNew`、1 分钟重启最多 10 次；不需要管理员、不存密码、不写密钥。工作流加“Scheduled task”一步跑 `selftest`
  - runner 上 `Interactive` 跑不起来时 `selftest` 改用 `S4U` 并写明原因；两种都不行就去掉这一步，只在任务 17 手工验证，原因记进日志
  - 测试：CI 上 `selftest` 通过：端口 60 秒内返回 200，`Stop-ScheduledTask` 后进程没了，`uninstall` 后任务不在、数据目录还在
  - _Leverage: DSH `apps/desktop/scripts/windows-runner-task.ps1:61-69`_
  - _Requirements: N10, N13, 需求 6.1, 6.2, 6.3, 6.4, 6.5_
  - _Prompt: Role: Windows 运维 | Task: 写登记计划任务的 PowerShell 脚本并在 CI 上自测 | Restrictions: 不要求管理员；不存密码；不删数据目录 | Success: selftest 在 CI 上通过，或写明为什么只能手工验证_

- [x] 15. 说明文档
  - File: README.md、README.en.md、.spec-workflow/steering/tech.md、.spec-workflow/BACKLOG.md（改）
  - README 新的一节“在 Windows 上运行”，内容照需求 8.1、8.2：要装什么、启动和 `users`、默认数据目录、`browser install`（不需要 `--with-deps`，中文字体系统自带）、计划任务脚本、API 密钥放用户环境变量、`.properties` 里路径写 `/` 或 `\\`、防火墙、Ctrl+C 和结束任务的区别、风险（用户目录外的数据目录和 `icacls` 命令、断电可能丢最新建的文件、不支持网络共享、arm64 没测过）。“测试”一节加 Windows 工作流。README.en.md 的 Run 一节加一段
  - tech.md：测试表加 Windows CI；Decision Log 加四条：不写 ACL、不做 Windows 服务、不用 Jimfs、用户目录外只 WARN
  - BACKLOG：“服务器装在 Windows 上”一行写 spec 名和进度；“Windows 连接程序”一行补一句：`TextFiles` 替换后原文件单独设的 ACL 会丢，DSH 用 `ReplaceFileW` 保留（`fs/fs-local/src/win32.ts:103-122`）
  - 确认 THIRD_PARTY_NOTICES 不用改（没有新依赖）
  - _Requirements: 需求 8.1, 8.2, 8.3_
  - _Prompt: Role: 技术写作 | Task: 写 Windows 上的安装、运行和风险说明 | Restrictions: 不写真实的密钥；命令都在 Windows 上能直接粘贴运行 | Success: Yi 照着能在一台新的 Windows 电脑上装好_

- [x] 16. Windows 上的测量
  - File: .spec-workflow/steering/tech.md（改），按需 docs/windows/tantalum-task.ps1（改，让 `selftest` 打印从启动到 200 的秒数）
  - 从 CI 记：有缓存和没缓存时整个工作流的时间、`mvn verify` 的时间、空数据目录从启动到能登录的时间（`selftest`）、`JarCrashIT` 重启后补齐的时间（它的日志）；和 NFR 比（≤ 45 分钟、≤ 20 秒），写进 tech.md，注明 runner 的规格
  - _Requirements: NFR Performance_
  - _Prompt: Role: 测试开发 | Task: 记下 Windows CI 上的时间 | Restrictions: 不和 macOS 的数字直接比 | Success: 数字达标并记进 tech.md；不达标时写明原因和下一步_

- [x] 17. 全部通过和手工检查
  - `windows-latest` 上整个工作流全绿，把运行链接写进本任务的实现日志和 BACKLOG；macOS 全量回归照常通过
  - Yi 有 Windows 电脑时照设计“手工检查”1–5 跑一遍（中文控制台的中文密码、提问附件记忆插件开关重启、`browser install` 和截图、普通用户登记计划任务并注销再登录、`D:\` 下的数据目录和 `icacls`），每一条的结果写进日志，截图给 Yi；没有就写明“只有 CI”，并把任务 9 和任务 14 里只能手工验证的部分列为未验证
  - 主会话把 `windows-server` 合进 `main`，确认 `main` 上的工作流也跑通
  - _Requirements: N16, 需求 7.4, 需求 1.2, 2.2, 5.1, 6.1, 6.4_
  - 结果（2026-11-07）：`windows-latest` 全绿 <https://github.com/intelac/tantalum/actions/runs/42318846814>；只有 CI，没有真实 Windows 电脑，CI 盖不到的几项列在任务 17 的实现日志里
  - _Prompt: Role: 测试开发 | Task: 以一次全绿的 Windows 运行收尾，有条件时在真实 Windows 电脑上手工检查 | Restrictions: 截图和日志里不出现密钥；提问用真实 Claude 一两轮即可，事后报用量 | Success: 运行链接和手工检查结果记进日志和 BACKLOG，Yi 收到截图_
