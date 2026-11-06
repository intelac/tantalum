# Tasks Document

工作方式：一次一个任务；行为改动先写会失败的测试；任务做完就标记、写实现日志。

每个阶段结束：
- 跑全量回归（`scratchpad/regress.sh`：控制台构建、`mvn -o clean verify` 含 `JarCrashIT`、vitest、Playwright）；
- 提交一次；
- 重新打包，复制到 `scratchpad/app/tantalum.jar`，跑 `up.sh` 更新 8091；
- 用一两句话向 Yi 汇报。

构建：不加新依赖，一律 `mvn -o … -am`；单独跑某个模块的测试也要带 `-am`，否则用到 `~/.m2` 里的旧包。

同时在做的 spec（`web-tools`、`model-picker`）也在加中文文字（I17）：合并时 `tsc` 报 `en.ts`、`ja.ts` 缺键，就照术语表补上英文和日文，不许用 `as` 绕过类型。

规则（每个任务都适用）：
- 给模型的文字一个字不改：system、工具说明、工具结果、`ToolChanges`、压缩提示词、不可信内容的说明；
- 会话文件格式不改；
- 错误的 `params` 只放数字和名字，不放路径、令牌、用户的内容；
- 服务器日志不翻译；
- 已有测试的断言不放宽：中文断言照旧用中文跑（I16）。

路径前缀：
- `console/` = `console/src/`
- `e2e/` = `console/e2e/`
- `value/` = `tantalum-value/src/main/java/tantalum/value/`
- `valuetest/` = `tantalum-value/src/test/java/tantalum/value/`
- `shell/` = `tantalum-shell/src/main/java/tantalum/shell/`
- `shelltest/` = `tantalum-shell/src/test/java/tantalum/shell/`
- `local/` = `tantalum-local/src/main/java/tantalum/local/`
- `connect/` = `tantalum-connect/src/main/java/tantalum/connect/`
- `connecttest/` = `tantalum-connect/src/test/java/tantalum/connect/`
- `examples/` = `tantalum-examples/src/test/java/tantalum/examples/`

每个任务的 `_Prompt` 开头省略的固定部分：“Implement the task for spec console-i18n, first run spec-workflow-guide to get the workflow guide then implement the task: 开始时在 tasks.md 把本任务标成 [-]，做完用 log-implementation 记录，再标成 [x]。”

## 阶段 1：控制台的底座（还只有中文）

先把“当前语言”和“渲染时取字典”搭好，58 个文件都改成经 `useText()` 取文字，日期数字换成 `Intl`，修掉写死的文字，加上源码扫描。这一阶段只有中文一份字典，8091 上看起来不变，插件页的内置工具变成中文。

- [x] 1. `language.ts` 和 `<html lang>`
  - File: console/text/language.ts（新）、console/index.html（改）、console/test/setup.ts（新）、console/vite.config.ts（改），console/text/language.test.ts（新）
  - `Language`、`LANGUAGES`、`NAMES`、`detect(tags)`（先整个标签、再主语言，都没有 `en`）、`htmlLang`、`intlLocale`、`readCached`/`cache`/`clearCache`（键 `tantalum.language`，经 `ui/storage.ts`）；`index.html` 去掉写死的 `lang="zh-CN"`，内联脚本按“缓存 → `navigator.languages` → en”写 `lang`；`test.setupFiles` 指向 `test/setup.ts`，每个测试前设成中文（这一步先只写 `document.documentElement.lang`，任务 2 再接上 `setLanguage`）
  - 测试：`detect` 表驱动（`ja-JP`、`zh-TW`、`["fr-FR","ja"]`、`fr`、空、`EN-gb`）；把内联脚本的函数体抽出来，用同一组输入跑，结果和 `detect` 相同
  - _Leverage: console/theme/theme.ts、console/ui/storage.ts、index.html 的深浅色脚本；DSH `packages/client/locale/src/client/index.ts:539-555`_
  - _Requirements: I1, I2, I3, 需求 1.1, 1.2, 1.8_
  - _Prompt: Role: 前端开发 | Task: 实现语言检测、缓存和 html lang | Restrictions: 不加依赖；本地存储读写一律经 ui/storage.ts | Success: 测试全过，pnpm build 通过_

