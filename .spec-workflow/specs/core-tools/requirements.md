# Requirements Document

## Introduction

现在模型只有 `read_file`、`grep` 和三个记忆工具：能看不能改，读大文件时整个塞进上下文（最多 1 MB）。本 spec 补上 pi 和 DSH 都有的基本文件工具：`read_file` 按行窗口读取并能续读，新增写文件、改文件、列目录、按名字找文件；再加一个像 DSH 那样的插件页，每个用户自己选用哪些工具（Yi 2026-11-03 定：并进本 spec，按登录用户分别记）。

参照的是 pi-mono 的 `main`（00967748）和 DSH 2026-11-03 的 `main`（ce7073d5）：

- **pi** 默认给模型 `read`、`bash`、`edit`、`write`，另有 `grep`、`find`、`ls`（`CA/src/core/tools/index.ts:195-211`）。它是单用户本机程序，写文件没有任何前置检查。
- **DSH** 默认给 `read`、`read_image`、`write`、`edit`、`glob`、`grep`，`apply_patch` 和 `str_replace_editor` 可选加载，没有列目录工具（`packages/bundle/base/cordis.patch.yml:292-304`）。它有“先读后写”的规则：没读过的已有文件不能覆盖或修改，读过之后被别处改过也不能（`packages/fs/fs-observation-policy/src/index.ts:65-97`）。
- **我们**是多用户服务器，每个用户一个工作目录，工具都在服务器进程里执行，靠路径校验隔离，没有沙箱。

