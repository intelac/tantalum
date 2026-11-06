# Tasks Document

工作方式：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志。

每个阶段结束：
- 跑全量回归（`scratchpad/regress.sh`：控制台构建、`mvn -o clean verify` 含 `JarCrashIT`、vitest、Playwright）；
- 提交一次；
- 重新打包，复制到 `scratchpad/app/tantalum.jar`，跑 `up.sh` 更新 8091；
- 用一两句话向 Yi 汇报。

提交前扫描密钥。搬过来的测试断言不放宽。

路径前缀：
- `local/` = `tantalum-local/src/main/java/tantalum/local/`
- `connect/` = `tantalum-connect/src/main/java/tantalum/connect/`
- `shell/` = `tantalum-shell/src/main/java/tantalum/shell/`
- `value/` = `tantalum-value/src/main/java/tantalum/value/`
- `kernel/` = `tantalum-kernel/src/main/java/tantalum/kernel/`
- `console/` = `console/src/`

每个任务的 `_Prompt` 开头省略的固定部分：“Implement the task for spec local-workspace, first run spec-workflow-guide to get the workflow guide then implement the task: 开始时在 tasks.md 把本任务标成 [-]，做完用 log-implementation 记录，再标成 [x]。”

## 阶段 1：共用模块，工具在电脑这边能执行（还不接服务器）

- [x] 1. 新模块 `tantalum-local`
  - File: pom.xml、tantalum-local/pom.xml（新）、tantalum-shell/src/test/java/tantalum/shell/ArchitectureTest.java
  - 模块只依赖 `tantalum-value` 和 Jackson。ArchitectureTest 加规则：`tantalum.local` 不引用 `tantalum.shell`、`tantalum.kernel`、`org.springframework`
  - 测试：规则生效（临时加一个违规引用时失败，确认后删掉）
  - _Requirements: 设计“Project Structure”_
  - _Prompt: Role: Java 构建 | Task: 建 tantalum-local 模块并加依赖规则 | Restrictions: 不加新的第三方依赖 | Success: mvn -o -q verify 通过_

- [x] 2. `AllowedDirs`
  - File: local/fs/AllowedDirs.java（新），测试从 `WorkspaceTest` 改写
  - 规则逐条照 `plugin/Workspace`：相对路径按主目录、绝对路径须落在某个允许目录、符号链接解析后再查、写入时查最近存在的上级目录、越界和不存在同一句话
  - 测试：多个目录；`..`；指向外面的符号链接；不存在的上级目录；两个目录互相嵌套
  - _Leverage: shell/plugin/Workspace.java_
  - _Requirements: L12, 需求 2.2_
  - _Prompt: Role: Java 开发 | Task: 实现多目录的路径检查 | Restrictions: 报错措辞不泄露目录外文件是否存在 | Success: 测试全过_