- [x] 2. `Dictionary` 类型、`useText()` 和渲染时取字典
  - File: console/text/dictionary.ts、console/text/index.ts（新）、console/text/zh.ts（改）、console/api/types.ts（改，补联合类型），引用 `text/zh` 的 58 个文件（改），console/text/index.test.ts（新）
  - `Dictionary<T>`：字面量放宽成 `string`、函数保留参数类型、元组保留长度、对象逐键递归；`Text = Dictionary<typeof zh>`。`index.ts`：`currentLanguage`、`currentText`、`setLanguage`（写 `<html lang>`、通知订阅者）、`useLanguage`、`useText`（`useSyncExternalStore`）；这一步只注册 `zh`。`zh.ts` 的 `as Record<string, string>` 全部换成 `satisfies Record<联合类型, string>`，`api/types.ts` 缺的联合类型补上（如钩子位置）。组件改用 `useText()`；非组件的 `api/client.ts`、`conversation/runOutcome.ts`、`composer/status.ts`、`attachment/limits.ts` 在调用时用 `currentText()`；模块顶层取字典的 17 个文件（`trace/*.tsx` 的 `const t = zh.trace`、`SettingsDialog.tsx:22-27`、`App.tsx:29-31`、`Sidebar.tsx:43-45`、`AppearancePane.tsx:6-10`、`LocalWorkspaceCard.tsx:16`、`QuestionCard.tsx:7`、`stats/*.tsx`）改成常量只放 `id` 和图标、渲染时查文字。`test/setup.ts` 改调 `setLanguage("zh")`
  - 测试：`index.test.ts`：订阅者在 `setLanguage` 后被通知、`<html lang>` 改了；临时注册一份假字典，渲染 `Sidebar` 后切换，菜单文字跟着变、组件没有重新挂载（用一个 `useRef` 计数）；已有的 vitest 和 Playwright 全部照过，断言一条不改
  - _Leverage: console/theme/theme.ts 的 `useSyncExternalStore` 写法；DSH `ui-trajectory/src/client/locales.ts:203-227`、`ui-slots/src/index.ts:86-87`_
  - _Requirements: I4, I5, 需求 2.1, 2.2_
  - _Prompt: Role: 前端开发 | Task: 搭字典类型和当前语言的订阅，把所有组件改成渲染时取文字 | Restrictions: 不改任何中文文字；不改组件的行为和样式；不许用 as 绕过 Dictionary 类型 | Success: tsc、vitest、Playwright 全过，画面和改之前一样_

- [x] 3. `format.ts`：日期、数字、时长
  - File: console/text/format.ts（新）、console/text/time.ts、console/text/time.test.ts（删，用例并入新测试）、console/trace/format.ts、console/plugins/LocalWorkspaceCard.tsx、console/text/zh.ts 和引用 `time.ts` 的文件（改），console/text/format.test.ts（新）
  - `formatNumber`、`formatBytes`、`clockTime`、`relativeTime`（`Intl.RelativeTimeFormat`，`numeric: "auto"`，分界照 `time.ts:4-14`）、`formatDuration`（文字取 `time.duration`）；locale 用 `intlLocale(currentLanguage())`。删掉 `zh.time` 里的相对时间条目；`process.bytes` 改用 `formatBytes`；`trace/format.ts:5-6` 的“毫秒”“秒”改取字典；`LocalWorkspaceCard.tsx:95` 的 `zh-CN` 改用 `clockTime` 的规则
  - 测试：只注册了中文，先测中文：同一天、跨天、零点是 `00:05` 不是 `24:05`、5 分钟前、昨天、3 天前、`1,234,567`、三档时长；`time.test.ts` 的原用例照搬、期望值不变（“刚刚”改成 `Intl` 的“现在”是唯一的变化，测试里写明）。英文和日文的用例在任务 15、16 加
  - _Leverage: console/text/time.ts；pi `chord/src/surfaces/format.ts:1-47`_
  - _Requirements: I7, 需求 3.1, 3.3_
  - _Prompt: Role: 前端开发 | Task: 用 Intl 按当前语言格式化日期、相对时间、数字，时长取字典 | Restrictions: 代码里不再出现 zh-CN 字面量（language.ts 的 intlLocale 除外）；分界规则不变 | Success: format.test.ts 和已有测试全过_

