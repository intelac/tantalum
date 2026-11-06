# Tantalum

生产级、通用的 Agent 运行时底座：内核是纯函数，每一步连同它的结果写成 journal 的一行、落盘之后才有效果，恢复时照记下的结果还原、不重算，插件不用改内核。

```
输入环 → 取序号 → Kernel.step → 事件和结果写成一行并落盘 → 输出环 → Handler → 结果事件回到输入环
```

代码分三层，依赖方向 `value ← kernel ← shell`，由 ArchUnit 守护：

| 模块 | 内容 |
|---|---|
| `tantalum-value` | 不可变类型：事件、命令、状态 |
| `tantalum-kernel` | 纯逻辑：转移表、钩子，不做 I/O |
| `tantalum-shell` | 一切 I/O：环、journal、Handler、模型客户端、Web |
| `tantalum-local` | 在用户电脑上执行的工具（文件、Git、`bash`）、MXC 沙箱、和连接程序之间的消息格式，服务器和连接程序共用；不依赖 Spring 和 `tantalum-shell` |
| `tantalum-connect` | 连接程序：在用户电脑上运行，主动连服务器，执行本机工作区的工具；macOS 安装包 |
| `tantalum-examples` | 示例插件，不打进可执行 jar |
| `console/` | 参考控制台 |

`tantalum-shell` 里的包，依赖只朝一个方向，没有循环（ArchUnit 检查）：

| 包 | 内容 |
|---|---|
| `boot` | 组装、启动、关闭：`Engine`、`Storage`（打开和关闭数据目录）、配置。只有它和 `web` 读全局配置 |
| `loop` | 管线：输入环 → 用户通道（取序号、载入冷会话）→ cleanroom（`Kernel.step`，算出这一步的结果）→ 用户通道（事件和结果写成一行、落盘）→ 输出环 → Handler；`Intake` 接收提交（落盘后才回“已接受”，`Receipts`），`Evictor` 回收空闲会话 |
| `journal` | 原始记录：每会话一个只追加的文件（一步一行）、编解码、格式版本、锁、序号 |
| `store` | 能从原始记录重建的东西：按记下的结果折叠（`RecordReplay`）、索引、查询、检查工具，以及读会话的 `SessionQueries` |
| `conversation` | 控制台看到的对话条目，是事件的展示投影 |
| `handler` | 执行命令：组装上下文、调模型、跑工具和插件工作 |
| `model` | Claude 和 OpenAI 兼容两个模型客户端，失败分类共用 `ModelFailures` |
| `tool`、`mcp`、`memory` | 内置工具（`read_attachment`）、MCP 客户端、每用户记忆 |
| `local` | 本机工作区：令牌、连接、转发到电脑的工具、插件页接口用的状态 |
| `sse`、`web`、`auth` | 推送、HTTP 接口、登录 |
| `plugin` | 插件 SDK |
| `access` | 每个用户的插件开关，以及插件唯一的调用入口 `PluginGate` |
| `json`、`net` | JSON 文本、主机名判断 |

设计文档在 `.spec-workflow/`。

## 运行

需要 JDK 25、Maven 3.9、Node 25 和 pnpm。

```sh
(cd console && pnpm install && pnpm build)   # 先构建控制台，它会被打进 jar
mvn clean package -DskipTests
```

默认模型是 Claude，prompt 会发送到 Anthropic，其中可能含客户代码：

```sh
export ANTHROPIC_API_KEY=...
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar
```

不能外发代码时，用本地的 OpenAI 兼容模型（默认指向 `127.0.0.1:8090` 的 strata）：

```sh
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar --tantalum.provider=openai
```

然后打开 <http://127.0.0.1:8080>。配置项和默认值见 `tantalum-shell/src/main/resources/application.properties`。

服务默认只绑定 `127.0.0.1`。

服务器上不读写代码：agent 读、改的是用户自己电脑上的文件，要在电脑上运行连接程序，见下面“本机工作区”。服务器的工作目录（`tantalum.workspace`）只放每个用户的记忆这类服务器自己的数据。

## 登录和用户

第一次启动时数据目录里没有用户，要用环境变量创建第一个账号（没有内置默认密码）：

```sh
export TANTALUM_ADMIN_USER=yi TANTALUM_ADMIN_PASSWORD='...'
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar ...
```

之后用命令行管理用户，密码从标准输入读取；`TANTALUM_DATA_DIR` 指向数据目录（默认 `~/.local/share/tantalum`）：

```sh
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar users add alice
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar users reset alice
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar users disable alice
```

口令用 Argon2id 哈希存储。登录后身份放在 `HttpOnly`、`SameSite=Strict` 的会话 Cookie 里，会话在内存中，重启后要重新登录；写操作还会校验 `Origin`。每个用户的工作目录隔离在 `<workspace>/<用户名>`（`tantalum.workspace-mode=per-user`），会话、运行、回放和 SSE 都只能看到自己的。登录连续失败会被限速。

## 十圈场景

设计图上的十圈有两个测试：