路径写法：pi 的 `CA` 指 `packages/coding-agent`；DSH 路径相对于仓库根目录；两边都没有对应做法的标“自定”。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| `read_file` 按行窗口读取、续读、长行截断、二进制识别 | `bash`、`pwsh`（Yi 2026-11-03 定：等沙箱 spec，在用户本机执行） |
| 新增 `write_file`、`edit_file`、`list_dir`、`find_files` | 联网 `web_search`、`web_fetch`（下一个 spec `web-tools`） |
| 先读后写的规则 | 一次补丁改多个文件的 `apply_patch`（DSH 默认也不加载） |
| 控制台和轨迹页显示这些工具，`edit_file` 显示改动前后的差异 | 工具结果过大时整体落盘（DSH spill files），留到 `web-tools` 一起定 |
| 插件页：每个用户给自己开关内置工具、MCP 服务器和钩子插件 | 在页面上安装、卸载、升级插件（DSH 装的是 npm 包；我们的插件是启动时加载的 Java 包，不能在运行中装卸） |
| 所有插件的加载和执行都按用户名过滤 | 管理员统一替用户开关（Yi 定：用户自己管自己的） |
| Windows 写的文件（CRLF 换行、BOM）照原样保留 | 服务器装在 Windows 上（Yi 定：单独 spec） |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 |
|---|---|---|
| C1 | 工具叫什么 | 沿用已有的 `read_file`，新工具同一种起名法：`write_file`、`edit_file`、`list_dir`、`find_files`。pi 叫 `read`、`write`、`edit`、`ls`、`find`，DSH 叫 `read`、`write`、`edit`、`glob`；改 `read_file` 的名字会让旧会话历史里的工具调用对不上，自定 |
| C2 | `read_file` 读多少 | 参数加 `offset`（从 1 开始的行号）和 `limit`（行数）。默认最多 2000 行或 50 KB，先到哪个算哪个（pi `CA/src/core/tools/truncate.ts:11-12`；DSH `packages/fs/tool-fs/src/read.ts:15`、`read-render.ts:14`）。单行超过 2000 个字符时截断并注明（DSH `read-render.ts:11,69-71`）。没读完时在结尾写明显示了哪几行、共多少行、下次用哪个 `offset` 继续（pi `read.ts:189-198`；DSH `read-render.ts:155-161`） |
| C3 | `read_file` 返回的样子 | 原文，不加行号（同 pi）。DSH 每行加 `N: ` 前缀，但模型改文件时要逐字复制原文，行号容易混进去；续读的行号写在结尾的说明里。行尾的 `\r` 去掉（DSH `read-render.ts:91-93`），改文件时按 C7 还原 |
| C4 | 多大的文件能读 | 去掉现在 1 MB 的整文件上限：边读边数，只把窗口里的行放进结果，所以大文件也不会占满内存。文件超过 64 MB 时拒绝，提示用 `grep` 找位置（DSH 10 MiB 以上改为流式读取，不设上限；我们是多用户服务器，设一个上限，自定）。前 8 KB 里有空字节的当二进制文件拒绝，不是合法 UTF-8 的拒绝（DSH `fs-local/src/fsio.ts:18,414-416,516-521,360-362`）。图片照 attachments spec 交给模型 |
| C5 | 写和改之前要不要先读 | 要，照 DSH 的规则：已有文件必须在本会话里读过（窗口读过也算），并且读过之后没有被改过，才能覆盖或修改；新文件可以直接写；自己刚写过、改过的文件可以接着改，不用重读（DSH `fs-observation-policy/src/index.ts:65-97`、`tool-fs/src/mutation-result.ts:15-21`）。pi 没有这条规则；我们一个工作目录可能被多个运行同时用，盲写会互相覆盖。“读过”的记录放在服务器内存里，按会话记；会话移出内存或服务重启后要重新读一次，自定 |
| C6 | `write_file` 怎么写 | 自动建上级目录（pi `write.ts`；DSH `fsio.ts:630`）。先写临时文件、落盘、再改名替换，读的人不会看到写了一半的文件（DSH `fsio.ts:618-702`）。已有文件保留原来的权限；新文件 600、新目录 700（DSH 同）。内容原样写入，不改换行。单次内容最多 10 MB，自定 |
| C7 | `edit_file` 怎么改 | 参数 `path`、`old_string`、`new_string`、`replace_all`（默认 false），同 DSH `tool-fs/src/edit.ts:80-90`。`old_string` 必须逐字匹配；默认必须正好出现一次，出现多次时报出现了几次，让模型补上下文或用 `replace_all`。不做模糊匹配（同 DSH；pi 在找不到时会忽略引号、破折号、行尾空格的差别再找一次，`edit-diff.ts:34-55`，结果不好预测）。匹配前把文件和参数里的 CRLF 都换成 LF，写回时按文件原来的换行还原（pi `edit.ts:184-195`；DSH `fsio.ts:731-751`）；开头的 BOM 去掉再匹配，写回时保留（pi 同；DSH 会丢掉 BOM） |
| C8 | 哪些能并行 | `read_file`、`list_dir`、`find_files` 可以和别的只读调用同时执行；`write_file`、`edit_file` 一个一个执行（DSH `packages/core/tools/src/pipeline.ts:105-113`）。同一个文件的写入在进程内加锁，再加上 C5 的版本检查，两个运行同时改一个文件时只有一个成功，另一个收到“文件读过之后被改过”（DSH `fs-local/src/index.ts:107-145`） |
| C9 | `list_dir` | 列一层，按名字排序（不分大小写），目录名后加 `/`，包括以 `.` 开头的文件，最多 500 项（pi `CA/src/core/tools/ls.ts:61,108`）。DSH 没有这个工具 |
| C10 | `find_files` | 参数 `pattern`（glob）、`path`（默认工作目录根）。不带 `/` 的模式匹配任意深度的文件名，带 `/` 时按路径匹配（DSH `tool-fs-search/src/glob.ts:323-325`）。跳过 `.git`、`.svn`、`.hg` 和 `node_modules`；不读 `.gitignore`（pi 读，靠外部程序 `fd`；DSH 不读）。结果按路径排序，最多 1000 个（pi `find.ts:40`），超出时写明 |
| C11 | 哪里不能写 | 只能写当前用户自己的工作目录，经路径校验，`..` 和指向外面的符号链接都拒绝。工作目录里的 `memory/` 不能用 `write_file`、`edit_file` 改，要用记忆工具（记忆工具会拒绝像密钥的内容，自定）。`workspace-mode=shared`（所有用户共用一个工作目录）时不提供 `write_file` 和 `edit_file`，免得用户互相改文件，自定 |
| C12 | 插件开关由谁定 | 用户自己管自己的：每个用户在插件页给自己开关，按登录用户记，只影响自己的会话，没有管理员替用户开关的一层（Yi 2026-11-03）。DSH 是单人程序，开关对整个配置生效（`packages/boot/plugin-manager/README.zh.md`“改动影响使用该 profile 的全部会话”） |
| C13 | 插件页上有什么 | 照 DSH 侧栏的“插件”页（`packages/client/ui-plugin-manager/README.zh.md`）：左侧栏一个“插件”入口。分三块：内置工具（每个工具一个开关，含记忆工具）；MCP 服务器（每个服务器一个开关，展开看它放行的工具，每个工具也能单独关，并显示连接状态）；钩子插件（每个插件一个开关，同一个插件的钩子、插件工作和工具后处理一起开关，并列出挂在哪些位置）。每一项显示中文名和一句说明 |
| C14 | 开关什么时候生效 | 从下一次提问开始（Yi 2026-11-03）。每次提问开始一个运行时，服务器本来就要组装一次上下文；这时按用户当前的开关算出工具和钩子插件的名单，和会话里记下的不同就换上新的，并给模型追加一句变化说明（如“工具有变化：新增 write_file”），同现有的上下文变化说明（`kernel/projection/ContextUpdate`）。运行进行到一半时不换，这个运行的请求前缀和钩子保持一致。关掉的工具在执行前立即拒绝，返回“这个工具已在插件页关闭”，不等下一次提问。换名单后的第一次请求没有 prompt 缓存，要按全价算一次输入。pi 的 `setActiveTools` 在下一次调模型时生效（`CA/src/core/extensions/types.ts:1802`），DSH 开启 HMR 时立即生效；我们放在提问之间，自定。历史里调用过、现在不在名单里的工具，Claude 照常接受（已有测试 `ClaudeSmokeTest.historyMayCallAToolThatIsNoLongerListed`） |
| C15 | 开关存在哪 | 每个用户一份，存在数据目录里，重启后还在；开关本身不进 journal。每次组装上下文时，算出的工具和钩子插件名单记进 `ContextAssembled` 事件（工具列表现在就记在这里），回放读的是这份记录，和之后的开关无关，所以回放结果不变。默认全开，自定 |
| C16 | 按用户名过滤 | 插件的加载和执行一律先按当前登录用户名过滤（Yi 2026-11-03）。“加载”指交给某个会话：每次提问开始组装上下文时，只给这个用户开着的工具和钩子插件（C14）。“执行”指每一次调用：调工具前、跑钩子前、跑插件工作前、跑工具后处理前，都按会话里记下的用户和名单再查一次，不在名单里的不执行。所有调用插件的地方都经过同一个过滤入口，用一条 ArchUnit 规则钉住：过滤入口之外的代码不能直接调用插件的方法，自定 |