- [x] 4. 插件页的内置工具和其他写死的文字
  - File: console/plugins/PluginsDialog.tsx、console/text/zh.ts（改），console/plugins/plugins.test.tsx（改或新）
  - `zh.plugins.tools` 补齐全部内置工具（`read_attachment`、`memory_*`、`web_fetch`；浏览器工具看 web-tools 那时的样子，单独列出就加，合在“联网”卡片里就不用）；`toolText` 不再退回 `tool.description`：字典里没有的内置工具显示工具名，并且测试失败；MCP 工具的说明照原样（I8）。插件页的服务器名、状态已经按代码翻译，不动
  - 测试：用 `/api/plugins` 的真实返回（从 `E2eServer` 抓一份存成测试数据）渲染，每个内置工具都有字典里的名字和说明；MCP 工具的说明原样出现
  - _Leverage: console/plugins/PluginsDialog.tsx:64-74_
  - _Requirements: I8, 需求 2.4, 2.7_
  - _Prompt: Role: 前端开发 | Task: 插件页的内置工具全部用字典里的名字和说明 | Restrictions: 不改服务器给模型的工具说明；MCP 的说明不翻译 | Success: 测试全过，8091 插件页没有给模型看的英文说明_

- [x] 5. 写死文字的源码扫描
  - File: console/text/literals.test.ts、console/text/literals.allow.ts（新）
  - 照设计“控制台的四层检查”第 ③ 层：去注释；`text/` 以外不许有中日文字符、不许在 `aria-label`/`title`/`placeholder`/`alt`/`label` 写含字母的字面文字、不许有含字母的 JSX 文字节点、不许引用 `text/zh|en|ja`、不许在模块顶层写 `currentText()`；放行清单每条写文件、文字、理由；扫到少于 50 个文件就失败；失败信息写文件和行号。扫出来的现有问题当场改掉（任务 4 之后应该只剩品牌名、`macOS` 这类）
  - 测试：变异各一次：组件里写 `aria-label="Close"`、写一句中文、`import { zh } from "../text/zh"`、模块顶层 `const t = currentText().trace`，四次都失败，删掉后通过
  - _Leverage: DSH `scripts/verify-client-ui-i18n.ts:1-36`、`:69-75`、`:341-345`_
  - _Requirements: I6, 需求 2.3, 2.7_
  - _Prompt: Role: 前端测试开发 | Task: 写扫描写死文字的 vitest | Restrictions: 不引入解析器依赖（TypeScript 7 没有 JS 语法树接口），用正则，规则写窄；放行清单每条都要理由 | Success: 测试通过，四种变异都被抓到_

## 阶段 2：服务器和本机

服务器记用户的语言，错误改成代码加参数，插件作者的文字改成 `LocalText`；沙箱原因改成代码；连接程序和命令行的终端文字放进三种语言的资源文件。控制台这一阶段只把新字段的类型接上，显示还是中文。阶段结束时 `tantalum-connect --lang ja` 能用。

- [x] 6. `tantalum.value.text`：`Language`、`LocalText`、`Bundle`
  - File: value/text/Language.java、LocalText.java、Bundle.java（新），valuetest/text/LanguageTest.java、LocalTextTest.java、BundleTest.java、BundleChecks.java（新，测试辅助，放进 test-jar 给别的模块用），shelltest/ArchitectureTest.java（改）
  - `Language`：`id`、`parse`、`fromTags`、`fromEnvironment(env, Locale)`（`LC_ALL` → `LC_MESSAGES` → `LANG`，去掉 `.`、`@` 后面，`C`/`POSIX`/空当没设，再看系统语言，最后 `EN`）；`LocalText`：`of(String)`、`of(en, zh, ja)`、`isEmpty`、`in(Language)`、JSON 两种写法；`Bundle.load(baseName, language)` 用 `getNoFallbackControl(FORMAT_PROPERTIES)`，`text(key, params)` 替换 `{name}`；`BundleChecks.assertComplete(baseName, Class<? extends Enum<?>>)`。ArchitectureTest：`java.util.ResourceBundle` 只在 `Bundle` 里用
  - 测试：`fromEnvironment` 表驱动；JVM 默认语言设成 `ja` 时 `Bundle.load(…, EN)` 拿到英文（回退被关掉的回归）；`{name}` 替换、缺参数留原样；`LocalText` 的 JSON；变异：在 shell 里临时用一次 `ResourceBundle.getBundle`，规则抓到后删掉
  - _Leverage: pi `chord/src/node/tui/launcher.ts:472-481`；DSH `packages/util/package-manifest/src/types.ts:44`、`index.ts:476-477`_
  - _Requirements: I10, I12, I14, 需求 5.2, 5.5_
  - _Prompt: Role: Java 开发 | Task: 写三个模块共用的语言、多语言文字和资源文件读取 | Restrictions: 不加依赖；不用 MessageFormat；Bundle 只读类路径，不做别的 I/O | Success: 测试和 ArchitectureTest 全过_