```sh
mvn -pl tantalum-kernel -am test -Dtest=TheLoopTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl tantalum-examples -am verify -Dtest=TenLoopIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

`TheLoopTest` 不启线程，逐圈断言状态和命令，回边被改坏时会指出是哪一圈。`TenLoopIntegrationTest` 经过真实的环、journal、Web 和 SSE，再回放比对每次的 prompt。

## 数据目录

默认 `~/.local/share/tantalum`（`tantalum.data-dir`）。目录下的文件只有进程所有者能读写（600/700），会话文件含完整的 prompt 和工具结果。

| 路径 | 内容 |
|---|---|
| `sessions/<用户>/<会话>.jsonl` | journal：每个会话一个文件，第一行是文件头（`format: 3`），之后一步一行，只追加 |
| `index.db` | 会话列表、提交去重、用量的索引（SQLite）。可以由会话文件重建 |
| `format.json` | 存储格式的版本（`storage: 3`） |
| `users.db` | 账号 |
| `store.lock` | 进程独占锁：一份数据目录同时只能被一个进程使用 |
| `memory/<用户>/` | 只在 `workspace-mode=shared` 时使用的记忆目录；默认每个用户的记忆在自己工作目录的 `memory/` 下 |
| `attachments/<用户>/` | 附件：`objects/<哈希>` 是内容（图片是规范化后的），旁边的 `.json` 是元数据；`requests/` 是请求用的图片版本，可以删，删了重新生成 |

用户名和会话 ID 里不能直接做文件名的字符会被编码，太长的名字截短后加哈希。只有会话文件是事实来源，索引落后或丢失时，启动时从会话文件补齐。旧版本留下的 `snapshots/` 目录不再使用，可以删除。

**一步一行。** 每一行是一个事件（用户消息、模型回复、工具结果、上下文组装……）加上内核处理它的结果（`"result": {...}`）：这一步追加了哪些消息、压缩换了哪一段、运行到了哪一步、发出了哪些命令、钩子做了什么决定、用了多少 token，只写变了的部分。事件进来时不写文件，内核处理完才写这一行；落盘之后才去调模型、执行工具、推送画面、写索引，提交接口也这时才回“已接受”（写不进去回 500，10 秒没写完回 504）。崩溃时没写进去的那一步等于没发生过，它的命令也没执行。

**配上了什么，只在变化时记。** 上下文组装那一行记着这个会话配上的插件、工具（完整定义和来源：`builtin` 或 `mcp:<服务器名>`）、system 各段和压缩设置。会话第一次组装、压缩之后写全文，其余只写变了的部分（`"diff": true`）。恢复时照着记录还原，不读插件、工具目录和 MCP，插件删了也照样打开。

恢复只读这些行，不再调用内核和钩子：打开不在内存的会话、重启后建索引、执行轨迹、请求详情、断线补读都是把记下的结果套回去。所以升级了内核、改了钩子规则或者卸载了插件，旧会话的历史和画面都不变，新规则只管之后的步骤。工具不能用了（插件删了、在插件页关了），下一次提问时告诉模型，压缩之后也再说一次；对话页在那一轮上面显示一行“工具变化”。

结果里，文件前面已有的内容写成引用，不存第二份：长文字和内容块写成哈希，请求里的消息和工具写成历史里的下标，请求只写相对上一次的变化，压缩时裁剪过的工具结果写成原文的切片。真实会话（Yi 的 4 个）按这个格式写，总大小是只记事件时的 103.8%。

## 用 journal 查看一个运行

**控制台：** 打开会话的“轨迹”页签。最上方是时间概览，四条道是输入、模型、工具、插件：点一段选中对应记录，拖选一段时间只看这段里的记录，滚轮缩放，工具栏可切换“按实际时长”和“每个操作等宽”。下面的记录按轮和步排列，每一行都能点开：请求看回放得出的完整 system、工具、消息、选项、用量和计时，工具看参数、结果和 Schema，钩子和插件工作可以互相跳转。对话页的工具调用和运行状态行有“在轨迹中查看”。地址 `/s/<会话>/trace/<运行>` 定位到那一轮，`/s/<会话>/trace?record=<记录>` 定位到那条记录。

**统计页签**（`/s/<会话>/stats`）按轮次列出触发、执行内容、上下文 token、输出 token、缓存命中率（轮次按 token 加权）、首 token 延迟、模型用时、工具用时（并行的只算一次）、输出速度和总用时条，展开看每一步；自动压缩的摘要请求单独一行，不计入合计。和轨迹页共用同一份记录和分页。旧版本没有记录的计时显示“未记录”。

**接口：** 先登录拿到 Cookie，再带着它调用。

```sh
curl -c cookie -H 'content-type: application/json' \
  -d '{"username":"yi","password":"..."}' http://127.0.0.1:8080/api/auth/login