## Requirements

### Requirement 1：按窗口读文件

**User Story:** 作为用户，我希望模型读大文件时只读需要的部分，以便一次调查不会因为一个大文件就把上下文占满。

出处：pi `CA/src/core/tools/read.ts:95,153-203`、`truncate.ts:11-12`；DSH `packages/fs/tool-fs/src/read.ts:74-84`、`read-render.ts:11-14,69-71,91-98,152-170`。2026-11-03 那次调查里，模型一次读进 132 KB 和 342 KB 的页面快照，运行中途被迫压缩（BACKLOG）。

#### Acceptance Criteria

1. WHEN 不带 `offset`、`limit` 读一个短文件 THEN `read_file` SHALL 返回全文，和现在一样。
2. WHEN 文件超过 2000 行或 50 KB THEN `read_file` SHALL 只返回开头不超过两个上限的整行，结尾写明“第几行到第几行，共多少行，用 offset=… 继续”。
3. WHEN 带 `offset` 和 `limit` THEN `read_file` SHALL 从第 `offset` 行开始最多返回 `limit` 行（`limit` 最大 2000），仍受 50 KB 限制；没到文件末尾时写明下一次的 `offset`。
4. WHEN `offset` 超出文件行数 THEN `read_file` SHALL 报错并写明文件共有多少行。
5. WHEN 某一行超过 2000 个字符 THEN 这一行 SHALL 截到 2000 个字符并注明被截断；不能因为一行太长就什么都不返回。
6. WHEN 文件超过 64 MB、是二进制、或不是合法 UTF-8 THEN `read_file` SHALL 报错说明原因，超大文件提示用 `grep` 找位置。
7. WHEN 读的是图片、PDF 或附件路径 THEN 行为 SHALL 和 attachments spec 规定的一样。
8. WHEN 工具说明 THEN SHALL 告诉模型大文件用 `offset`、`limit` 分段读（pi `read.ts:95`；DSH `read.ts:74`）。