- [x] 7. 用户的语言：设置和登录接口
  - File: shell/settings/UserSettings.java、shell/web/SettingsController.java、AuthController.java（改），shelltest/settings/UserSettingsTest.java、shelltest/web/SettingsApiTest.java、AuthApiTest.java（改），console/api/types.ts、console/api/client.ts（改）
  - `UserSettings.language`/`setLanguage`（空就删行，读不懂当没有，缓存同压缩点）；`GET /api/settings` 加 `language`；`PUT /api/settings/language`，只收 `zh`/`en`/`ja`/`null`；登录和 `me` 返回 `language`。控制台：`User.language`、`UserSettingsView.language`、`client.setLanguage`（这一步不接到界面）。和 `model-picker` 同改 `SettingsController` 时，各加各的字段
  - 测试：默认 `null`；存 `ja` 再取是 `ja`，另一个用户还是 `null`（1.5）；存 `null` 删掉（1.4）；`fr` 回 400（错误代码在任务 8 接上，这里先断言状态码）（1.9）；数据库里写一个 `xx`，读出来是 `null`（NFR Reliability）；登录和 `me` 带 `language`；未登录 401
  - _Leverage: shell/settings/UserSettings.java:30-110_
  - _Requirements: I3, I18, 需求 1.3–1.5, 1.9_
  - _Prompt: Role: Java 后端开发 | Task: 按用户存语言并在设置、登录接口里返回 | Restrictions: 不改表结构；只收三个值 | Success: 测试全过_