curl -b cookie http://127.0.0.1:8080/api/sessions/<session>/runs
curl -b cookie http://127.0.0.1:8080/api/runs/<run>/turns
curl -b cookie http://127.0.0.1:8080/api/runs/<run>/turns/1
curl -b cookie 'http://127.0.0.1:8080/api/sessions/<session>/trace'              # 轨迹记录：最新一页
curl -b cookie 'http://127.0.0.1:8080/api/sessions/<session>/trace?before=<seq>' # 更早的一页
curl -b cookie 'http://127.0.0.1:8080/api/sessions/<session>/trace?run=<run>'    # 从最新一直到这个运行
curl -b cookie http://127.0.0.1:8080/api/sessions/<session>/trace/calls/<command> # 一次模型调用的完整输入
```

**轨迹记录**（`/api/sessions/<session>/trace`）按运行从新到旧分页，一页至少 20 个运行或 400 条记录，不拆开一个运行。记录有 9 种：`run`、`system`、`user`、`context`、`compacted`、`request`、`tool`、`work`、`hook`，每条带 `id`、`pos`（创建它的事件“序号.下标”）、所属的轮和步、开始和结束时刻。记录里不放 prompt 的消息、system 原文和工具 Schema，这些按需从 `/trace/calls/<command>` 取。实时的变化通过 SSE 的 `trace` 消息推送，客户端按 `id` 覆盖；页面和推送来自同一个投影，所以刷新前后看到的一样。

**journal 里和轨迹有关的字段**，旧版本写的行没有这些字段，读出来是空，显示“未记录”：

| 事件 | 字段 | 含义 |
|---|---|---|
| `ModelReplied`、`ModelFailed`、`ModelInterrupted` | `startedAt` | 拿到模型并发名额、开始调用的时刻 |
| | `firstTokenAt` | 第一个内容分片（文字、思考或工具参数）到达的时刻 |
| | `options` | 实际发出的请求选项：提供方、模型、最大输出、是否允许工具，以及 thinking、effort 等；不含任何请求头、地址和密钥 |
| `ToolFinished`、`WorkDone` | `startedAt` | 拿到名额、真正开始执行的时刻；没有真正执行时为空 |

**钩子的决定**写在这一步的结果里：内核在钩子拦截、要求工作或带补丁放行时报一条 `HookDecided` 事实（钩子名、位置、结果、理由、补丁的长度和工具名），推送和轨迹里都看得到，恢复时照着记下的结果显示，不重新问钩子。只放行、没加内容的钩子不报。

**直接看事件：**

```sh
jq -c 'select(.seq) | [.seq, .type, .run, .at]' ~/.local/share/tantalum/sessions/yi/<session>.jsonl
jq -c 'select(.result) | [.result, (.commands // [] | map(.type)), (.facts // [] | map(.type))]' ~/.local/share/tantalum/sessions/yi/<session>.jsonl
```

**请求详情和指纹。** 每次调模型，这一步的结果记下这次请求的指纹（SHA-256 的前 128 位）。轨迹页的请求详情按记下的结果还原请求，再算一次指纹；不一致时页面顶部提示“与当时发出的请求不完全一致”，内容照常显示。

**检查新代码会不会改变旧会话。** 部署前用新版本对数据目录（可以是一份拷贝）跑一遍：

```sh
java -jar tantalum.jar check --tantalum.data-dir=/srv/tantalum
```

它用这个版本的内核和钩子把每个会话重放一遍，和记下的结果逐步比较，不一致的会话列出第一处差异（`DIFFERENT yi/s-1: seq 42 commands`）。全部一致时退出码 0，否则 1。只读：不取存储锁、不打开索引库，服务器运行时也能跑；不建模型客户端、不连 MCP。嵌入 Tantalum 的应用有自己的插件时，在自己的 Spring 配置里调 `RecordCheck.run`。

## 运维

**启动。** 启动时比较会话文件和索引，补齐落后的部分；补不上就拒绝启动，提示删掉 `index.db` 重建。最后给索引里没结束的运行收尾，这些会话和平时一样按需从文件载入。日志：

```
handled 2 events left without results by the previous process
index caught up with 3 session files
store ready at /srv/tantalum in 1910 ms: 2 runs left unfinished by the previous process closed as interrupted
```

前两行只在上次没有正常关闭（比如被 `kill -9`）时出现。50 万个事件的数据目录启动约 2 秒，实测数字见 `.spec-workflow/steering/tech.md`。

**崩溃或重启时没结束的运行。** 启动时给它们追加结束事件：已经发出但没有结果的工具调用补一条“结果未知”，运行以“已中断”结束，控制台显示“服务重启，这次运行已中断”。不会自动重新调用模型或重跑工具，因为写操作可能已经生效；用户在同一会话继续提问即可。

**旧数据目录。** 早期版本用单个 `journal.db`。数据目录里有 `journal.db` 而没有 `format.json` 时拒绝启动，不改动任何文件：

```
/srv/tantalum holds an old journal.db and no format.json; this version cannot read it. Point tantalum at a new data directory.
```

旧数据不迁移。用 `--tantalum.data-dir` 指向一个新目录；旧目录不再需要时自行删除。`format.json` 的存储版本比程序新时同样拒绝启动，要升级程序。

更早的存储版本（1：只有事件；2：事件行和结果行分开）也不迁移：数据目录里还有这种会话文件时拒绝启动，提示把 `sessions/` 和 `index.db` 移走；账号、插件开关和用户设置不受影响。没有会话文件时直接升级到版本 3。

**会话常驻内存。** 没有运行、没有排队提交的会话，空闲超过 `idle` 或常驻数超过 `max-resident`（最久没用的先走）时移出内存，下次打开时按它记下的结果载入（1889 个事件的长会话，首屏约 0.6 秒）。回收在处理事件时顺带检查，所以完全没有请求的服务器不会主动回收，常驻数仍受上限约束。

| 配置 | 默认 | 含义 |
|---|---|---|
| `tantalum.residency.idle` | `30m` | 空闲多久移出内存 |
| `tantalum.residency.max-resident` | `1000` | 常驻会话上限 |
| `tantalum.residency.sweep-interval` | `1s` | 两次回收检查的最小间隔 |

**关闭。** 收到 `SIGTERM` 后先拒绝新提交、关掉推送连接，再等在途的模型和工具调用最多 `tantalum.shutdown-wait`（默认 30 秒），到时中断它们；之后关索引和文件。被中断的运行下次启动按上面的规则收尾。

## 记忆

模型可以替每个用户记住跨会话仍然有用的事实：偏好、纠正过的做法、项目事实、外部链接。四层模型里实现了第二层（会话摘要，见下面的“上下文压缩”）、第三层（个人长期记忆）和第四层（共享知识，只读）。

- **存在哪**：每人模式下在 `<workspace>/<用户名>/memory/`，索引 `MEMORY.md` 每条一行，条目 `<name>.md` 带 frontmatter（名称、描述、类型、更新时间）。共用工作目录模式下放到 `<data-dir>/memory/<用户名>/`，这样别人的 `grep` 搜不到。索引以条目文件为准，每次写删后重建。
- **怎么进 prompt**：运行开始时把记忆指引、共享知识、`AGENTS.md` 和该用户的索引依次拼进 system，写进 `ContextAssembled` 事件；回放不读文件。索引在 system 末尾、钩子追加内容之前，prompt caching 的断点整个运行里稳定。条目正文不常驻，模型用 `memory_read` 按需读。新记忆从下一个运行开始生效：会话的 system 和工具在第一个运行时固定（所有模型相同），之后可变内容有变化时追加一条用户消息说明，新工具要到新会话才可见。
- **工具**：`memory_write(name, type, description, content)` 新建或同名覆盖，`memory_read(name)`，`memory_delete(name)`。名称 `[a-z0-9-]{1,64}`，类型 `user`、`feedback`、`project`、`reference`。看起来像密钥、令牌、口令的内容会被拒绝写入。
- **上限**：`tantalum.memory.max-entries`（200）、`max-index-bytes`（16 KB）、`max-entry-bytes`（8 KB）。超限时写入被拒并提示模型先删或合并；不做自动整理。
- **共享知识**：`tantalum.knowledge.dir` 指向管理员维护的目录，其中的 `*.md` 按文件名顺序进所有用户的 system（总字节上限 `tantalum.knowledge.max-bytes`，默认 64 KB）。模型改不了它。
- **边界和删除**：知识、`AGENTS.md`、索引用标签包裹并转义闭合标签，这只降低注入风险，不是保证。记忆变化时只追加变化的部分。删除记忆不会清除 journal 和历史消息里的内容，同一会话的 system 也要到压缩或新会话才更新。
- **界面**：设置 › 记忆：列表、正文、删除（需确认）、手工新增。接口 `GET/POST /api/memory`、`GET/DELETE /api/memory/{name}` 只操作当前登录用户的记忆。

## 附件

提问时可以带图片和文件（`attachments` spec）。控制台里用回形针按钮、拖到页面上或直接粘贴；对话里的图片可以拖回输入框再发一次，不用重新上传。

- **存储**：每个用户自己的 `attachments/<用户>/`，按内容的 SHA-256 存，同样的内容只存一份；别人的附件和不存在一样。图片上传时统一规范化：按照片方向转正、去掉 GPS 等元数据、GIF 只取第一帧，超过 2048×2048 就缩小；已经干净的图片原样保存。只收 PNG、JPEG、WebP、GIF 四种图片，SVG 当普通文件。
- **限制**（都可配置，`tantalum.attachments.*`）：每条消息最多 20 张图片、合计 200 MB；每张最多 50 MB、6400 万像素、单边 65535 px；每条消息最多 20 个文件，每个 50 MB；每个用户合计 5 GB。前端先查一遍，服务器再查一遍。
- **给模型的样子**：图片前面一行说明（名字、哈希、尺寸、只读路径），再是图片本身；文件只给说明和只读路径 `/attachments/sha256-<哈希>`，模型要看时用服务器上的 `read_attachment` 读（不用连电脑）；PDF 在 Claude 上另作为文档整份发出（32 MB、100 页以内）。一次请求里图片合计超过 20 MB（base64）时，从最早的开始换成省略说明，省略过的一直省略，prompt 缓存不会反复失效。
- **模型不收图片时**：OpenAI 兼容的模型默认当作只收文字（`tantalum.openai.vision=false`），带图片的提交返回 400；`read_file` 读到的图片换成占位文字。
- **`read_attachment`、`read_file`**：`read_attachment` 只读本人附件，参数和窗口读同 `read_file`；电脑上的 `read_file` 读到图片时，服务器照附件规则规范化、存进本人附件目录，把图片交给模型；读到 PDF 时不把二进制当文字返回。
- **接口**：`POST /api/sessions/{id}/attachments?name=…`（请求体是原始字节）、`GET /api/attachments/{附件 ID}[?download=1]`；提交接口的请求体加 `attachments: [{id, name}]`，尺寸和大小以服务器存的为准。下载一律 `nosniff` 加沙箱 CSP，非图片一律作为下载，HTML、SVG 不会在页面里执行。
- **没做的**：没有引用的附件怎么清理，等以后做会话删除时一起做（A9）。

## 本机工作区：文件、Git、命令行

服务器上没有读写文件、跑命令的工具（`local-workspace` spec）。这些工具在**用户自己的电脑上**执行：电脑上运行连接程序 `tantalum-connect`，由它主动用 WebSocket 连到服务器（电脑上不开端口），模型调 `read_file`、`git_diff`、`bash` 时，服务器把调用转给这台电脑，执行完再把结果送回来。服务器上唯一读文件的工具是 `read_attachment`，只读本人上传的附件；记忆、待办照常在服务器上。

**能动什么只由电脑这边的启动参数决定**：`--allow` 列出的目录才能进，加 `--allow-write` 才能改文件，加 `--allow-shell` 才能跑命令。服务器没有任何办法改这几项；每个调用到了电脑上，连接程序都自己再查一遍（用户对不对、这一类工具允许没有、路径在不在允许的目录里），不信任服务器的判断。每个调用在终端里打一行：时间、会话、工具、路径或命令原文、结果（`ok`、`denied`、`error`、`cancelled`），不打文件内容和令牌。

### 安装（macOS，Apple 芯片）

1. 拿到安装包 `tantalum-connect-<版本>-macos-arm64.zip`（自己打：`mvn -o -pl tantalum-connect -am package -Pmac -DskipTests`，产物在 `tantalum-connect/target/`）。包里有自带的 Java 运行环境和微软 MXC 的 `mxc-exec-mac`，不用另装 Java。
2. 解压，把 `Tantalum Connect.app` 放进“应用程序”。
3. **第一次打开**：安装包没有签名、没有公证。从浏览器下载的会带隔离标记，系统会拦下并提示“无法验证开发者”。二选一：
   - 终端里去掉隔离标记：`xattr -dr com.apple.quarantine "/Applications/Tantalum Connect.app"`；
   - 或者先在终端运行一次下面的命令，被拦后到“系统设置 › 隐私与安全性”底部点“仍要打开”。
4. 把命令放到 `PATH` 上，插件页给出的命令才能直接运行：

   ```sh
   mkdir -p ~/.local/bin
   ln -sf "/Applications/Tantalum Connect.app/Contents/MacOS/tantalum-connect" ~/.local/bin/tantalum-connect
   echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc   # 已经在 PATH 上就不用
   ```

   `tantalum-connect --help` 列出全部参数。

### 连接

1. 控制台侧栏“更多 › 插件”，“本机工作区”一栏点“连接这台电脑”，选有效期（半小时、1 小时、4 小时、1 天）。页面给出令牌和一行命令，**只显示这一次**；服务器只存令牌的哈希。
2. 在电脑的终端里，到要交给 agent 的目录下运行这行命令，按需改参数：

   ```sh
   TANTALUM_TOKEN=tc_… tantalum-connect --server http://127.0.0.1:8091 --allow . --allow-write --allow-shell
   ```

   令牌只从环境变量 `TANTALUM_TOKEN` 读；不设就在启动后按提示粘贴（不显示）。**不要写在命令行参数里**（同一台电脑上的其他程序看得到参数），写了连接程序拒绝启动。令牌不写进任何文件。
3. 启动说明写明连到哪、允许了什么、命令行能不能用以及原因；插件页 2 秒内显示“已连接”、电脑名、目录、权限和到期时间。下一次提问开始，模型就有这些工具，对话里这一轮上方显示“工具变化：新增 …”。
4. 有效期内断网会自动重连（1、2、4 … 30 秒）。到期、在插件页点“撤销”、同一个账号从另一台电脑连上时，连接程序写明原因退出（退出码 2）；下一次提问显示这些工具不能用了。按 Ctrl+C 断开，正在跑的命令连同它启动的进程一起结束。

一个账号同一时间只连一台电脑；这个账号的所有会话共用它，同时最多执行 4 个调用。服务器只绑 `127.0.0.1` 时只有同一台电脑能连；要从别的电脑连，在前面放 HTTPS 反向代理，`--server` 写 `https://` 地址。