### Requirement 2：写文件

**User Story:** 作为用户，我希望模型能在我的工作目录里新建和重写文件。

出处：pi `CA/src/core/tools/write.ts:49-70`；DSH `packages/fs/tool-fs/src/write.ts:36-75`、`packages/fs/fs-local/src/fsio.ts:618-702`、`packages/fs/fs-local/src/index.ts:350-363`。

#### Acceptance Criteria

1. WHEN `write_file` 写一个不存在的文件 THEN 系统 SHALL 建好上级目录并写入，返回“已创建”和路径、字节数。
2. WHEN 覆盖一个本会话读过且之后没被改过的文件 THEN 系统 SHALL 整个替换，返回“已更新”；保留文件原来的权限。
3. WHEN 覆盖一个本会话没读过的已有文件 THEN 系统 SHALL 拒绝，告诉模型先读这个文件再试（C5，DSH `tool-fs/src/error.ts:29-42`）。
4. WHEN 文件在读过之后被改过（别的运行、别的工具或用户） THEN 系统 SHALL 拒绝，告诉模型重新读再试。
5. WHEN 写到一半服务停止 THEN 原文件 SHALL 要么是旧内容要么是新内容，不会是半个文件（先写临时文件再改名）。
6. WHEN 路径越出工作目录、在 `memory/` 里、是目录，或内容超过 10 MB THEN 系统 SHALL 拒绝并说明原因（C11）。越界和不存在用同一句话，不泄露工作目录外有什么（同现有 `Workspace`）。

### Requirement 3：改文件

**User Story:** 作为用户，我希望模型只改文件里需要改的一段，不用整个重写。

出处：DSH `packages/fs/tool-fs/src/edit.ts:47-90`、`packages/fs/fs-local/src/fsio.ts:731-751,862-882`；pi `CA/src/core/tools/edit.ts:146-202`、`edit-diff.ts:255-290`。

#### Acceptance Criteria

1. WHEN `old_string` 在文件里正好出现一次 THEN `edit_file` SHALL 把它换成 `new_string` 并写回，返回改了几处。
2. WHEN `old_string` 找不到 THEN 系统 SHALL 报错，说明必须逐字匹配，包括空格和换行。
3. WHEN `old_string` 出现多次且没有 `replace_all` THEN 系统 SHALL 报出现了几次，让模型补上下文或设 `replace_all`；文件不变。
4. WHEN `replace_all` 为 true THEN 系统 SHALL 替换所有出现的地方，返回替换了几处。
5. WHEN `old_string` 为空或和 `new_string` 相同 THEN 系统 SHALL 报错，文件不变。
6. WHEN 文件用 CRLF 换行或开头有 BOM THEN 匹配 SHALL 不受影响，写回后换行风格和 BOM 都和原来一样（C7）。
7. WHEN 文件没读过、读过后被改过、是二进制或不存在 THEN 系统 SHALL 和 `write_file` 一样拒绝并说明（C5）。改完之后接着改同一个文件不用重读。
8. WHEN 改动写回 THEN 写法 SHALL 和 `write_file` 一样先写临时文件再改名，保留原来的权限。

### Requirement 4：列目录和找文件

**User Story:** 作为用户，我希望模型能看到工作目录里有哪些文件，不用靠猜路径。

出处：pi `CA/src/core/tools/ls.ts:11-12,59-61,108,139-150`、`find.ts:27-40,77,125`；DSH `packages/fs/tool-fs-search/src/glob.ts:25,37,317-325`。

#### Acceptance Criteria

