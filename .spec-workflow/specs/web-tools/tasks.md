# Tasks Document

工作方式：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志。

每个阶段结束：
- 跑全量回归（`scratchpad/regress.sh`：控制台构建、`mvn -o clean verify` 含 `JarCrashIT`、vitest、Playwright）；
- 提交一次；
- 重新打包，复制到 `scratchpad/app/tantalum.jar`，跑 `up.sh` 更新 8091；
- 用一两句话向 Yi 汇报。

构建：任务 1 联网执行一次 `mvn -pl tantalum-shell -am dependency:resolve`（下载 Playwright 1.64.0 约 209 MB），之后一律 `mvn -o … -am`；单独跑某个模块的测试也要带 `-am`，否则用到 `~/.m2` 里的旧包。

提交前扫描密钥。搬过来的测试断言不放宽。安全规则（每个任务都适用）：
- API 密钥、令牌、上游代理的用户名密码不进日志、文件和输出；
- 服务器不为联网写用户文件：浏览器资料在内存里，下载拒绝，截图只进本人附件；
- 联网日志只记主机、端口、原因类别，不记路径、正文、快照、截图；
- 服务器默认只绑 `127.0.0.1`，`Egress` 只听 `127.0.0.1`。

路径前缀：
- `shell/` = `tantalum-shell/src/main/java/tantalum/shell/`
- `shelltest/` = `tantalum-shell/src/test/java/tantalum/shell/`
- `examples/` = `tantalum-examples/src/test/java/tantalum/examples/`
- `console/` = `console/src/`
- `e2e/` = `console/e2e/`

每个任务的 `_Prompt` 开头省略的固定部分：“Implement the task for spec web-tools, first run spec-workflow-guide to get the workflow guide then implement the task: 开始时在 tasks.md 把本任务标成 [-]，做完用 log-implementation 记录，再标成 [x]。”

## 阶段 1：依赖、出口和 `web_fetch`（还不接到 agent）

先把两个新依赖下载好，再做网络出口（网址规则、地址规则、上游代理、过滤代理），最后做 `web_fetch` 本身。这一阶段的类都能单独测，不改 `Engine`，8091 上看不到变化。

- [x] 1. 依赖和两条依赖规则
  - File: tantalum-shell/pom.xml（改）、shelltest/ArchitectureTest.java（改）
  - 联网执行一次 `mvn -pl tantalum-shell -am dependency:resolve`，加 `com.microsoft.playwright:playwright:1.64.0` 和 `org.jsoup:jsoup:1.19.1`（版本写死）；确认 `driver`、`driver-bundle`、`Java-WebSocket` 下载到 `~/.m2`，`gson`、`jspecify` 用已缓存的版本；之后 `mvn -o -q verify` 能过
  - ArchitectureTest 加规则 1、2：只有 `tantalum.shell.browser` 依赖 `com.microsoft.playwright..`（写法同 `mcpSdkStaysInItsPackage`）；只有 `tantalum.shell.fetch` 依赖 `org.jsoup..`
  - 测试：两条规则各临时加一个违规引用，确认失败后删掉
  - _Requirements: 设计“Technical Standards”“Project Structure”规则 1、2_
  - _Prompt: Role: Java 构建 | Task: 下载并加入 Playwright 和 jsoup，加两条依赖规则 | Restrictions: 只联网这一次；不加别的第三方依赖；不改其他模块的 pom | Success: mvn -o -q verify 通过，两条规则的变异都被抓到_

- [x] 2. `WebUrl` 和 `AddressRules`
  - File: shell/net/WebUrl.java、shell/net/AddressRules.java（新），shelltest/net/WebUrlTest.java、AddressRulesTest.java（新）
  - `WebUrl.check`：只收 `http`、`https`，不带用户名密码，最长 2048 字符，三种错误消息照设计原文
  - `AddressRules(blockPrivateNetwork)`：`ownPort(int)`；`blocked(InetAddress, port)` 返回 `Optional<Reason>`：不论开关，自己的端口加本机地址（回环、`0.0.0.0`/`::`、本机网卡地址，缓存 60 秒）拦为 `OWN_PORT`；开关开时拦 W6 列出的地址段为 `PRIVATE_NETWORK`，IPv4 映射的 IPv6 先还原；`describe` 两句话照设计原文
  - 测试：`WebUrl` 表驱动 `ftp:`、`file:`、`javascript:`、`user:pass@`、2048 和 2049 字符；`AddressRules` 表驱动，开关开、关各一遍：`10.0.0.1`、`172.31.255.255`、`172.32.0.1`（不拦）、`192.168.1.1`、`169.254.169.254`、`127.0.0.2`、`0.0.0.0`、`::1`、`fe80::1`、`fd00::1`、`::ffff:10.0.0.1`、`8.8.8.8`；自己的端口在回环上拦、在本机网卡地址上拦、别的端口不拦、开关关着也拦
  - _Leverage: shell/net/Hosts.java_
  - _Requirements: W2, W6, 需求 1.2, 4.1, 4.2, 4.4_
  - _Prompt: Role: Java 网络安全开发 | Task: 实现网址规则和地址规则 | Restrictions: 只拦 W6 列出的段，不多拦；这里不做 DNS 以外的网络访问 | Success: 两个表驱动测试全过_