### 参数和风险

| 参数 | 意思 | 风险 |
|---|---|---|
| `--server URL` | 服务器地址，默认 `http://127.0.0.1:8091` | 不是本机时用 `https://`，否则令牌和文件内容明文经过网络 |
| `--allow 目录` | 允许 agent 进的目录，可以写多个，第一个是主目录（相对路径按它解析，`AGENTS.md` 从这里读，是 Git 仓库时才有 Git 工具） | agent 能读里面的所有文件，包括 `.env` 这类密钥文件；不要给整个家目录 |
| `--allow-write` | 允许 `write_file`、`edit_file`，命令也能写这些目录 | 能改、能删允许目录里的任何内容；用 Git 管着的目录出错了好恢复 |
| `--allow-shell` | 允许 `bash`，在 MXC 沙箱里执行 | 命令能读允许目录、联网（除非 `--no-network`），能用本机的工具链；见下面“沙箱拦什么” |
| `--allow-read 目录` | 命令额外能读（只读）的目录，如 `~/.m2`、另装的 JDK | 只给命令，文件工具不能进 |
| `--env 名字` | 把这台电脑上的这个环境变量交给命令，可以写多个（如 `--env JAVA_HOME`） | 名字里有 `KEY`、`TOKEN`、`SECRET`、`PASSWORD` 的一律拒绝；其他变量的值模型能读到 |
| `--no-network` | 命令不能联网 | 下载依赖、访问内网的命令会失败 |
| `--name 名字` | 插件页和 system 里显示的电脑名，默认主机名 | |
| `--mxc 路径` | 用这个 `mxc-exec-mac`，不用包里的 | 只在调试或不用安装包时用 |

### 工具