1. WHEN `list_dir` 不带路径 THEN 系统 SHALL 列工作目录根下的一层，按名字排序，目录名后加 `/`，包括以 `.` 开头的项。
2. WHEN 一个目录超过 500 项 THEN `list_dir` SHALL 只列前 500 项并写明共有多少项。
3. WHEN `find_files` 的模式不带 `/`（如 `*.java`） THEN SHALL 匹配任意深度的文件名；带 `/` 时按相对路径匹配（如 `src/**/*Test.java`）。
4. WHEN `find_files` 搜索 THEN SHALL 跳过 `.git`、`.svn`、`.hg`、`node_modules`，不跟随符号链接，只返回文件，按路径排序。
5. WHEN 结果超过 1000 个 THEN `find_files` SHALL 只给前 1000 个并写明总数，提示缩小范围。没有结果时写明“没有找到”。
6. WHEN 路径越出工作目录或不是目录 THEN 两个工具 SHALL 用和 `read_file` 相同的说法拒绝。

### Requirement 5：并发和一致性

**User Story:** 作为运维者，我希望同一个工作目录被多个运行同时使用时文件不会坏，回放也不会重新执行工具。

出处：DSH `packages/core/tools/src/pipeline.ts:105-113`、`packages/fs/fs-local/src/index.ts:107-145`；现有内核的并行规则（`ToolSafety`）。

#### Acceptance Criteria

1. WHEN 一轮里有多个只读调用 THEN 它们 SHALL 同时执行；有写或改时，写和改 SHALL 一个一个执行（C8）。
2. WHEN 两个运行同时改同一个文件 THEN SHALL 只有一个成功，另一个收到“读过之后被改过”，文件内容是成功的那一个写的。
3. WHEN 回放会话 THEN 写和改 SHALL 不重新执行，结果取自 journal，和其他工具一样。
4. WHEN 服务重启后继续一个会话 THEN 模型改文件前 SHALL 要重新读（“读过”的记录不跨进程，C5），错误说明里写清楚这一点。

### Requirement 6：显示

**User Story:** 作为用户，我希望在对话和轨迹里看清模型读了哪段、写了什么、改了哪里。

出处：DSH 的工具卡片显示改动的差异（`packages/fs/tool-fs/src/edit.ts:113-117` 把差异放在界面元数据里，不发给模型）；pi 同样只在界面显示 diff（`edit.ts:197-200`）。

#### Acceptance Criteria

1. WHEN 对话页显示 `edit_file` 的步骤 THEN SHALL 显示改动前后的差异（从调用参数里的 `old_string`、`new_string` 算出，不另存）。
2. WHEN 显示 `write_file` 的步骤 THEN SHALL 显示路径、字节数，以及内容的前若干行，可以展开看全文。
3. WHEN 显示 `read_file` 的步骤 THEN SHALL 显示读的是哪几行（有 `offset`、`limit` 时）。
4. WHEN 打开轨迹页 THEN 这些工具 SHALL 和现有工具一样有记录和检查器页签；`edit_file` 的检查器里有差异视图。
5. WHEN 发给模型的结果 THEN SHALL 只有简短的文字（改了几处、写了多少字节），不带差异（同 DSH、pi），不额外占上下文。

### Requirement 7：插件页

**User Story:** 作为用户，我希望像在 DSH 里一样有一个插件页，自己决定模型能用哪些工具，比如只给它读文件、不给它改。

出处：DSH `packages/client/ui-plugin-manager/README.zh.md`（侧栏“插件”页、插件卡片和开关）、`packages/client/ui-settings-plugins/README.zh.md`（只读的内置插件列表）、`packages/boot/plugin-manager/README.zh.md`（开关的保存和生效）。

#### Acceptance Criteria