- [x] 3. `UpstreamProxy`
  - File: shell/net/UpstreamProxy.java（新），shelltest/net/UpstreamProxyTest.java（新）
  - `fromEnvironment(Function<String,String>)` 读 `HTTPS_PROXY`、`HTTP_PROXY`、`NO_PROXY`，大小写都认，空值当没设；SOCKS 或解析不了的值 WARN 一行（不打印值），这个协议直连；`route(host, port, tunnel)` 返回 `Direct` 或 `Via(host, port, credentials)`；回环永远直连；`NO_PROXY` 认主机名、`.example.com` 后缀、`*`
  - 测试：大小写两种；空值；`NO_PROXY` 后缀和 `*`；回环不走代理；SOCKS 改直连；截获日志，WARN 里没有代理网址和用户名密码
  - _Leverage: shell/net/Hosts.java（isLoopback）_
  - _Requirements: W12_
  - _Prompt: Role: Java 开发 | Task: 实现上游代理的选择 | Restrictions: 代理的用户名密码只在内存里，不进日志和 toString | Success: 测试全过_

- [x] 4. 出口 `Egress`
  - File: shell/net/Egress.java（新），shelltest/net/EgressTest.java（新）
  - 照设计“出口”1–8：`ServerSocket` 只听 `127.0.0.1:0`，每条连接一个虚拟线程；请求头最多 16 KB、10 秒；只认 `CONNECT` 和绝对网址的明文 http，其余 400；解析一次、每个地址都过 `AddressRules`，有一个被拦就 403 加 `X-Tantalum-Blocked: private-network|own-port`；连这次解析出来的地址（IPv4 优先，10 秒）；上游代理按 `UpstreamProxy`；CONNECT 双向转发，5 分钟没数据关；明文 http 一条连接只转一个请求（改写请求头、`Connection: close`、去掉 `Proxy-*`，分块正文 411）；最多 512 条连接，再多 503。出口自己的端口也报给 `AddressRules` 当“自己的端口”。`client()` 给一个经出口、不跟跳转、连接超时 10 秒的 `HttpClient`
  - 测试：只绑 127.0.0.1；CONNECT 到本地 https 测试站通；被拦地址 403 带头；**同一条连接上第二个明文请求指向被拦的端口也被拦**（实测漏洞的回归）；假上游代理收到的 CONNECT 主机正确、带 `Proxy-Authorization`；超过 512 条连接回 503；截获日志只有主机、端口、原因，没有路径
  - _Requirements: W6, W12, 需求 4.2, 4.4, NFR Security_
  - _Prompt: Role: Java 网络开发 | Task: 实现进程内的过滤代理 | Restrictions: 只听 127.0.0.1；检查后不再查 DNS；不记路径；不看 TLS 内容 | Success: 测试全过，回归用例钉住明文复用的漏洞_