- **读文件**（`read_file`、`list_dir`、`find_files`、`grep`）：路径相对主目录或写绝对路径，解析符号链接之后必须在某个允许的目录里，越界和不存在用同一句话拒绝。`read_file` 按行窗口读，`offset`（从 1 开始）和 `limit`；默认最多 2000 行或 50 KB，没读完时结尾写明下一次从哪一行接着读；超过 2000 字符的行截断并注明；64 MB 以内的文件不会整个进内存，二进制和不是 UTF-8 的文件拒绝。读到图片时电脑送回原始字节，服务器照附件规则规范化、存进本人附件目录再交给模型。`list_dir` 列一层（最多 500 项），`find_files` 按 glob 找（最多 1000 个，跳过 `.git`、`node_modules` 等，不跟符号链接）。
- **改文件**（`write_file`、`edit_file`，要 `--allow-write`）：先读后写，覆盖或修改已有文件前这个会话必须读过它，而且之后没被改过。“读过”记在连接程序的内存里，只留最近 64 个会话；连接程序重启或会话太久没用要重读。先写临时文件再改名，保留原来的权限、CRLF 换行和 BOM；单次最多 10 MB。`edit_file` 逐字匹配，默认必须正好出现一次，多处时用 `replace_all`。
- **Git 查询**（`git_log`、`git_show`、`git_diff`、`git_blame`、`git_branches`，主目录是 Git 仓库时才有）：调本机的 `git`，只读，不碰索引锁；只接受路径、提交号、行范围、条数，以 `-` 开头的提交号拒绝；输出最多 2000 行或 50 KB。
- **命令行**（`bash`，要 `--allow-shell` 而且 MXC 可用）：参数 `command`、`timeout`（秒，默认 120，最多 600）、`workdir`（默认主目录，必须在允许的目录里）。标准输出和错误输出合在一起，只留最后 2000 行或 50 KB，截掉时写明；退出码不是 0 算错误并写明；超时、取消、断开时结束整个进程树。每次调用一个临时目录（`TMPDIR`），结束后删掉。
- **显示**：对话页上 `edit_file` 显示改动前后的差异，`write_file` 显示字节数和前 20 行，`read_file` 显示读了哪几行；轨迹页检查器里 `edit_file` 有“差异”页签。

### 沙箱拦什么、不拦什么（`bash`）

命令在微软 MXC（`microsoft/mxc` v1.0.1）的 `mxc-exec-mac` 里执行，它用 macOS 自带的 Seatbelt，规则从“全部拒绝”开始。连接程序启动时先检查 MXC（`--dry-run` 检查规则，再真跑一次 `/usr/bin/true`）；找不到或检查不过，`bash` 就不出现，启动说明和插件页写明原因，读文件、改文件照常能用，**不会退回到不隔离直接跑**。

- **能读**：系统目录（`/usr`、`/bin`、`/System`、`/Library` 等，MXC 自带）、允许的目录、`/opt/homebrew`、`/usr/local`、`--allow-read` 的目录。
- **读不到**：家目录里的其他东西（`~/Documents`、浏览器资料、`~/.gitconfig` 等）；`~/.ssh`、`~/Library/Keychains` 就算在允许的目录里也读不到。
- **能写**：有 `--allow-write` 时只能写允许的目录和这次的临时目录；没有就只能写临时目录（结束后删掉）。
- **网络**：默认放开，`--no-network` 整体关掉。MXC 在 macOS 上**只能整体开关，不能按网址放行**。不管开没开，**都连不上本机的端口**（`localhost`、本机的局域网地址）：命令碰不到 Tantalum 自己的接口，也碰不到本机的数据库、开发服务器；要在命令里起服务再自己连（比如测试里起的嵌入式服务器）也连不上。
- **环境变量**：命令拿不到连接程序的环境变量（包括 `TANTALUM_TOKEN`），只有 `PATH`、`HOME`（连接程序自己的空目录，只读）、`TMPDIR`、`LANG` 和 `--env` 放行的。
- **不拦的**：允许目录里的东西随便读（含 `.env`）；网络开着时能把读到的内容发出去；能用本机装的所有工具链。MXC 清理进程是尽力而为：一直在飞快地起新进程的命令，极少数情况下可能漏掉一个（MXC 文档 “Process cleanup › Limits”）。

Maven、Gradle 这类要读 `~/.m2`、`~/.gradle` 的：加 `--allow-read ~/.m2` 并用离线模式（`mvn -o`），或者把它加成允许目录（`--allow ~/.m2`，有 `--allow-write` 时可写；注意文件工具也能读它）。Java 的 `user.home` 取的是真实家目录，不受 `HOME` 影响。JDK 不在 `/opt/homebrew`、`/Library/Java` 下时用 `--allow-read` 加上它，再 `--env JAVA_HOME`。

### 插件页的开关

插件页在侧栏“更多 › 插件”，每个用户自己管自己的开关，存在数据目录的 `users.db`，不进 journal；默认全开。

- **本机工作区**：总开关，下面读文件、Git 查询、改文件、命令行四个子开关。总开关关着、电脑没连上、或者连接程序没给对应的 `--allow-*` 时，这些工具一个也没有；子开关开着但连接程序没允许时写明“连接程序没有允许”，MXC 不可用时写明原因。
- **其他能开关的**：`read_attachment` 和记忆工具；每个 MCP 服务器（关掉等于关掉它的全部工具）和其中每个工具；每个钩子插件（同一个插件的钩子、插件工作和工具后处理一起开关，页面上列出它挂在哪些位置）。策略类插件（钩子声明了 `mandatory()`，如额度）显示为“必须开启”，不能关。
- **什么时候生效**：打开的工具、连上的电脑从下一次提问开始可用，在同一个会话里也是；关掉的立即不能再调用，模型这时再调会收到“已在插件页关闭”；运行中途电脑断开，再调会收到 “The local workspace is not connected.”，调用途中断开收到 “The computer disconnected. Whether this operation finished is unknown.”，不自动重发。运行进行中不换名单。
- **怎么做到不破坏思考签名**：Claude 把历史里的思考内容和当时的工具列表绑定，直接改工具列表会让新账号收到 400。所以会话的工具列表从一开始就是全集（本机工作区的工具定义固定，不随电脑变；目录和权限写在 system 里会变的“本机工作区”一段），没开的工具标为延后加载（模型看不见）；开关变了，就在下一次提问后追加一条系统消息写明新增和移除了哪些（`tool_addition`、`tool_removal`，beta `mid-conversation-tool-changes-2026-07-01`）。支持的 Claude 模型见 `ClaudeRequest.TOOL_CHANGE_MODELS`；不支持的模型打开的工具要等新会话，插件页会写明。OpenAI 兼容的模型直接发现在开着的工具，变化另写一段文字说明。
- **过滤只有一个入口**：工具、工具后处理、插件工作只经 `PluginGate` 调用，它在调用前按当前用户的开关查一次；内核钩子只经 `HookChain`，按会话记下的名单过滤。两条 ArchUnit 规则钉住，绕过的代码构建失败。
- **接口**：`GET /api/plugins`、`PUT /api/plugins/{tool|mcp|plugin|local}/{名字}`（请求体 `{"enabled": true}`）；本机工作区另有 `GET /api/local`（开关和连接状态）、`POST /api/local/tokens`（`{"validity": "30m" | "1h" | "4h" | "1d"}`，返回令牌和命令）、`DELETE /api/local/tokens`（撤销全部令牌并断开）。都只操作当前登录用户的。