- [x] 8. 错误代码
  - File: shell/web/ErrorCode.java（新）、shell/web/WebJson.java、ApiController.java、AttachmentController.java、QuestionController.java、PluginController.java、SettingsController.java、LocalController.java、MemoryController.java、TraceController.java、RunTraceController.java、AuthController.java（改），console/api/errorCodes.json（新），shelltest/web/ErrorCodeTest.java（新）、shelltest/web/*ApiTest.java（改）
  - `ErrorCode` 枚举照设计“错误代码”表（`wire()`、默认状态码）；`WebJson.error(code, diagnostic[, params])` 返回 `error`、`code`、`params`；删掉旧的 `error(status, message)`，所有调用改过来；只有写错的客户端会碰到的约 20 种统一 `request/invalid`，诊断照旧具体；现在是中文的消息（`ApiController.java:165,175,283,286`、`AttachmentController.java:59,64`、`QuestionController.java:57`）改成英文诊断；`AuthController` 的 `error` 值保留、加 `code`。`errorCodes.json` 先写 `errors` 一组（`sandbox` 一组任务 11 加）
  - 测试：`ErrorCodeTest`：`ErrorCode` 的 `wire()` 集合等于 `errorCodes.json`，代码都是 `领域/原因` 的小写连字符形式；每个代码至少有一个接口测试触发，断言状态码和 `code`，`error` 里没有中文字符；现有断言中文消息的测试改成断言代码（不是放宽：断言的是同一件事，换成不随语言变的形式）
  - _Leverage: shell/web/WebJson.java:36-38；DSH `.agents/notes/implemented/architecture/2026-08-28-ctx-remote-failure-vocabulary.md:33,62`_
  - _Requirements: I9, 需求 4.1, 4.4_
  - _Prompt: Role: Java 后端开发 | Task: 给所有接口错误加代码，消息改成英文诊断 | Restrictions: 错误的状态码不变；params 不放路径、令牌、用户内容 | Success: ErrorCodeTest 和全部接口测试通过_

- [x] 9. 附件、记忆、插件的带参数错误
  - File: shell/attachment/AttachmentRejected.java、AttachmentStore.java、ImageNormalizer.java、AttachmentLimits.java、shell/memory/MemoryFormat.java、MemoryStore.java、CredentialGuard.java、shell/access/PluginGate.java、相关控制器（改），对应的测试（改）
  - `AttachmentRejected` 加 `code()`、`params()`，消息改英文诊断（上限字节数、像素、张数作为参数，不预先格式化成“50 MiB”）；`AttachmentLimits` 的张数超限同样；记忆的拒绝原因加代码和参数，`MemoryWriteTool` 给模型的仍是英文原因；`PluginGate` 的未知插件、必须开启的插件带名字
  - 测试：每种拒绝的 `code` 和 `params` 正确（超大文件带 `limit` 字节数、图片边长带 `width`/`height`/`limit`、张数带 `max`/`count`）；`ToolImages` 给模型的错误结果是英文；`MemoryWriteToolTest` 给模型的文字不变
  - _Leverage: shell/attachment/AttachmentRejected.java:6-17_
  - _Requirements: I8, I9, 需求 4.1, 4.2_
  - _Prompt: Role: Java 后端开发 | Task: 让附件、记忆、插件的拒绝带代码和参数 | Restrictions: 给模型的文字不变；状态码不变 | Success: 测试全过_

- [x] 10. 插件作者的文字：`LocalText`
  - File: tantalum-kernel/src/main/java/tantalum/kernel/hook/KernelHook.java、shell/plugin/AfterToolHook.java、WorkType.java、Question.java、shell/access/PluginGate.java、shell/web/PluginController.java、QuestionController.java、问题的推送（改），examples/ 下 8 个插件和 `ApprovalWork`（改），console/api/types.ts（改），相关测试（改）
  - `description()` 返回 `LocalText`，默认空；`Question` 的 `title`、`detail`、`Choice.label` 改 `LocalText`；`PluginGate` 合并说明时用 `isEmpty()`；接口里 `LocalText` 写成字符串或对象。例子插件写齐英、中、日（照术语表，日文用です・ます体）。控制台类型 `LocalText`，显示先只取 `zh`（任务 14 接 `pick`）
  - 测试：`/api/plugins` 里例子插件的说明是带三种语言的对象，`KernelHook` 默认的是空字符串；审批问题的推送和 `GET` 里标题是对象；`hook-coverage` 的钩子矩阵照过
  - _Leverage: DSH `packages/core/tools/src/contracts.ts:361`、`client/locale/src/client/index.ts:215-221`_
  - _Requirements: I10, 需求 4.5_
  - _Prompt: Role: Java 后端开发 | Task: 把插件说明和问题改成多语言文字 | Restrictions: Stop.reason 不改；会话文件不改 | Success: 测试和钩子矩阵全过_

- [x] 11. 沙箱用不了的原因改成代码
  - File: local/sandbox/Unavailable.java（新）、local/sandbox/Mxc.java、Sandbox.java、Status.java、local/wire/Hello.java、connect/Main.java（`NO_SHELL_FLAG`）、shell/web/LocalController.java（改），console/api/errorCodes.json、console/api/types.ts（改），对应测试（改）
  - `Unavailable(Code, params)`，代码照设计的表；`Mxc.firstLine` 的两句改英文（拼进 `detail`）；给模型的那句（`Mxc.java:133`）由英文表生成；`Hello.Shell` 加可选的 `code`、`params`，`reason` 写英文诊断；`/api/local` 的 `link.shell` 转出 `code`、`params`；`errorCodes.json` 加 `sandbox` 一组
  - 测试：非 macOS 条件下 `sandbox/not-macos`；`--mxc` 指向不存在的文件 `sandbox/mxc-invalid` 带 `path`；没有 `--allow-shell` 时 `shell/not-allowed`；老格式的 `Hello`（没有 `code`）照样能连上、`/api/local` 里 `code` 为空；`ErrorCodeTest` 比对 `sandbox` 一组；给模型的那句是英文
  - _Leverage: local/sandbox/Mxc.java:55-125_
  - _Requirements: I12, 需求 4.7, NFR Reliability_
  - _Prompt: Role: Java 开发 | Task: 把沙箱的原因从中文句子改成代码加参数 | Restrictions: 连接协议版本号不变，新字段可选；detail 原样保留 MXC 和系统的输出 | Success: 测试全过，MxcIT 在 Yi 的 Mac 上照过_

- [x] 12. 连接程序的终端文字和 `--lang`
  - File: connect/Words.java（新）、tantalum-connect/src/main/resources/tantalum/connect/Text.properties、Text_zh.properties、Text_ja.properties（新）、connect/Main.java、Link.java（改），connecttest/WordsTest.java（新）、connecttest/MainTest.java（改）
  - 键的枚举和 `say`；把 `HELP`、`Refused` 的消息、令牌提示、启动摘要、`Link` 的重连/连上/关闭消息、沙箱原因搬进资源文件，中文照现在的原文，英文和日文照术语表新写；`Main` 先解析 `--lang`，值不对用英文提示并以 1 退出；没给就 `Language.fromEnvironment`；`HELP` 加 `--lang`。不搬：`Journal` 的记录行、给模型的工具结果
  - 测试：`BundleChecks.assertComplete`；`--lang ja --help` 是日文、`LANG=ja_JP.UTF-8` 时同样（5.2）；`--lang fr` 退出码 1、英文提示（5.3）；没有令牌时的报错三种语言各一遍（5.1）；现有 `MainTest` 中文断言加 `--lang zh` 照过；变异：删掉 `Text_ja` 的一个键，确认失败后恢复
  - _Leverage: connect/Main.java:45-364、connect/Link.java:81-251_
  - _Requirements: I12, I14, I15, 需求 5.1–5.3, 5.5_
  - _Prompt: Role: Java 开发 | Task: 连接程序的终端文字放进三种语言的资源文件，加 --lang | Restrictions: 令牌提示仍不回显；Journal 行格式不变；给模型的文字不变 | Success: 测试全过，Yi 的 Mac 上 tantalum-connect --lang ja --help 是日文_

- [x] 13. 命令行子命令的文字
  - File: shell/boot/CliText.java（新）、tantalum-shell/src/main/resources/tantalum/shell/boot/Text.properties、Text_zh.properties、Text_ja.properties（新）、shell/boot/TantalumApplication.java、shell/auth/UserCommand.java、shell/boot/RecordCheckCommand.java、shell/store/RecordCheck.java、shell/browser/BrowserCommand.java、BrowserDriver.java（改），shelltest/boot/CliTextTest.java（新）、相关测试（改）
  - 同任务 12：`TantalumApplication.main` 分派前解析 `--lang`；`users`、`check`、`browser install` 的输出改用 `CliText`；`UserStore` 的异常由 `UserCommand` 按种类换成文字；启动失败和日志不动。`BrowserCommand` 先看 web-tools 那边的现状，只换输出文字
  - 测试：`BundleChecks.assertComplete`；`users add` 缺密码时 `--lang ja` 是日文（5.4）；`check` 的结果行三种语言各一遍；日志里没有中日文字符（5.6）
  - _Leverage: shell/auth/UserCommand.java:15-54、shell/store/RecordCheck.java:68-85_
  - _Requirements: I13, I14, 需求 5.4–5.6_
  - _Prompt: Role: Java 开发 | Task: 命令行子命令的输出改成三种语言 | Restrictions: 不改子命令的参数和退出码；日志不翻译 | Success: 测试全过_

## 阶段 3：翻译和切换

控制台接上错误代码和 `LocalText`，写英文和日文两份字典，另一个 agent 审校，然后加设置页的“语言”一栏和登录、退出时的切换，最后写端到端。阶段结束时 8091 上能在三种语言之间切换。

- [x] 14. 控制台：错误代码、`LocalText`、沙箱原因
  - File: console/text/errors.ts、console/text/localText.ts（新）、console/api/client.ts、console/text/zh.ts、设计“`errors.ts`”里列的 12 处显示错误的组件、console/plugins/PluginsDialog.tsx、console/composer/QuestionCard.tsx、console/plugins/LocalWorkspaceCard.tsx（改），console/text/errors.test.ts、localText.test.ts（新）
  - `ApiError` 带 `status`、`code`、`params`、诊断；`errorText`：429/503、认得的代码（参数经 `format.ts`）、认不得的代码退回“请求失败（状态码）”加诊断小字；`zh.errors` 按 `errorCodes.json` 的 `errors` 写齐，`zh.plugins.local.sandbox` 按 `sandbox` 写齐（中文照现在服务器上的原文）；显示错误的地方都在渲染时调 `errorText`；插件说明和问题用 `pick`；`LocalWorkspaceCard.refusal` 按 `code`，没有时退回 `reason`
  - 测试：`errors.test.ts`（4.2、4.3）；`localText.test.ts`（4.5）；`zh.errors` 的键等于 `errorCodes.json`；上传超大图片时显示“单张图片不能超过 50 MB”（字节数格式化）；老格式 `link.shell` 只有 `reason` 时照原样显示
  - _Leverage: console/api/client.ts:57-77、console/conversation/runOutcome.ts_
  - _Requirements: I9, I10, I12, 需求 4.2–4.5, 4.7_
  - _Prompt: Role: 前端开发 | Task: 控制台按错误代码和多语言文字显示服务器给的内容 | Restrictions: 错误在渲染时才变成文字；不显示参数以外的服务器原文（诊断小字除外） | Success: 测试全过_

- [x] 15. 英文字典和三份字典的对比测试
  - File: console/text/en.ts（新）、console/text/index.ts（改，注册 en）、console/text/dictionaries.test.ts（新）、console/text/format.test.ts（改）
  - 先写 `dictionaries.test.ts`（设计第 ② 层：路径集合、值的种类、函数参数个数、样例调用、记号不变、英文无中日文字符、`errors` 和 `sandbox` 的键等于 `errorCodes.json`、少于 400 条失败），确认在没有 `en` 时失败；再写 `en.ts`：全部条目照术语表，句首大写其余小写，复数在函数里按数量选，按钮短、提示直接；`format.test.ts` 加英文用例（`5 minutes ago`、`yesterday`、`12:05 AM` 还是 `00:05` 照 `en-US` 的 `h23` 规则、`1,234,567`、`1m 5s`）
  - 测试：上面这些；变异：删掉 `en` 的一条、把一个函数的参数去掉、在英文里留一个中文字，三次都失败
  - _Leverage: console/text/zh.ts；DSH 各包 `locales.ts` 的 en 词典（`ui-trajectory`、`ui-workspace`、`ui-plugin-manager`、`ui-chat`）、`scripts/locale-dictionary-parity.spec.ts:385-404`_
  - _Requirements: I5, I6, I7, I15, 需求 2.1, 2.2, 2.6, 3.2_
  - _Prompt: Role: 前端开发兼英文技术写作 | Task: 写英文字典和三份字典的对比测试 | Restrictions: 不许用 as 绕过 Text 类型；产品名、工具名、参数、配置项原样；术语只用术语表里的说法 | Success: tsc、dictionaries.test.ts、format.test.ts 全过_

- [x] 16. 日文字典
  - File: console/text/ja.ts（新）、console/text/index.ts（改，注册 ja）、console/text/dictionaries.test.ts（改，加日文的放行清单）、console/text/format.test.ts（改）
  - 全部条目照术语表：按钮和标签用名词形，说明和提示用です・ます体，英文单词和日文之间留半角空格，`ブラウザー` 这类带长音的写法全篇统一；和中文完全相同的值只能是放行清单里的（`MCP`、`Git`、`Schema`…）；`format.test.ts` 加日文用例（`5 分前`、`昨日`、`1分5秒`）
  - 测试：`dictionaries.test.ts` 对日文全过；变异：把一条日文改成和中文相同，测试失败
  - _Leverage: DSH 各包 `locales.ts` 的 ja 词典（`ui-trajectory/src/client/locales.ts:428-627` 等）_
  - _Requirements: I5, I6, I7, I15, 需求 2.1, 2.2, 2.6, 3.2_
  - _Prompt: Role: 前端开发兼日文技术写作 | Task: 写日文字典 | Restrictions: 不机翻腔，读起来像日本的开发工具；术语只用术语表里的说法；不许用 as 绕过类型 | Success: tsc、dictionaries.test.ts、format.test.ts 全过_

- [x] 17. 译文审校
  - File: console/text/en.ts、console/text/ja.ts、tantalum-connect 和 tantalum-shell 的 `Text_*.properties`、tantalum-examples 里的 `LocalText`（按审校意见改）
  - 交给一个没参与翻译的 agent：对照中文原文和设计的术语表，逐条看英文和日文：意思对不对、同一概念是不是同一个词、语气（英文句首大写、日文です・ます体）、长度（按钮在 `settings`、`plugins`、`trace.toolbar` 里会不会撑破，看渲染截图）；意见写成清单，逐条改或写明不改的理由，记进实现日志
  - 测试：`dictionaries.test.ts`、`BundleChecks` 照过
  - _Requirements: I15, 需求 2.6_
  - _Prompt: Role: 英日双语审校 | Task: 审英文和日文译文 | Restrictions: 不改中文原文的意思；术语表要改先写明理由 | Success: 审校清单每条都有结论_

- [x] 18. 设置页的“语言”和登录、退出时的切换
  - File: console/settings/LanguagePane.tsx、console/settings/useLanguage.ts（新）、console/settings/SettingsDialog.tsx、console/app/App.tsx、console/auth/LoginPage.tsx（改），console/settings/language.test.tsx、console/text/english.test.tsx（新）
  - “语言”一栏放在“外观”下面，图标 `Languages`；四个选项：“跟随浏览器（检测到的语言名）”、`中文`、`English`、`日本語`；选了先 `setLanguage` 再 `PUT`，失败换回并提示；`me` 和登录返回后照设计“控制台怎么决定用哪种语言”第 3、4 步；退出登录删缓存、回到浏览器的语言；`english.test.tsx` 照设计第 ④ 层
  - 测试：`language.test.tsx`：选英文后标题当场变英文、窗口还开着、输入框里的草稿还在（1.3、I4）；`PUT` 失败换回（Error Handling）；选“跟随浏览器”发 `null`（1.4）；`me` 返回 `ja` 后变日文、写进缓存；401 后删缓存（1.7）；`english.test.tsx` 通过（2.5），变异：在某个组件的模块顶层取一次字典，测试失败后恢复
  - _Leverage: console/settings/AppearancePane.tsx、console/settings/useCompaction.ts；DSH `client/locale/src/client/LanguageRow.tsx`_
  - _Requirements: I2, I3, I4, 需求 1.2–1.7, 2.5_
  - _Prompt: Role: 前端开发 | Task: 做设置页的语言选择和登录、退出时的语言切换 | Restrictions: 切换不刷新页面、不重新挂载；检测到的语言不写回服务器 | Success: vitest 全过_

- [x] 19. 端到端和“给模型的不变”
  - File: e2e/i18n.spec.ts（新）、examples/I18nChainTest.java（新）、examples/E2eServer.java（改，按需）
  - `i18n.spec.ts` 照设计“End-to-End Testing”：`ja-JP` 浏览器的登录页和 `lang`；设置里换 English 当场生效、窗口不关；刷新后第一帧就是 `en`；另一个 `zh-CN` 上下文登录同一用户还是英文；退出后回到浏览器的语言；日文下传超大图片的提示；日文插件页的内置工具。`I18nChainTest`：脚本化模型，三个用户分别设 `zh`、`en`、`ja`，同样的提问，记录的模型请求逐字相同（4.8）
  - 测试：上面这些；已有的 Playwright 测试照 `zh-CN` 跑、断言不改
  - _Requirements: 需求 1.2–1.8, 2.4, 4.2, 4.8_
  - _Prompt: Role: 测试开发 | Task: 写语言切换的端到端测试和给模型不变的链路测试 | Restrictions: E2E 不访问外网 | Success: Playwright 和链路测试全过_

## 阶段 4：收尾和 8091 演示

量 NFR，写文档，最后在 8091 上三种语言各看一遍、截图给 Yi。

- [x] 20. NFR 测量和文档
  - File: .spec-workflow/steering/tech.md、README.md、README.en.md、.spec-workflow/BACKLOG.md（改）
  - 测量：生产构建下，1,000 行的轨迹页切换语言到画面换完 ≤ 300 ms（Playwright 里 `performance.now()` 前后各取一次，取 5 次的最大值）；主包 gzip 后比阶段 1 之前增加 ≤ 30 KB（`vite build` 的输出）；结果写进 tech.md。tech.md 的 Decision Log 加两条：不用 i18n 库、用带类型的字典（I5）；服务器只给代码（I9）。README 和 README.en 加一节“语言”：三种语言、默认跟浏览器、设置页可改、`tantalum-connect --lang`、命令行 `--lang`、给模型的文字不翻译、插件作者怎么写 `LocalText`。BACKLOG“控制台国际化”一行改成完成，写 spec 名
  - _Requirements: NFR Performance, I10, I12, I13_
  - _Prompt: Role: 测试开发兼技术写作 | Task: 量切换时间和包大小，写语言的说明 | Success: 数字达标并记进 tech.md，README 两份都更新_

- [x] 21. 8091 上三种语言各看一遍
  - 部署后用 Yi 的账号在 8091 上依次把语言换成中文、英文、日文，每种截登录页（退出后、浏览器语言设成对应语言）、对话页（有一次带工具调用和压缩的会话）、轨迹页、统计页、插件页、设置页的每一栏；看截图里没有别的语言的产品文字（用户、模型、MCP 写的除外）、按钮没被撑破、日文汉字是日文字形；连接程序在 Yi 的 Mac 上用 `--lang ja` 连一次，终端截图；截图交给 Yi，发现的问题写进本任务的进度，当场能改的改掉再截
  - _Requirements: 需求 6.1, 6.2_
  - _Prompt: Role: 测试开发 | Task: 在 8091 上切换三种语言并截图关键页面 | Restrictions: 截图里不出现令牌、密钥；最后把 Yi 的语言设置恢复成原来的 | Success: Yi 收到三种语言的截图，没有混着的语言_
