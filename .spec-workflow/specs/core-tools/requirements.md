# Requirements Document

## Introduction

现在模型只有 `read_file`、`grep` 和三个记忆工具：能看不能改，读大文件时整个塞进上下文（最多 1 MB）。本 spec 补上 pi 和 DSH 都有的基本文件工具：`read_file` 按行窗口读取并能续读，新增写文件、改文件、列目录、按名字找文件。

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
- 真实 Claude 跑两项，每项有判定标准：一是读一个很长的文件，只用窗口读到藏在中间的词；二是改一个文件里的函数，先读后改，改完测试通过。
- 端到端：对话页显示 `edit_file` 的差异和 `write_file` 的内容。