实测（细节在 `.spec-workflow/steering/tech.md`）：同一台电脑上 `read_file` 经过连接程序比直接执行 P95 多 0.94 ms（要求 ≤ 20 ms）；安装包里的连接程序空闲时 114 MB（要求 ≤ 150 MB）；`bash true` 一次往返约 28 ms，主要是 MXC 启动沙箱。

## 上下文压缩

长会话不会因为上下文满而失败。模型调用前，内核按估算判断会话有没有越过阈值；越过了就先发一次禁用工具、限长的摘要调用，用摘要替换历史开头，再发原来的调用。所有模型走同一条路。

- **窗口**：每个模型用它自己的窗口。启动时和每 10 分钟从模型那边读：Claude 读 Models 接口的 `max_input_tokens`（开着备用模型时取主模型和备用模型中最小的），本地服务读 `/v1/models` 里的 `meta.n_ctx`、`max_model_len` 或 `context_length`；模型报上下文超长时再读一次。读不到才用配置的 `context-window`。设置页“模型”一栏写明窗口从哪来。
- **压缩点**：每个用户在设置页“模型”一栏选 50%、70%、85% 或用满（默认用满），压缩点 = min(窗口 × 百分比, 窗口 − max(`reserve-tokens`, 该供应商的 `max-tokens`))。从下一次提问开始生效，正在进行的对话也是；只决定什么时候压缩，不改发给模型的内容。状态栏到压缩点的 90% 变色。
- **规则**：保留最近约 `keep-recent-tokens`（不超过阈值的四分之一）的消息，其余交给模型总结；一次调用前最多压缩一次；摘要调用可重试，摘要帮不上忙或失败时，如果裁剪工具结果有进展就只裁剪；都不行才放弃，本运行不再自动压缩；模型报上下文超长时只留最新一段压缩并重试一次。压缩后 system 和工具重新组装。完整规则在 `.spec-workflow/specs/context-compaction/requirements.md`。
- **历史只追加**：journal 和对话页始终是全部原文，压缩只换模型看到的视图；对话页在压缩处有分隔线，点开能看摘要。保留段里的推理块会去掉，过大的工具结果首尾各留一半。
- **配置**：`tantalum.<provider>.context-window`（读不到模型的窗口时用，为 0 时不压缩）；`tantalum.<provider>.bytes-per-token`（估算系数，宁可多算：Claude 1.5、本地 1.75，按实测定）；`tantalum.compaction.window-from-model`（默认 true，测试用小窗口时关掉）；`tantalum.compaction.reserve-tokens`（16384）、`keep-recent-tokens`（20000）、`max-kept-tool-result-bytes`（32 KB）、`max-summarized-tool-result-bytes`（4 KB）、`summary-max-tokens`（8192）。设置每次提问时按当时的窗口和用户的选择重算，记进 journal，回放用记下的那份。
- **手动**：状态栏的压缩按钮，或 `POST /api/sessions/{id}/compact`（可带 `instructions`）；会话运行中或没有可压缩的内容时返回 409。
- **回复被截断**：回复因输出上限结束时运行照常完成，对话页提示“回复被截断”；不自动续写。

实测（细节在 `.spec-workflow/steering/tech.md`）：Claude Sonnet 5.5 严格校验和本地 qwen 用同一个十篇笔记的用例，压缩后暗号和规则全部保留；同一任务开压缩后总 prompt token 从 15.3 万降到 10.3 万。

## MCP

可以连接远程 MCP 服务器，把它们的工具交给模型。只由管理员配置：

```properties
tantalum.mcp.servers.jira.url=https://mcp.example.com/mcp
tantalum.mcp.servers.jira.auth=service         # none 或 service
tantalum.mcp.servers.jira.token-env=JIRA_TOKEN # 令牌只从这个环境变量读
tantalum.mcp.servers.jira.allow=create_issue   # 除只读工具外额外放行
tantalum.mcp.servers.jira.deny=delete_issue    # 优先于一切
```

可选项：`concurrency`（默认 4）、`timeout`（默认且不能超过 `tantalum.tool-timeout`）、`max-result-bytes`（默认 64 KB）、`max-tools`（默认 64）。工具名是 `mcp__<服务器>__<工具>`，调用经过和内置工具相同的钩子、超时、journal 和轨迹。

默认只放行声明了 `readOnlyHint` 的工具。这个声明是服务器自己写的，所以只连可信的服务器。

第一版的限制，部署前要知道：

- **共享身份**：所有用户用同一个凭据，外部系统分不清是谁操作的，只有 journal 能回答。没有每用户令牌。
- **提示注入**：工具结果可能诱导模型。system 里有提醒，但这不是保证；未放行的工具才真正调不到。放行写工具后，没有人工确认这一步。
- **取消和超时**：服务器不一定知道调用被取消，写工具的结果可能未知，系统不自动重试。
- **会话失效**：只有期间没有别的调用才重试一次；有重叠时报告结果未知，因为 SDK 2.1.0 用同一条错误结束所有在途调用，分不清哪个已经执行。
- **不并行**：同一轮的 MCP 工具逐个执行，这样回放不依赖服务器当时的状态。
- **新工具**：已有会话要到压缩之后或新会话才看到。新会话最多等 `tantalum.mcp.startup-wait`（默认 10 秒）让第一次连接结束。
- **不支持**：stdio 本地服务器、OAuth、资源、提示词模板、采样。状态只能看日志，没有界面。

## 写插件

只需要看 `tantalum.shell.plugin`、`tantalum.kernel.hook` 和 `tantalum.value`，示例在 `tantalum-examples`。

| 想做什么 | 实现 |
|---|---|
| 在固定位置判断或拦截 | `KernelHook`，纯函数，返回 `Allow`、`Stop` 或 `NeedWork` |
| 判断时需要 I/O | `KernelHook` 返回 `NeedWork`，再实现 `WorkType` |
| 增加工具 | `Tool`，声明服务端和并发上限；未声明时上限为 1 |
| 修改工具输出 | `AfterToolHook` |

注册成 Spring bean 即可。同一个插件的钩子、工作类型和工具后处理要让 `plugin()` 返回同一个名字（钩子和工作类型默认是 `name()`，工具后处理默认是类名），插件页按它分组开关；`description()` 是插件页上的一句说明。插件里不要直接调别的插件的 `run`：所有调用都经过 `PluginGate`，它负责按用户的开关过滤。

七个钩子（前 6 个是 `KernelHook` 的方法，最后一个是处理器钩子）。`Allow` 带的补丁只在 `beforeModelCall` 生效，其余位置忽略。