- [x] 5. `HtmlMarkdown`
  - File: shell/fetch/HtmlMarkdown.java（新），shelltest/fetch/HtmlMarkdownTest.java（新）
  - `of(Document)`：先删 `script`、`style`、`noscript`、`template`、`iframe`、`object`、`embed`，`hidden`、`aria-hidden="true"`、`input[type=hidden]`，行内 `display:none`、`visibility:hidden|collapse`；转换：标题 `#`、代码块 ```` ``` ````、列表 `-`、链接和图片转绝对网址、GFM 管道表（单元格里的 `|` 转义）、连续空行压成一个
  - 测试：每种不显示的内容都不出现；标题、相对链接变绝对、嵌套列表、代码块、表格各一例
  - _Leverage: DSH `web/tool-web/src/fetch.ts:25-49` 的规则_
  - _Requirements: W4, 需求 1.1_
  - _Prompt: Role: Java 开发 | Task: 在 jsoup DOM 上写 HTML 转 Markdown | Restrictions: 不引入别的转换库 | Success: 测试全过_

- [x] 6. `Fetcher` 和 `FetcherIT`
  - File: shell/fetch/Fetcher.java（新），shelltest/fetch/FetcherIT.java（新）
  - 设计“一次 `web_fetch`”1–7：每跳 `WebUrl.check` 加 `AddressRules` 事先检查（只为给清楚的原因）；`User-Agent: Tantalum/<版本>`，不发 `Accept-Encoding`、不带 Cookie；最多 5 次跳转；按 `Content-Type` 分 HTML、文本、拒绝（图片和 PDF 提示不同，照“偏离需求的地方”）；流式读满 5 MB 停；整次 30 秒；解码顺序：头里的 charset → jsoup（BOM、`<meta charset>`、`http-equiv`）→ UTF-8；认出 `X-Tantalum-Blocked` 当错误；连接失败只写类别
  - 测试（本地 `com.sun.net.httpserver` 测试站，全程经真实 `Egress`）：5 次跳转成功、6 次报错（1.3）；跳到回环时开关开拦、开关关通（4.3）；`localhost` 域名开关开时拦（4.3）；Shift_JIS 和 GBK 只写在页面里时解对，头里写了以头为准（1.6）；6 MB 在 5 MB 停并写明（1.4）；PDF 和 PNG 被拒、提示不同（1.5）；404、500 是正常结果（1.7）；开关关着时访问设定的“自己的端口”被拦（4.4）；截获日志里没有页面里的标记字符串（NFR Security）
  - _Requirements: W3, W4, W5, W6, W12, W13, 需求 1.1–1.7, 4.3, 4.4, NFR Security_
  - _Prompt: Role: Java 后端开发 | Task: 实现网页下载、跳转、分类和解码 | Restrictions: 只用 Egress.client()，不自己 new HttpClient；常量不做配置；正文不进日志 | Success: FetcherIT 全过_

- [x] 7. `WebFetchTool` 和出网规则
  - File: shell/fetch/WebFetchTool.java（新），shelltest/fetch/WebFetchToolTest.java（新），shelltest/ArchitectureTest.java（改）
  - `name()` `web_fetch`，参数 `{url}` 必填、`maxLength` 2048；`server()` 是 `"web-fetch"`（任务 10 换成 `WebAccess.FETCH` 常量）、`serverConcurrency()` 16、`isConcurrencySafe` 为 `true`；说明文字照设计原文；结果格式：第一行 W9 那句话，然后 URL、Status、Content-Type、正文；超过 50,000 字符只留开头，写明原长度；5 MB 停止那一行；`FetchFailed` 变成错误结果
  - ArchitectureTest 加规则 3、4（规则对 `fetch` 和 `browser` 两个包写好，`browser` 包在阶段 3 才有类）：不调 `HttpClient.newBuilder`/`newHttpClient`、不 new `java.net.Socket`、不调 `URL.openConnection`；不依赖 `java.nio.file.Files`，`BrowserDriver` 例外
  - 测试：第一行是 W9 那句话（1.8）；120,000 字符截到 50,000 并写明原长度（1.4）；404 不是错误结果（1.7）；参数 schema 合法；变异：在 `fetch` 里临时 new 一个 `HttpClient`，确认规则 3 抓到后删掉
  - _Leverage: shell/plugin/Tool.java、shell/plugin/ToolOutput.java_
  - _Requirements: W1, W5, W9, W13, 需求 1.4, 1.7, 1.8_
  - _Prompt: Role: Java 后端开发 | Task: 把 Fetcher 包成 web_fetch 工具并加出网规则 | Restrictions: 这一步不改 Engine，不注册工具 | Success: 测试和 ArchitectureTest 全过_

## 阶段 2：图片、开关、接到 agent 和插件页

先把存图代码抽成 `ToolImages`，让 MCP 图片也能进附件（W8）；再做“联网”这一组开关，把 `web_fetch` 接进 `Engine`，插件页加“联网”一块。阶段结束时 8091 上能用 `web_fetch`（浏览器还没有）。

- [x] 8. `ToolImages`
  - File: shell/attachment/ToolImages.java（新）、shell/local/LocalTool.java（改），shelltest/attachment/ToolImagesTest.java（新）
  - 把 `LocalTool.java:64-91` 的存图代码搬成 `ToolImages(AttachmentStore)` 和 `output(Principal, text, error, List<Image>, name)`、`record Image(mediaType, bytes)`；每张图经 `AttachmentStore.upload` 得到 `ImageRef`，文字后加 `AttachmentText.image(ref)` 那一行；存不下时返回错误结果，沿用 `AttachmentRejected` 的消息。`LocalTool` 改用它
  - 测试：PNG 存进本人附件，结果里是 `ImageRef`；配额满时错误结果写明原因；`LocalToolTest` 照跑，断言不变
  - _Leverage: shell/local/LocalTool.java, shell/attachment/AttachmentStore.java, ImageNormalizer.java_
  - _Requirements: W8, 需求 3.1_
  - _Prompt: Role: Java 后端开发 | Task: 把工具结果的存图代码抽出来共用 | Restrictions: 本机工作区的行为不变；attachment 包不依赖 local、mcp、browser | Success: 新测试和 LocalToolTest 全过，shellPackagesHaveNoCycles 通过_

- [x] 9. MCP 工具返回的图片
  - File: shell/mcp/McpResults.java、McpConnection.java、McpTool.java、McpRegistry.java、shell/boot/Engine.java（改），shelltest/mcp/McpResultsTest.java（改），shelltest/model/OpenAiClientTest.java（改）
  - `McpResults.toOutput` 返回 `Result(text, error, images)`：PNG、JPEG、WebP、GIF 且 base64 是规范写法才收下；否则换成一行 “[`<类型>` image omitted: only PNG, JPEG, WebP and GIF images are kept]”；一个结果最多收 `tantalum.attachments.max-images-per-message` 张，多的换成一行；文字截断不算图片。`McpConnection.call` 返回 `Result`；`McpTool.run` 交给 `ToolImages.output`，名字 `<服务器>-<工具>.<扩展名>`；`McpRegistry` 构造参数加 `ToolImages`
  - 测试：PNG 收下；SVG 换成一行；不规范的 base64 换成一行；超过张数换成一行（3.2）；OpenAI 兼容路线 `vision=false` 时请求里是 `textOnly` 那一行，没有图片数据（3.3，补断言）；`McpIntegrationTest` 用 `TestMcpServer` 返回一张 PNG，存进本人附件
  - _Leverage: shell/mcp/McpResults.java, shelltest/mcp/TestMcpServer.java, DSH `mcp/mcp-client/src/tools.ts:333-397`_
  - _Requirements: W8, 需求 3.2, 3.3_
  - _Prompt: Role: Java 后端开发 | Task: 让 MCP 图片照附件规则进本人附件 | Restrictions: 图片的 base64 不进文字、不进日志 | Success: MCP 相关测试全过_

- [x] 10. `ToolGroup`、`WebAccess` 和 `WEB` 开关
  - File: shell/access/ToolGroup.java、WebAccess.java（新）、shell/access/LocalAccess.java（删）、shell/access/PluginSwitches.java、PluginGate.java、shell/local/LocalWorkspace.java、LocalTool.java、shell/handler/ContextHandler.java、shell/fetch/WebFetchTool.java（改），shelltest/access/WebAccessTest.java（新）、PluginGateTest.java（改）
  - `ToolGroup`：`kind()`、`switches()`（第一个是总开关）、`owns`、`enabled`、`source()`；`LocalAccess` 的常量搬进 `LocalWorkspace`，`LocalWorkspace` 实现 `ToolGroup`；`WebAccess`：`MASTER`/`FETCH`/`BROWSER`、`owns` 按 `server()`、`enabled` = 总开关 × 子开关、`admin()`、`blockPrivateNetwork()`、`NOTE`（设计 `ContextHandler` 那段原文）。`PluginSwitches.Kind.WEB`；`PluginGate` 持有组列表，`toolEnabled`、`overview`、`set`（名字不在组里抛 `UnknownPlugin`）、新增 `source(Tool)`；`ContextHandler.sources()` 改问它；`WebFetchTool.server()` 改用 `WebAccess.FETCH`
  - 测试：总开关 × 子开关 × 管理员关闭的组合表（5.1–5.3）；联网工具不进“内置工具”；`source` 为 `web`；本机工作区的 `PluginGateTest`、`LocalWorkspaceTest` 断言不变；`access` 不依赖 `fetch`、`browser`
  - _Leverage: shell/access/LocalAccess.java, shell/local/LocalWorkspace.java_
  - _Requirements: W10, 需求 5.1, 5.2, 5.3_
  - _Prompt: Role: Java 后端开发 | Task: 把开关组推广成 ToolGroup 并加联网这一组 | Restrictions: 不改开关表结构；本机工作区行为不变 | Success: 测试和 ArchitectureTest 全过_

- [x] 11. 配置和 `Engine` 接上 `web_fetch`
  - File: shell/boot/TantalumProperties.java、Engine.java、TantalumApplication.java（改）、tantalum-shell/src/main/resources/application.properties（改），shelltest/boot/TantalumPropertiesTest.java、shelltest/handler/ContextHandlerTest.java、shelltest/web/ShutdownOrderTest.java（改）
  - `Web(blockPrivateNetwork, Fetch, Browser)`、`Fetch(enabled)`、`Browser(enabled, maxOpen, idleClose, sandbox)`，`maxOpen < 1` 启动失败；`application.properties` 照设计加六行和注释。`Engine`：两项都没开时不起出口；否则 `AddressRules`、`UpstreamProxy.fromEnvironment(System::getenv)`、`Egress.start`、`ToolImages`，开着时注册 `web_fetch`，`WebAccess` 进 `PluginGate`；`fixedNote` = MCP 说明 + `WebAccess.NOTE`；`close()` 关出口；公开 `addressRules()`。`TantalumApplication` 监听 `WebServerInitializedEvent` 设实际端口
  - 测试：`maxOpen=0` 启动失败；`fetch.enabled=false` 时没有 `web_fetch`；system 里有“## Web content”那段；服务器随机端口启动后，`web_fetch` 访问自己的端口被拦（4.4）；关机后出口端口不再接受连接
  - _Requirements: W6, W9, W10, 需求 4.4, 5.3, 2.8_
  - _Prompt: Role: Java 后端开发 | Task: 把联网配置和 web_fetch 接进服务器 | Restrictions: 默认绑定地址不变；Egress 只听 127.0.0.1；代理凭据不进日志 | Success: 测试全过，本地起服务能抓本地测试页_

- [x] 12. 插件页接口
  - File: shell/web/PluginController.java（改），shelltest/web/PluginApiTest.java（改）
  - `GET /api/plugins` 加 `web: {switches, admin, blockPrivateNetwork}`（设计“数据模型”）；两项都被管理员关掉时照样返回；`PUT /api/plugins/web/{name}` 照用，未知名字 404
  - 测试：默认三项都开；关掉 `web-fetch` 后再取是关的，另一个用户还是开的（5.1）；管理员关掉 `browser` 时 `admin.browser=false`（5.3）；未登录 401；未知名字被拒
  - _Leverage: shell/web/PluginController.java 本机工作区那一项_
  - _Requirements: W10, 需求 5.1, 5.3_
  - _Prompt: Role: Java 后端开发 | Task: 在插件接口里加联网这一项 | Restrictions: 不返回代理地址和凭据 | Success: PluginApiTest 全过_

- [x] 13. 插件页“联网”卡片
  - File: console/plugins/WebCard.tsx（新）、console/plugins/PluginsDialog.tsx、console/plugins/usePlugins.ts、console/api/types.ts、console/text/zh.ts（改），console/plugins/WebCard.test.tsx（新）
  - 放在本机工作区下面、内置工具上面；用现有 `Section`、`Row`；开关用 `useTogglePlugin({kind: "web", …})`；不轮询。总开关“联网”加灰色 `web`、说明和内网限制那一行；子开关“抓网页 `web_fetch`”“浏览器”及说明照设计原文；管理员关掉的项禁用并写“管理员已关闭”，两项都关时总开关也禁用。`PluginKind` 加 `"web"`，`PluginsResponse.web`；`zh.plugins.web`
  - 测试：vitest 覆盖默认、关掉一项、管理员关掉一项、两项都关、内网限制开着这几种样子
  - _Requirements: W10, 需求 5.1, 5.3_
  - _Prompt: Role: 前端开发 | Task: 做插件页的联网卡片 | Restrictions: 照现有插件页样式；文字放 zh.ts | Success: vitest 全过_

- [x] 14. 对话页的工具变化
  - File: console/conversation/ToolChangeNotice.tsx、console/text/zh.ts（改），console/conversation/ToolChangeNotice.test.tsx（改）
  - 来源 `web` 的“不再可用”写成 `不再可用 web_fetch、浏览器（11 个工具）（联网已在插件页关闭）`；`browser_*` 合成“浏览器（N 个工具）”；新增的照现有写法；加 `zh.conversation.toolsWebOff`
  - 测试：只关 `web_fetch`；只关浏览器（11 个合成一项）；两样都关；`local` 和 MCP 的现有用例不变
  - _Requirements: 需求 5.2_
  - _Prompt: Role: 前端开发 | Task: 让工具变化提示认识联网来源 | Restrictions: 不改 local、mcp 的现有措辞 | Success: vitest 全过_

## 阶段 3：每人一个浏览器

做 Playwright 驱动的解压和 `browser install` 命令，再做每个用户的浏览器（启动、上限、空闲关闭、崩溃重开、关机），然后是 11 个浏览器工具，接进 `Engine`，最后用真实 Chromium 跑 `BrowsersIT`。阶段结束部署前，先在 8091 的机器上跑一次 `browser install`。

- [x] 15. `BrowserDriver` 和 `browser install`
  - File: shell/browser/BrowserDriver.java（新）、shell/boot/TantalumApplication.java（改），shelltest/browser/BrowserDriverTest.java（新）
  - `ensure()`：把 `driver/package/**` 和本平台的 `driver/<平台>/node`（加执行权限）解到 `<数据目录>/browser/driver-<版本>/`，先解到临时名字再改名，设 `playwright.cli.dir`，删掉别的版本的目录；`install(withDeps)`：先 `ensure()`，再调 `com.microsoft.playwright.CLI` 装 `chromium-headless-shell`，`--with-deps` 时加系统依赖。子命令 `browser install [--with-deps]` 不启动 Spring
  - 测试：解压后目录完整、`node` 可执行；中途失败不留半个目录（临时名字被清掉）；旧版本目录被删；重复 `ensure()` 不重新解压；`browser install` 参数解析，未知参数报错
  - 在 Yi 的 Mac 上联网跑一次 `java -jar … browser install`，装好 Chromium headless shell（约 200 MB），记下用时
  - _Requirements: 设计“`BrowserDriver`”“定下的两件事”1, 需求 6.2_
  - _Prompt: Role: Java 开发 | Task: 解压 Playwright 驱动并提供安装命令 | Restrictions: 只写数据目录下的 browser/ 和 Playwright 的浏览器目录；不写用户文件 | Success: 测试全过，本机装好 Chromium_

- [x] 16. `UserBrowser`
  - File: shell/browser/UserBrowser.java（新）
  - 启动：`BrowserDriver.ensure()`，`Playwright.create()`（`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`）；`chromium().launch()`：headless、`chromiumSandbox=settings.sandbox`、代理是出口、`--force-webrtc-ip-handling-policy=disable_non_proxied_udp`；`newContext()`：`acceptDownloads=false`、`locale=zh-CN`、视口 1280×720。字段：每个会话的当前 `Page`、`ReentrantLock`、最后使用时间；最多 10 个标签页，再开新的关最久没用的；没装浏览器、沙箱起不来时的错误消息照设计“Error Handling”，日志 ERROR 带安装命令或 `sandbox=false` 的提示
  - 测试放在任务 20 的 `BrowsersIT`（要真实 Chromium）；本任务先写一个最小用例：起一个、打开本地页、关掉后没有子进程
  - _Requirements: W7, 需求 2.1, 2.3, NFR Security_
  - _Prompt: Role: Java 开发 | Task: 实现一个用户的浏览器环境 | Restrictions: 浏览器资料只在内存里；不允许下载；代理必须是 Egress | Success: 最小用例在装了 Chromium 的机器上通过，没装时跳过并写明原因_

- [x] 17. `Browsers`
  - File: shell/browser/Browsers.java（新）
  - 构造参数 `Settings(maxOpen, idleClose, sandbox)`、`Egress`、`ToolImages`、`Clock`；`use(Principal, SessionId, Action)`：取得或启动、加锁执行、放锁；计数和占名额在同一把全局锁里，启动在锁外；到上限返回 “All browsers on this server are in use (N of N). Try again in a few minutes.”，不挤掉别人；后台虚拟线程每 30 秒关掉空闲超过 `idleClose` 且锁空着的；`PlaywrightException` 时看 `isConnected()`，断了就关掉这一组、删登记、返回崩溃那句话；调用被打断时锁在 `finally` 放掉；`close()` 关全部；`openCount()`
  - 测试放在任务 20 的 `BrowsersIT`；本任务的单元测试只测不需要 Chromium 的部分：并发 8 个用户、上限 2 时启动数不超过 2（启动换成包内可替换的工厂）
  - _Requirements: W7, 需求 2.4, 2.5, 2.8, NFR Reliability_
  - _Prompt: Role: Java 并发开发 | Task: 管理每个用户的浏览器和全局上限 | Restrictions: 不挤掉别人的浏览器；一个用户的崩溃不影响别人；Playwright 对象只在持锁时调用 | Success: 并发上限的单元测试全过_

- [x] 18. `PageText`、`BrowserTool` 和 11 个工具
  - File: shell/browser/PageText.java、BrowserTool.java、BrowserTools.java（新），shelltest/browser/PageTextTest.java、BrowserToolsTest.java（新）
  - `PageText`：第一行 W9 那句话，Page、URL、Tab，AI 快照（`ariaSnapshot` 的 `AI` 模式），超过 50,000 字符截断并写明原长度；`BrowserTool`：`server()` `WebAccess.BROWSER`、`serverConcurrency()` 64、`isConcurrencySafe` 默认 `false`；错误只给第一行，超时、编号找不到、`ERR_TUNNEL_CONNECTION_FAILED`、下载那几句照设计原文；`browser_navigate` 先 `WebUrl.check` 和地址事先检查。`BrowserTools.all(Browsers)`：设计表里的 11 个工具和参数，`browser_navigate` 说明照原文（Bing 网址、Google 限流），用到编号的说明都写 “refs come from the latest browser_snapshot or action result”；截图经 `ToolImages` 存为 `screenshot-<主机>.png`，结果是 W9 那句话加图片说明行；`browser_evaluate` 结果转 JSON，最多 50,000 字符
  - 测试：正好 11 个工具、名字唯一、没有上传、下载、拖放、`run_code`（2.2）；`browser_navigate` 说明里有 Bing 的网址格式（2.6）；每个 schema 合法；`PageText` 第一行是 W9 那句话、截断和原长度（2.7）；`file:`、`data:`、`javascript:` 的网址被拒
  - _Leverage: @playwright/mcp 的工具名_
  - _Requirements: W1, W7, W9, 需求 2.2, 2.6, 2.7, 3.1_
  - _Prompt: Role: Java 开发 | Task: 定义 11 个浏览器工具和结果格式 | Restrictions: 不提供上传、下载、执行任意 Playwright 代码；快照和截图不进日志 | Success: 测试全过_

- [x] 19. `Engine` 接上浏览器
  - File: shell/boot/Engine.java（改），shelltest/web/ShutdownOrderTest.java、shelltest/ArchitectureTest.java（改）
  - `browser.enabled` 时 `BrowserTools.all(new Browsers(...))` 注册；`close()` 先停处理，再 `browsers.close()`，再关出口；确认规则 3、4 对 `browser` 包生效，`BrowserDriver` 是 `Files` 的唯一例外
  - 测试：`browser.enabled=false` 时没有 `browser_*`；关机顺序：浏览器先于出口关闭；变异：在 `browser` 的别的类里临时用 `Files`，确认规则 4 抓到后删掉
  - _Requirements: W10, 需求 2.8, 5.3, 设计规则 3、4_
  - _Prompt: Role: Java 后端开发 | Task: 把浏览器工具接进服务器 | Restrictions: 浏览器懒启动，服务器启动时不起 Chromium | Success: 测试和 ArchitectureTest 全过_

- [x] 20. `BrowsersIT`
  - File: shelltest/browser/BrowsersIT.java（新）
  - 真实 Chromium、本地测试站、真实 `Egress`；没装浏览器时跳过并写明原因（同 `MxcIT`）。用例：A 设 Cookie 后 B 看不到 Cookie 和 A 的标签页（2.1）；快照有 `[ref=…]`，按编号输入、点击后结果对（2.2）；点下载链接报错，临时目录、数据目录、`~/Downloads` 没有新文件（2.3）；空闲设 2 秒，到期后子进程没了，再调用重开、Cookie 没了（2.4）；上限 2 时第三个用户得到“都在用”、前两个照常（2.5）；杀掉一个用户的 Chromium 主进程，这次得到崩溃说明、下次正常、另一个用户不受影响（NFR Reliability）；`close()` 后没有子进程（2.8）；截图进本人附件、结果里是 `ImageRef`（3.1）；开关开时打开回环测试页被拦、关着能打开，测试页里指向“自己端口”的图片被出口拦下（看出口的计数）（4.1、4.2、4.4）；同一用户两个会话各有自己的当前页
  - _Requirements: 需求 2.1–2.5, 2.8, 3.1, 4.1, 4.2, 4.4, NFR Reliability_
  - _Prompt: Role: Java 测试 | Task: 用真实浏览器测隔离、生命周期和地址规则 | Restrictions: 不访问外网；测试结束不留子进程和文件 | Success: BrowsersIT 在 Yi 的 Mac 上全过_

## 阶段 4：收尾、去掉临时浏览器、8091 演示

补链路测试和控制台端到端，量 NFR 的数字，写文档，去掉 8091 上临时接的 `@playwright/mcp`，最后用真实 Claude 在 8091 上做一次完整的演示。

- [x] 21. 链路测试和会话文件
  - File: examples/WebChainTest.java（新）、examples/E2eServer.java（改）
  - `E2eServer` 带一个本地测试页和一个返回图片的假 MCP 工具。脚本化模型：在插件页关掉“浏览器”，下一次提问出现工具变化，浏览器工具不再可用，来源 `web`（5.2）；调 `browser_take_screenshot` 和假 MCP 工具，会话文件里有 `ImageRef`、没有 `iVBORw0K`（3.4、W11）；调 `web_fetch` 后会话文件里有正文，回放时不再联网
  - _Requirements: W11, 需求 3.4, 5.2_
  - _Prompt: Role: Java 测试 | Task: 写联网的链路测试和会话文件检查 | Restrictions: 不访问外网 | Success: 测试全过_

- [x] 22. 控制台端到端
  - File: e2e/plugins.spec.ts（改）、e2e/web-fetch.spec.ts（新）、examples/E2eServer.java（改，按需加启动参数）
  - 插件页有“联网”一块；关掉“抓网页”后刷新还是关的，换一个用户还是开的（5.1）；`E2eServer` 用 `tantalum.web.browser.enabled=false` 起时，“浏览器”一行禁用、写着“管理员已关闭”（5.3）；脚本化模型调 `web_fetch` 抓 `E2eServer` 自带的测试页，对话页的工具结果里有正文和第一行那句话（1.1、1.8）
  - _Requirements: 需求 1.1, 1.8, 5.1, 5.3_
  - _Prompt: Role: 测试开发 | Task: 写联网的端到端测试 | Restrictions: E2E 不访问外网 | Success: Playwright 全过_

- [x] 23. NFR 测量
  - File: shelltest/fetch/FetcherIT.java、shelltest/browser/BrowsersIT.java（改）、.spec-workflow/steering/tech.md（改）
  - `web_fetch`：100 KB HTML 从收到正文到结果，100 次取 P95 ≤ 200 ms；浏览器：1 个空闲 ≤ 300 MB，4 个同时开着本地大页面合计 ≤ 1.2 GB，第一次打开 ≤ 5 s；macOS 用 `footprint`，Linux 用 `/proc/<pid>/smaps_rollup` 的 PSS；结果写进 tech.md
  - _Requirements: NFR Performance_
  - _Prompt: Role: 测试开发 | Task: 量联网的处理时间、内存和启动时间 | Success: 数字达标并记进 tech.md_

- [x] 24. 说明文档和许可证
  - File: README.md、.spec-workflow/steering/tech.md、.spec-workflow/BACKLOG.md、THIRD_PARTY_NOTICES.md（改）
  - README 新的一节“联网”：`browser install`（约 200 MB）、Linux `--with-deps`、中文字体 `fonts-noto-cjk`、不用装 Node；`tantalum.web.*` 每一项的含义和默认值；风险（内网默认能访问和 `169.254.169.254`、自己的端口永远拦、上游代理时 DNS 由代理解析、上限和内存、沙箱默认开及关掉的后果、网页内容只标明不过滤、登录状态 10 分钟或重启就没了、正文和快照进会话文件）；插件页开关；“能力对照”表联网一行改成现状。tech.md 依赖表加 Playwright、jsoup 两行，Decision Log 加“选 A 不选 B”。THIRD_PARTY_NOTICES 加 Playwright、jsoup、`driver-bundle` 里的 Node。BACKLOG“联网”“MCP 工具结果里的图片”两行改成完成，写 spec 名
  - _Requirements: 需求 6.2_
  - _Prompt: Role: 技术写作 | Task: 写联网的说明、依赖和许可证 | Restrictions: 不写真实的代理地址、密钥 | Success: Yi 照着能在一台新服务器上装好联网_

- [x] 25. 去掉 8091 的临时浏览器
  - File: scratchpad/app/start.sh、scratchpad/app/up.sh（改）
  - `start.sh` 去掉五个 `--tantalum.mcp.servers.playwright.*` 参数；`up.sh` 不再起 `scratchpad/pwmcp/start.sh`、不再等 8931，`pkill @playwright/mcp` 那一行留着；部署后确认 8931 没有监听、服务器没有连 8931，插件页没有 `playwright` 这个 MCP 服务器。旧的 `/Users/kangyi/tantalum-workspace/yi/.browser` 先不删，问 Yi
  - _Requirements: 需求 6.1_
  - _Prompt: Role: 运维 | Task: 去掉临时接的共用浏览器 | Restrictions: 不删 Yi 的旧快照和截图；不动别的启动参数 | Success: 8091 只用本 spec 的联网工具_

- [ ] 26. 8091 真实演示
  - 8091 上用真实 Claude 问“DeepSeek 最近发的 super agent 文章讲了什么”：模型用浏览器打开 `https://www.bing.com/search?q=…` 读结果页，找到文章链接，用 `web_fetch` 读文章，再截一张图描述页面（需求的例子表）；看对话页的工具结果第一行是 W9 那句话，截图在 Yi 的附件里；截图给 Yi；报告 Claude 用量（上下文、输出、缓存命中、用时）和发现的问题，写进本任务的进度
  - _Requirements: 需求 1, 2, 3 的例子, 需求 6.1_
  - _Prompt: Role: 测试开发 | Task: 在 8091 上做一次真实的联网演示 | Restrictions: 截图和报告里不出现密钥、令牌；不在服务器上留联网的文件 | Success: 模型用 Bing 找到文章、读出正文、看到截图，Yi 收到截图和用量_