- [x] 3. 六个文件工具搬到 `tantalum-local`
  - File: local/tool/LocalTool.java、LocalContext.java、LocalResult.java（新）；`ReadFile`、`ListDir`、`FindFiles`、`Grep`、`WriteFile`、`EditFile`，以及 `TextFiles`、`ToolArguments`、`Observations`、`WritePaths`（从 `shell/tool/` 搬来）
  - 一个工作目录换成 `AllowedDirs`；读到图片时返回原始字节（`LocalImage`），不再碰附件目录；说明文字改成“用户电脑上允许的目录”，去掉附件那句。`kind()` 标 `READ`、`WRITE`
  - 测试：现有测试跟着搬，断言不变；加多目录的用例
  - _Leverage: shell/tool/*Tool.java 及其测试_
  - _Requirements: L5, L12, 需求 3.1_
  - _Prompt: Role: Java 开发 | Task: 把文件工具搬进共用模块 | Restrictions: 参数、截断、先读后写、CRLF/BOM 行为不变；这一步不删 shell 里的旧类（任务 13 删） | Success: 搬过来的测试全过_

- [x] 4. Git 查询工具
  - File: local/tool/GitLog.java、GitShow.java、GitDiff.java、GitBlame.java、GitBranches.java、local/tool/Git.java（新）
  - 调本机 `git`，参数固定不经过 shell，`--no-pager`、`GIT_TERMINAL_PROMPT=0`、`GIT_OPTIONAL_LOCKS=0`；只接受路径、提交号、行范围、条数字段；输出最多 2000 行或 50 KB 并写明截断；`kind()` 为 `GIT`
  - 测试：临时仓库里造提交，各工具输出；以 `-` 开头的提交号被拒（防止当成选项）；执行前后 `.git` 目录不变；不是仓库时报错
  - _Requirements: L5, 需求 3.2_
  - _Prompt: Role: Java 开发 | Task: 实现五个只读 Git 工具 | Restrictions: 不能改仓库；不接受任意参数 | Success: 测试全过_

- [x] 5. 消息格式
  - File: local/wire/*.java（新）
  - `hello`、`welcome`、`call`、`result`、`cancel`、`context`、`contextResult`、`bye` 八种消息和 `Wire.encode/decode`；`protocol = 1`；关闭码常量
  - 测试：每种往返相等；未知字段忽略；未知 `type` 报错；图片 base64 往返
  - _Requirements: 设计“消息格式”_
  - _Prompt: Role: Java 开发 | Task: 定义连接用的消息和编解码 | Restrictions: 不放令牌字段 | Success: 测试全过_

- [x] 6. 工具清单
  - File: local/tool/Tools.java（新）
  - `all()` 返回全部工具（六个文件、五个 Git，`bash` 在任务 21 加），名字唯一、顺序固定
  - 测试：名字和 core-tools 的旧名字一致；每个工具的参数是合法的 JSON Schema
  - _Requirements: L5, L9_
  - _Prompt: Role: Java 开发 | Task: 提供固定的工具清单 | Success: 测试全过_

## 阶段 2：连接

- [x] 7. 令牌
  - File: shell/local/LocalTokens.java（新）
  - `users.db` 加 `local_tokens` 表；`issue(principal, validity)` 只认 30 分钟、1 小时、4 小时、1 天；只存 SHA-256；`verify`、`revokeAll`；启动时删掉到期超过 7 天的行
  - 测试：四种有效期之外的拒绝；数据库里查不到原始令牌；到期、撤销后 `verify` 失败；时钟用可调的
  - _Leverage: access/PluginSwitches 的 users.db 用法_
  - _Requirements: L2, L3, 需求 1.1, 1.7_
  - _Prompt: Role: Java 后端开发 | Task: 实现有期限、可撤销的令牌 | Restrictions: 令牌不进日志 | Success: 测试全过_

- [x] 8. 服务器端连接
  - File: shell/local/LocalEndpoint.java、LocalLinks.java（新）、shell/web/SecurityConfig.java、shell/boot/Engine.java
  - Jakarta WebSocket 端点 `/api/local/connect`，握手读 `Authorization`；第一条必须是 `hello`（5 秒）；`LocalLinks` 每用户一条，替换、到期、撤销时发 `bye` 并关；15 秒 ping、45 秒没 pong 就关；`call` 等 `result`，断开时在跑的调用记“结果不确定”；每条连接 4 个名额
  - 测试：用 JDK 的 WebSocket 客户端连真服务器：错令牌 4401、到期 4403、第二条连接让第一条收到 4409；到期时刻断开；断开时在跑的 `call` 得到断开错误
  - _Requirements: L1, L3, L8, L10, L11, 需求 1.2–1.6_
  - _Prompt: Role: Java 后端开发 | Task: 实现服务器端的连接管理 | Restrictions: 不加新依赖；握手路径不走登录会话 | Success: 测试全过_

- [x] 9. 连接程序：参数和启动说明
  - File: tantalum-connect/pom.xml（新）、connect/Main.java（新）
  - 参数：`--server`、`--allow`（多个）、`--allow-write`、`--allow-shell`、`--allow-read`、`--env`、`--no-network`、`--name`、`--mxc`、`--help`；令牌从 `TANTALUM_TOKEN` 或不回显的输入读；检查目录；打印启动说明
  - 测试：没有 `--allow` 拒绝启动；目录不存在拒绝；`--help` 列出全部参数；命令行里出现像令牌的参数时拒绝并提示用环境变量
  - _Requirements: L2, L4, L16, 需求 2.1, 7.3_
  - _Prompt: Role: Java 开发 | Task: 写连接程序的入口 | Restrictions: 令牌不写文件 | Success: 测试全过_

- [x] 10. 连接程序：连接、执行、记录
  - File: connect/Link.java、Calls.java、Journal.java（新）
  - `Link`：连接、按 1–30 秒退避重连、收到 4400/4401/4403/4409 退出（退出码 2）。`Calls`：三项检查（用户、工具类别、路径）；虚拟线程执行；`cancel` 和断开时打断；每个会话一份 `Observations`。`Journal`：每个调用一行，不含内容和令牌
  - 测试：服务器硬发 `write_file`（没有 `--allow-write`）被拒；`user` 不对被拒；越界路径被拒；`cancel` 打断；记录里没有文件内容
  - _Requirements: L4, L8, L15, 需求 2.3, 2.5_
  - _Prompt: Role: Java 并发开发 | Task: 实现连接程序的连接和执行 | Restrictions: 不信任服务器的判断，每个调用都检查 | Success: 测试全过_

- [x] 11. 服务器加连接程序的集成测试
  - File: tantalum-connect/src/test/java/tantalum/connect/LinkIT.java（新）
  - 同一个 JVM 里起服务器和 `Link`：生成令牌 → 连上 → `LocalLinks.call` 执行 `read_file`、`edit_file` 结果正确 → 撤销后连接程序退出；强关连接时在跑的调用得到断开错误；断网重连后能继续调用
  - _Requirements: 需求 8.1_
  - _Prompt: Role: Java 测试 | Task: 写真连接的集成测试 | Success: mvn -o verify 通过_

## 阶段 3：接到 agent 和插件页

- [ ] 12. 工具自己的超时
  - File: shell/plugin/Tool.java、shell/handler/ToolHandler.java
  - `Tool` 加 `default Duration timeout(Json arguments, Duration standard)`；`ToolHandler` 用它代替固定超时
  - 测试：声明了更长超时的工具不在 60 秒时被打断；没声明的照旧
  - _Requirements: L14_
  - _Prompt: Role: Java 后端开发 | Task: 让工具能声明自己的超时 | Success: ToolHandlerTest 全过_

- [ ] 13. 转发工具，去掉服务器上的文件工具
  - File: shell/local/LocalTool.java（新）、shell/tool/ReadAttachmentTool.java（新）、shell/tool/BuiltinTools.java、shell/boot/Engine.java，删 shell/tool 下的文件工具类；value/attachment/AttachmentText.java、kernel/attachment/ImageBudget.java
  - 每个 `tantalum.local` 工具包一个服务器端 `LocalTool`：只转发，不读写文件；图片照附件规则存好再交给模型。`read_attachment` 从 `ReadFileTool` 拆出来读附件；提示文字改成用 `read_attachment`
  - 测试：转发结果和错误照样变成 `ToolOutput`；图片存进附件目录；没连接时返回 “The local workspace is not connected.”；`read_attachment` 只能读本人附件
  - _Requirements: L6, L7, L8, 需求 3.3, 3.4_
  - _Prompt: Role: Java 后端开发 | Task: 把服务器上的文件工具换成转发工具 | Restrictions: shell.local 不引用 java.nio.file.Files | Success: 测试和 ArchitectureTest 全过_

- [ ] 14. 开关和“哪些工具可用”
  - File: shell/access/PluginSwitches.java、shell/access/PluginGate.java、shell/local/LocalWorkspace.java（新）
  - `Kind.LOCAL`：`local-workspace`、`fs-read`、`git`、`file-edit`、`shell`，默认开。`LocalWorkspace.enabled` = 总开关 × 子开关 × 有连接 × 连接允许这一类；`PluginGate.toolEnabled` 对本机工作区的工具改问它
  - 测试：组合表（总开关关时全没有；没 `write` 时没有改文件工具；不是 Git 仓库时没有 Git 工具）；待办、记忆、`read_attachment` 不受影响
  - _Requirements: L9, L17, 需求 6.1, 6.2_
  - _Prompt: Role: Java 后端开发 | Task: 实现本机工作区的开关和可用性 | Success: 测试全过_

- [ ] 15. system 段、`AGENTS.md`、工具来源
  - File: shell/handler/ContextHandler.java、shell/local/LocalWorkspace.java、console/conversation/ToolChangeNotice.tsx、console/text/zh.ts
  - “本机工作区”一段（会变的段）；`AGENTS.md` 改从电脑取，2 秒没回就不带；`toolSources` 写 `local`；对话页的工具变化遇到 `local` 写“本机工作区没有连上或没有允许”
  - 测试：连上、换目录、断开时段落变化并追加更新消息；不再读服务器目录里的 `AGENTS.md`；ToolChangeNotice 的 vitest
  - _Requirements: L6, L9, 需求 3.5, 5.3_
  - _Prompt: Role: 全栈开发 | Task: 把电脑的信息放进上下文 | Restrictions: 不改工具定义 | Success: 测试全过_

- [ ] 16. 插件页接口
  - File: shell/web/LocalController.java（新）
  - `GET /api/local`、`POST /api/local/tokens`、`DELETE /api/local/tokens`；要登录、查 Origin；响应里的命令带 `--server` 地址，令牌只在 POST 的响应里出现一次
  - 测试：未登录 401；别人的连接看不到；撤销后连接断开
  - _Requirements: L2, L17, 需求 1.1, 6.4_
  - _Prompt: Role: Java 后端开发 | Task: 写插件页用的接口 | Success: 测试全过_

- [ ] 17. 插件页卡片
  - File: console/plugins/LocalWorkspaceCard.tsx（新）、console/plugins/PluginsPage.tsx、console/api/*.ts、console/text/zh.ts
  - 总开关和四个子开关；连接状态、到期倒计时；“连接这台电脑”选有效期，弹出令牌和可复制的命令（只显示一次）；“撤销”；子开关开着但连接程序没允许时写明；页面打开时每 2 秒取状态
  - 测试：vitest 覆盖未连接、已连接、命令行不可用、到期这几种样子
  - _Requirements: L17, 需求 6_
  - _Prompt: Role: 前端开发 | Task: 做本机工作区卡片 | Restrictions: 照现有插件页的样式 | Success: vitest 全过_

- [ ] 18. 链路测试和“服务器上没有文件工具”
  - File: tantalum-shell/src/test/java/tantalum/shell/local/LocalChainTest.java、NoServerFileToolsTest.java（新）
  - 假的连接接进 `E2eServer`：连上 → 下一次提问有工具变化（新增）；断开 → 下一次提问显示不再可用；调用中途断开 → 错误结果。启动完整服务器，注册的工具只有转发工具、`read_attachment`、记忆和待办类
  - _Requirements: 需求 5, L6_
  - _Prompt: Role: Java 测试 | Task: 写链路测试和守护测试 | Success: 测试全过_

- [ ] 19. 浏览器端到端
  - File: console/e2e/local-workspace.spec.ts（新）、E2eServer 的辅助接口
  - 插件页生成令牌 → 后台起连接程序（临时目录、`--allow-write`）→ 卡片显示已连接 → 对话里改文件，文件真的变了，这一轮上方显示工具变化 → 撤销 → 连接程序退出 → 下一次提问显示不再可用
  - _Requirements: 需求 8.3_
  - _Prompt: Role: 测试开发 | Task: 写端到端测试 | Success: Playwright 全过_

## 阶段 4：命令行和安装包

- [ ] 20. MXC 检查和规则
  - File: local/sandbox/Sandbox.java、Mxc.java、MxcPolicy.java（新）
  - 找 `mxc-exec-mac`（安装包里的或 `--mxc`），`--dry-run` 最小规则，再真跑 `/usr/bin/true`，任何一步失败都给出原因。规则按设计：读写目录、只读目录（含 `/opt/homebrew`、`/usr/local`、`--allow-read`）、禁止 `~/.ssh` 和 `~/Library/Keychains`、网络、`hostLoopback` 拒绝、环境变量
  - 测试：规则 JSON 的内容（没有 `--allow-write` 时允许目录是只读）；找不到程序时原因清楚；环境变量里不会出现含 `KEY`、`TOKEN`、`SECRET`、`PASSWORD` 的名字和 `TANTALUM_TOKEN`
  - _Requirements: L13, L14, 需求 4.2, 4.3, 4.5_
  - _Prompt: Role: Java 开发 | Task: 实现 MXC 的检查和规则生成 | Restrictions: 不可用时不能退回不隔离执行 | Success: 测试全过_

- [ ] 21. `bash`
  - File: local/tool/Bash.java（新）、local/tool/Tools.java、tantalum-local/src/test/java/tantalum/local/sandbox/MxcIT.java（新）
  - 参数 `command`、`timeout`（默认 120、最多 600）、`workdir`；声明自己的超时；只保留最后 2000 行或 50 KB；退出码不是 0 算错误；取消时先取子孙进程再逐个结束；每次调用一个临时目录，结束后删掉
  - 测试（`MxcIT`，只在 macOS 上、程序存在时跑，否则跳过并写明原因）：读不到 `~/.ssh` 和另造的家目录文件；没有写权限时写不了；有时只能写允许目录；`--no-network` 连不上外网；连不上本机服务器端口；超时和取消后没有残留进程
  - _Requirements: L13, L14, 需求 4, 8.2_
  - _Prompt: Role: Java 开发 | Task: 实现在 MXC 里执行的 bash | Success: MxcIT 在 Yi 的 Mac 上全过_

- [ ] 22. 安装包
  - File: tantalum-connect/pom.xml（`mac` profile）、tantalum-connect/src/package/*、THIRD_PARTY_NOTICES.md
  - 下载 MXC v1.0.1 发布包并校验 SHA-256，取出 `mxc-exec-mac`；`jlink` 裁剪运行环境；`jpackage` 做 `Tantalum Connect.app`，打成 `.zip`；包里带启动脚本 `tantalum-connect`
  - 测试：解压后在没有装 Java 的环境（`PATH` 去掉 Java、`JAVA_HOME` 置空）里 `tantalum-connect --help` 能跑；包里有 MXC 并且检查通过
  - _Requirements: L16, 需求 7.1_
  - _Prompt: Role: 构建工程 | Task: 打 macOS 安装包 | Restrictions: MXC 程序不进 Git 仓库 | Success: 安装包能用_

- [ ] 23. 说明文档
  - File: README.md、.spec-workflow/steering/tech.md、.spec-workflow/BACKLOG.md
  - README：下载、第一次打开怎么放行、生成令牌、启动命令、每个参数的意思和风险、MXC 拦什么不拦什么（网络只能整体开关）。tech.md 记设计和测量结果；BACKLOG 更新反向沙箱一行，加 Windows、代码索引两项
  - _Requirements: 需求 7.2_
  - _Prompt: Role: 技术写作 | Task: 写说明 | Success: Yi 照着能装起来_

- [ ] 24. 测量和 8091 演示
  - File: tantalum-connect/src/test/java/tantalum/connect/LinkBench.java（新）
  - `read_file` 经过连接程序和直接执行各 1000 次，比 P95（≤ 20 ms）；连接程序空闲内存（≤ 150 MB）。8091 上用 Yi 的 Mac 和真实 Claude，在一个小 Maven 项目里修一个失败的测试：读代码、改代码、跑 `mvn -q test` 通过；截图给 Yi；报告 Claude 用量
  - _Requirements: NFR Performance, 需求 8.4_
  - _Prompt: Role: 测试开发 | Task: 测量并做真实演示 | Success: 数字达标，演示成功_