| 钩子 | 能返回 | 结果怎么生效 | 补丁 | 示例插件 |
|---|---|---|---|---|
| `beforeModelCall` | `Allow`、`Stop`、`NeedWork` | `Stop` 结束运行；工作做完后再问一次 | 生效 | `DailyQuotaHook` |
| `beforeToolCall` | `Allow`、`Stop`、`NeedWork` | `Stop` 只拦这个调用，模型收到“被拦下”；工作做完后由 `onWorkResult` 决定，不再问这里 | 忽略 | `ReadOnlyHook`、`ToolApprovalHook` |
| `onFailure` | `Allow`、`Stop`、`NeedWork` | 放行交给内核（超长恢复、退避重试）；`Stop` 运行失败；工作做完后重试一次，占用重试次数 | 忽略 | `ProviderBackoffHook` |
| `beforeRunEnd` | `Allow`、`Stop`、`NeedWork` | `Stop` 结束为 STOPPED；工作带 `continueText` 时回到模型，否则再问一次 | 忽略 | `CitationCheckHook` |
| `onWorkResult` | `Allow`、`Stop`、`NeedWork` | 放行回到原处；`Stop` 在工具调用前只拦那个调用，其余位置结束运行；`NeedWork` 再要一轮，记在原等待点 | 忽略 | `ToolApprovalHook` |
| `beforeCompact` | `Allow`、`Stop`（`NeedWork` 当作拒绝） | 拒绝自动压缩：本运行不再自动压缩；拒绝手动压缩：以原因失败；拒绝超长恢复：运行失败 | 忽略 | `PinnedContextHook` |
| `afterToolExecution`（`AfterToolHook`） | 一个 `ToolOutput` | 返回新的就替换结果，原样返回就不变；抛异常时记日志，结果保持上一个 | — | `TruncateOutputHook` |

写钩子时要注意五点：
1. **I/O 只能经 `NeedWork`。** 钩子是纯函数，不读文件、不联网、不取时钟（时间用 `context.at()`）；要做 I/O 就返回 `NeedWork`，由有超时的 `WorkType` 去做。
2. **`onWorkResult` 对所有工作都会调用。** 包括别的插件要的工作，钩子要先按 `work.workType()` 过滤（示例 `ToolApprovalHook`）。
3. **失败的工作结果里没有原来的输入。** 超时、抛异常、连不上时，结果只有错误说明；下一轮要用的信息要由工作在成功结果里带回（示例 `ApprovalWork` 带回工具、参数和次数）。
4. **`onFailure` 要求工作会跳过内置的超长恢复。** 所以上下文超长（`CONTEXT_OVERFLOW`）要放行（示例 `ProviderBackoffHook`）。
5. **工作轮数有上限。** 一个等待点连续要求工作最多 `tantalum.hooks.max-work-rounds` 轮（默认 8，小于 1 拒绝启动），超过时运行以失败结束，原因写明钩子、位置和轮数。`onWorkResult` 接着要的工作记在原来的等待点上；`beforeRunEnd` 的轮数整个运行累计，不清零。插件自己的次数（如审批查询次数）要小于这个上限。

**钩子只能看当前用户的当前会话。** `KernelHook` 拿到的是整个 `KernelState`，但只能读当前运行、当前会话和当前用户的用量（`state.principals()` 里这个用户的那一项）。别的会话可能不在内存里，读它们会让钩子的决定取决于当时哪些会话恰好常驻，检查工具重放时就会报出差异。接口拦不住，靠这条约定；示例钩子都符合。

线程模型：`KernelHook` 在内核的单线程里运行，所有用户的状态只在这一个线程上相遇，钩子不需要考虑并发，但必须是纯函数：钩子的决定记在这一步的结果里，恢复时不再调用；检查工具用新版本重放时会再次调用，结果不同说明规则变了。`Tool`、`AfterToolHook`、`WorkType` 每个是一个单例，会被多个虚拟线程同时调用（不同用户、同一运行里的并行工具），所以它们不能保存可变状态；每次调用的身份和工作目录都在 `ToolContext` 里。

超时与失败：`Tool.run` 和 `WorkType.run` 都受 `tantalum.tool-timeout` 约束，到时线程被中断、结果按失败交回；撤销运行也会中断它们，所以阻塞操作要能响应中断。钩子返回 null 按 `Stop` 处理。按名单放行的策略钩子（如示例 `ReadOnlyHook`）要把只读的 `list_dir`、`find_files` 和三个记忆工具加进名单，否则它们会被拦下；`write_file`、`edit_file` 不在名单里就会被拦，正是只读策略要的；记忆目录是用户自己的，不算对工作目录的写操作。做“检查”的钩子在 `onWorkResult`/`beforeRunEnd` 里看到 `work.failed()` 时不要放行（示例 `CitationCheckHook` 会 `Stop`），否则检查没做成就等于通过。从 `onFailure` 申请的工作完成后再调模型会占用重试次数，失败持续时最多重试 3 次。

## 测试

```sh
mvn clean verify          # 不访问外网，不花钱
(cd console && pnpm test)
(cd console && pnpm e2e)  # Playwright 端到端：自动起脚本化模型的后端（8097）和 vite（5174）
```

`mvn verify` 在集成测试阶段还会跑 `LinkIT`（同一个 JVM 里起服务器和真的连接程序，测令牌、断网重连、被顶替、硬发越权调用被电脑拒）和 `MxcIT`（用真的 `mxc-exec-mac` 跑 `bash`：读不到允许目录外和 `~/.ssh`、没有 `--allow-write` 写不了、`--no-network` 上不了网、连不上本机端口、超时和取消后没有残留进程、环境变量里没有令牌。只在 macOS 上、程序存在时跑，否则跳过并写明原因；程序默认用打安装包时下载校验过的 `~/.cache/tantalum/mxc/1.0.1/mxc-exec-mac`，也可 `-Dtantalum.mxc=路径`），并用打好的 jar 跑 `JarCrashIT`：模型调用和工具调用进行中 `kill -9`，重启后检查已提交的事件不丢、索引补齐、运行收尾、模型和工具没有被再次调用。性能预算用 `mvn -P bench -pl tantalum-examples -am -DskipTests test-compile` 手动跑，超预算时失败。连接程序的往返和内存用 `LinkBench` 手动跑（先打安装包，有包时测包里的连接程序）：

```sh
mvn -o -pl tantalum-connect -am verify -Djacoco.skip=true -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test=LinkBench -Dfailsafe.failIfNoSpecifiedTests=false
```

浏览器和磁盘看到的 JSON（`/messages` 的条目、SSE 消息）由 `WireFormatTest` 逐字节钉住，期望文件在 `tantalum-shell/src/test/resources/wire/`。有意改格式时加 `-Dwire.record=true` 重新生成，并在提交里写明改了什么。

跳号回归：让事件序号每次加 7 再跑一遍全部测试，确认没有代码假设“下一个序号是加一”（运行 ID、命令 ID 和断点续传都从序号来）：