1. WHEN 点左侧栏的“插件” THEN 系统 SHALL 打开插件页，分“内置工具”“MCP 服务器”“钩子插件”三块，每项显示中文名、一句说明和当前状态。
2. WHEN 用户关掉或打开一个内置工具、MCP 服务器或钩子插件 THEN 系统 SHALL 立即保存，只改这个用户的设置；页面写明“从下一次提问开始生效，关掉的工具立即不能再调用”。
3. WHEN 展开一个 MCP 服务器 THEN 页面 SHALL 列出它放行的工具，每个工具可以单独关；服务器连不上时显示原因。
4. WHEN 查看钩子插件 THEN 页面 SHALL 列出名字、说明、挂在哪些位置（调模型前、调工具前等），每个插件一个开关，它的钩子、插件工作和工具后处理一起开关。
5. WHEN 用户重新登录或服务重启 THEN 设置 SHALL 还在。
6. WHEN 两个用户各自开关 THEN 互不影响；接口只读写当前登录用户自己的设置，别人的读不到也改不了。
7. WHEN 页面宽度小于 768 px THEN 插件页 SHALL 正常显示，不横向滚动（同 DSH 窄屏规则）。

### Requirement 8：开关生效

**User Story:** 作为用户，我希望关掉的工具模型真的用不了。

出处：现有规则——会话的工具在第一次运行时固定（README“记忆”一节）；DSH 关掉的工具不进模型这一步的工具列表（`packages/web/tool-web/src/search.ts:414` 的同类做法）。

#### Acceptance Criteria

1. WHEN 一次提问开始组装上下文 THEN 发给模型的工具列表 SHALL 只包括这个用户当时开着的工具；内核 SHALL 只运行这个用户当时开着的钩子插件；两份名单都记进 `ContextAssembled` 事件（C14、C15）。
2. WHEN 会话进行中用户打开一个工具或钩子插件 THEN 它 SHALL 从下一次提问开始可用，模型收到一句变化说明；正在进行的运行不受影响。
3. WHEN 会话进行中用户关掉一个工具 THEN 模型之后再调用它时，服务器 SHALL 在执行前拒绝，结果是一句说明，不执行任何操作；从下一次提问开始它不再出现在工具列表里。
4. WHEN 会话进行中用户关掉一个钩子插件 THEN 正在进行的运行 SHALL 照运行开始时的名单继续，下一次提问开始不再运行它。钩子是纯函数，运行中途改名单会让回放和实际不一致。
5. WHEN 任何一次插件调用（工具、钩子、插件工作、工具后处理） THEN 系统 SHALL 先按会话的用户名和名单过滤；过滤入口之外直接调用插件的代码 SHALL 被 ArchUnit 规则拒绝（C16）。
6. WHEN 回放旧会话 THEN 结果 SHALL 和开关无关，和现在一样；改动之前的旧会话没有名单，按“全部开着”回放，和现在一样。
7. WHEN 新加的写工具（`write_file`、`edit_file`）被关掉 THEN 模型 SHALL 只能读，相当于只读模式。

## Non-Functional Requirements

### 安全
- 写和改只在当前用户的工作目录里；`..`、指向外面的符号链接、`memory/` 一律拒绝（C11）。
- 共用工作目录模式下不提供写工具（C11）。
- 工具说明和结果里不出现服务器上的绝对路径，只出现相对于工作目录的路径（同现有工具）。
- README 和架构页里“内置工具只读”的说法要改掉；示例插件 `ReadOnlyHook` 要能拦住新的写工具。

### 性能
- 从一个 64 MB、几十万行的文件里读一个窗口在 1 秒内（参照机器：现有压测用的 Mac Studio）。
- 在一万个文件的目录树里 `find_files` 在 1 秒内。

### 兼容
- 旧会话里的 `read_file` 调用照常显示；不带 `offset`、`limit` 读短文件时结果和现在一样。
- Windows 上写的文件（CRLF、BOM）改过之后仍是原来的格式（C7）。

### 测试
- 每个工具的单元测试覆盖上面每条验收标准，包括 CRLF、BOM、多字节字符、超长行、空文件。
- 真实 Claude 跑三项，每项有判定标准：一是读一个很长的文件，只用窗口读到藏在中间的词；二是改一个文件里的函数，先读后改，改完测试通过；三是同一个会话里先没有 `write_file`、中途在插件页打开，下一次提问模型能用它写文件，带思考的历史不报错。
- 端到端：对话页显示 `edit_file` 的差异和 `write_file` 的内容；插件页关掉 `write_file` 后，正在进行的会话里调用它被拒绝，下一次提问起模型拿不到它；再打开后下一次提问又能用；两个用户的设置互不影响。