```sh
JAVA_TOOL_OPTIONS=-Dtantalum.test.sequence-step=7 mvn test -Djacoco.skip=true
```

端到端的后端是 `tantalum-examples` 测试目录里的 `E2eServer`（Maven profile `e2e`，模型按关键词回放剧本，不访问外网），账号 `e2e` / `e2e-password`。

真实模型的冒烟测试要显式打开：

```sh
TANTALUM_SMOKE_STRATA=1 mvn -pl tantalum-examples -am verify -Dtest=StrataSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
TANTALUM_SMOKE_CLAUDE=1 mvn -pl tantalum-examples -am verify -Dtest=ClaudeSmokeTest -Dsurefire.failIfNoSpecifiedTests=false   # 需要 key，会产生费用
TANTALUM_SMOKE_CLAUDE=1 mvn -pl tantalum-examples -am verify -Dtest='ClaudeScenarioTest,ClaudeCrashRecoveryTest' -Dsurefire.failIfNoSpecifiedTests=false
```

`ClaudeScenarioTest` 和 `ClaudeCrashRecoveryTest` 用真实的 Claude 走完整的环：八步顺序工具链、thinking 签名跨多轮回传、每一个钩子位置、流式中取消后继续、三个用户同时调用、模型调用进行中杀进程再恢复。一轮约 0.1 美元，结论记在 `.spec-workflow/steering/tech.md`。

第三方依赖和界面参考（DeepSeek Harness）的许可证见 `THIRD_PARTY_NOTICES.md`。

## 能力对照：和 pi、DeepSeek Harness 比

“做完了”一律对着这张表说。每个 spec 完成后更新；`core-skeleton` 和 `console-ui` 两个 spec 的任务已全部完成，但相对目标还缺下面标“没有”的部分。

| 能力 | pi | DeepSeek Harness | Tantalum | 计划 |
|---|---|---|---|---|
| agent 循环、流式、工具调用、并行工具 | 有 | 有 | 有 | |
| 会话持久化、崩溃后恢复 | 有（文件，存消息本身） | 有（事件日志，折叠时不跑钩子） | 有：一步一行（事件和它的结果），恢复只读记下的结果、不重算，内核和钩子改了旧会话也不变；`check` 命令检查新版本会不会改变旧会话；重启后未结束的运行自动收尾（未配对的工具调用补“结果未知”），可在同一会话继续，不自动重新执行 | 不做自动续跑：写操作可能已生效 |
| 钩子 / 扩展点 | 有 | 有 | 有，纯函数钩子 + `NeedWork` | |
| 多用户、登录、每用户目录、入口限额 | 无 | 无 | 有 | |
| 轨迹回放、每次模型调用的完整 prompt | 部分 | 有 | 有：轨迹页（时间概览、记录表、检查器）和统计页签，和 DSH 对等，另加钩子决定和插件工作两类记录，`trace-view` spec | 嵌套子工具、`run_code` 源码视图等 sandbox 之后 |
| prompt caching（Claude） | 有 | 经 pi | 有 | |
| 上下文压缩 | 有 | 有 | 有：自动、超长恢复、手动，所有模型同一套规则，`context-compaction` spec；窗口从模型读，压缩点每个用户在设置页选（pi 和 DSH 画面上都没有），`compaction-threshold` spec | 自定义摘要钩子待定 |
| 启动和内存不随历史增长 | 有（只开一个会话） | 有（会话按需载入 + 每会话投影检查点） | **没有** | `ops-hardening` |
| 长期记忆 | 无 | 没有自带，只有通用 MCP 桥和 3 个可选示例配置 | 有：每用户记忆文件 + 三个工具 + 共享知识层 + 设置面板，`memory` spec | 自动整理未做（决定 M5） |
| 图片和文件附件 | 有（图片、`read` 读图） | 有（图片、文件、PDF、超预算省略） | 有：上传、规范化、每用户去重存储、Claude 图片和 PDF 文档块、OpenAI 兼容 `image_url`、超预算省略、`read_file` 读附件和图片、控制台附件条和灯箱、轨迹显示，`attachments` spec | 无引用附件的清理随会话删除一起做 |
| MCP 接入 | 有（`builtin:mcp`） | 有 | 有：远程 HTTP 服务器、放行名单、共享凭据；没有 stdio、OAuth、每用户令牌 | 每用户令牌待定 |
| 文件读写（窗口读、写、改、列目录、找文件） | 有（`read`、`write`、`edit`、`ls`、`find`） | 有（读、写、改、glob，先读后写） | 有：窗口读取和续读、先读后写、原子写、保留换行和 BOM、对话页差异，`core-tools` spec；`local-workspace` 起在用户电脑上执行，只能进 `--allow` 的目录 | 代码索引 `code-index` spec |
| 工具在用户自己的电脑上执行 | 可换执行方式（例子用 SSH） | 服务器主动 SSH 进去 | 有：连接程序主动连服务器，有期限可撤销的令牌，允许什么只由电脑决定、每个调用电脑再查一遍；文件、Git 查询、`bash`；插件页总开关和子开关，`local-workspace` spec | Windows 连接程序、连多台电脑 |
| `bash` | 有（不隔离） | 有（沙箱） | 有：在用户电脑上的 MXC 沙箱里执行，读不到允许目录外的用户文件、只能写允许目录、网络整体开关、连不上本机端口，沙箱不可用就不提供 | Windows 的 `powershell` |
| 插件页、每个用户开关工具和插件 | 无 | 有（插件页） | 有：内置工具、MCP 服务器和工具、钩子插件，每人自己的开关；会话中途开关从下一次提问生效，不破坏思考签名 | |
| 联网（搜索、抓取网页） | 无，靠 `bash` 跑 curl | 有：`web_search`（Exa、Perplexity、DeepSeek 可换）、`web_fetch` | **没有**专门的联网工具；电脑连上并允许命令行时，模型能在电脑上用 `bash` 跑 curl | `web-tools` spec |
| 操作系统级沙箱 | 无 | 有（`sandbox-local`：Seatbelt、受限令牌、bwrap） | 有，只用于电脑上的 `bash`（macOS 用微软 MXC 的 Seatbelt 后端）；服务器上不再有读写文件、跑命令的工具 | Windows 上换 MXC 的 Windows 后端 |
| 子 agent | 有 | 有 | **没有** | 子 agent spec |
| 技能、计划、定时任务 | 部分 | 有 | **没有** | 未规划 |
| 用户管理界面 | 不适用 | 不适用 | **没有**，只有命令行；会话在内存里，重启后要重新登录 | 未规划 |
| 终端、文件树、浏览器面板 | 无 | 有 | **没有**，`console-ui` 时明确排除 | 未规划 |

顺序（Yi 决定）：`memory`、`mcp-client`、`context-compaction` 已完成；其余顺序待定。
