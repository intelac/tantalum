# Requirements Document

## Introduction

控制台现在只有中文，可画面上还是“一会儿英语一会儿中文”（Yi 2026-11-06：“菜单一会儿英语一会儿中文的。像 dsh 一样做个国际化吧。”，BACKLOG“控制台国际化”一行）。控制台自己的文字都在 `console/src/text/zh.ts`（约 500 条，58 个文件引用），日期写死 `zh-CN`（`text/time.ts:29-30`、`zh.ts:85`、`plugins/LocalWorkspaceCard.tsx:95`）。英文是从服务器来的：
- 插件页的内置工具只有 4 个有中文名（`zh.ts:541-546`），其余直接显示给模型看的英文说明（`plugins/PluginsDialog.tsx:72-74`），例如 `web_fetch`、浏览器工具；
- 接口错误大约 50 种，英文中文混着，控制台原样显示（`api/client.ts:70-77`；英文如 `ApiController.java:142` “missing text”，中文如 `AttachmentStore.java:123`）；
- 钩子插件的说明、插件提的问题写死中文（`tantalum-examples` 的 8 个插件，`ApprovalWork.java:66-69`）；
- 连接程序 `tantalum-connect` 在终端里只说中文（`Main.java:45-67` 等约 40 处），命令行子命令只说英文（`UserCommand.java`、`RecordCheck.java`）。

本 spec 让这些文字都跟着用户选的语言走：
- **三种语言：中文、英文、日文。** 默认跟浏览器，设置页可以改，按用户记在服务器上；登录页跟浏览器。
- **控制台自己的文字照 DSH 的做法：** 每种语言一份带类型的字典，缺一条、参数对不上，构建就失败；另有测试抓写死在组件里的文字。
- **服务器给画面的文字改成“代码加参数”，** 由控制台按用户的语言说出来；插件作者写的文字可以带三种语言。
- **终端里的文字（连接程序、命令行）** 跟系统语言，或者用 `--lang` 指定。
- **给模型看的文字不翻译：** system、工具说明、“网页内容不可信”那句、压缩的提示词、工具结果都不动。

例子：

| 时间 | 发生了什么 | 结果 |
|---|---|---|
| 09:00 | Yi 的浏览器是中文，打开 8091 | 登录页、对话页、插件页都是中文；插件页里 `web_fetch`、浏览器工具也是中文名和中文说明 |
| 09:10 | 一位日本同事第一次打开，浏览器语言是 `ja-JP` | 登录页是日文；登录后整个控制台是日文，时间显示成 `11月6日 09:10` 的日文写法 |
| 09:12 | 他在设置页把语言改成 English | 当场变成英文，设置窗口不关；刷新后还是英文 |
| 09:30 | 他传了一张超过上限的图片 | 提示是英文：“Images can be at most 50 MB.”（服务器只回代码 `attachment/image-too-large` 和上限的字节数） |
| 次日 | 他在另一台电脑登录 | 直接是英文：语言记在服务器上，跟着账号走 |
| 10:00 | 他在日文系统的 Mac 上运行 `tantalum-connect` | 终端里的提示是日文；加 `--lang en` 就是英文 |
| 10:05 | 模型调用一个 MCP 工具 | 插件页上这个 MCP 工具的说明照原样显示（是 MCP 服务器写的），模型看到的工具说明还是英文 |

### 调研

参照 DSH `main`（fcc0ad4621，2026-11-04）和 pi-mono `main`（7f47a2e，2026-11-05）。DSH 路径相对于仓库根目录；pi 路径相对于 `packages/`；都没有对应做法的标“自定”。

- **DSH 有两套。**
  - 每个包的 `locale/<语言>.json` 只放插件的标题和说明（`meta.title`、`meta.description`，例如 `packages/subagent/subagent-dsh-sdk/locale/en.json:2-5`），由主程序读（`packages/boot/app-boot/src/package-meta.ts:124-175`）。
  - 界面文字是每个包里带类型的 TypeScript 字典，以 zh 为键的来源（`packages/client/ui-trajectory/src/client/locales.ts:7`、`:210` `TrajectoryKey = keyof typeof zh`），en 写成 `Record<TrajectoryKey, string>`（`:224`），ja 等用 `satisfies`（`:627`），缺键或多键编译失败（`packages/client/ui-slots/src/index.ts:86-87`）。
  - 发行 8 种语言（`scripts/shipped-locales.ts:2`）。没有用 i18next 这类库，自己写的 `LocaleRuntime`（`packages/client/locale/src/client/index.ts:166`）。
- **DSH 怎么选语言：** 存下的偏好 → `navigator.languages`（先整个标签，再只比主语言）→ `en`（`index.ts:305-312`、`:539-555`、`:110`）。偏好存在整个 DSH 目录一份，不按账号（`packages/client/locale/README.md:12`；`.agents/notes/implemented/bug-fix/2026-09-15-authenticated-browser-host-preferences.md:11-13`）；检测到的语言不写回（`.agents/notes/archived/feature/2026-07-31-browser-derived-initial-locale.md:24`）。`<html lang>` 跟着当前语言，`zh` 写成 `zh-CN`（`index.ts:149-154`）。设置页“通用”里有一行“语言”（`index.ts:622-629`，`src/client/LanguageRow.tsx`）。切换语言不用重新注册，标签都是取值函数（`.agents/notes/archived/architecture/2026-07-30-client-locale-full-rollout.md:14`）。
- **DSH 的插值和复数：** 单花括号 `{name}`，按正则替换（`index.ts:476-477`）；没有复数规则（`packages/client/locale/README.md:140`），用 `.one`/`.other` 两个键由调用方选（`packages/client/ui-chat/src/client/locale.ts:160-161`）。找不到键就显示键本身，不报错（`index.ts:474`）。
- **DSH 的日期：** 规定不用 `Intl`，日期格式写在字典里（rollout note:24、35），但有几处还是用了浏览器默认的 `toLocaleTimeString`（`packages/client/ui-trajectory/src/client/TrajectoryTimeline.tsx:98`）。
- **DSH 的检查：**
  - `scripts/locale-dictionary-parity.spec.ts`：每种语言的键相同，占位符、斜杠命令、`snake_case` 字段名翻译后不变（`:2-4`、`:385-396`；记号规则 `scripts/shipped-locales.ts:12-14`），扫到的文件太少就失败，免得检查悄悄变窄（`:320`、`:404`）。
  - `scripts/verify-client-ui-i18n.ts`：组件里不许写死界面文字，查 JSX 文字和 `alt`、`aria-label`、`placeholder`、`title`、`label` 这些属性（`:1-8`、`:17-33`），字典文件除外（`:69-75`）。
  - 端到端测试固定用 `en-US` 浏览器（rollout note:26）。
- **DSH 服务端只给代码：** 错误是 `<领域>/<原因>` 形式的代码加一句英文诊断（`.agents/notes/implemented/architecture/2026-08-28-ctx-remote-failure-vocabulary.md:33`、`:62`），客户端按代码说话（`packages/client/ui-model-selection/src/client/index.ts:160`）。插件元数据和钩子的 `displayReason` 是多语言的对象 `LocalizedText = string | { en: string; [语言]: string }`（`packages/util/package-manifest/src/types.ts:44`、`packages/core/tools/src/contracts.ts:361`），客户端选语言（`index.ts:215-221`）。用户、模型、供应商、插件、操作系统写的内容原样显示（`.agents/notes/implemented/architecture/2026-08-23-locale-owned-client-ui-copy.md:13`）。
- **DSH 的英文风格：** 句首大写，其余小写（`AGENTS.md:159`）。术语表 `docs/i18n/terminology.md` 只有中英（压缩 `:106`、钩子 `:143`、插件 `:169`、会话 `:186`、轮次 `:208`），没有日文术语表。
- **pi 的产品本身（coding-agent、tui）没有国际化：** 文字直接写英文（`coding-agent/src/modes/interactive/interactive-mode.ts:6862-6871`），日期用系统默认语言（`:1254`）。2026-11-03 起只在单独的 `chord` 包里做了一套：
  - 消息在代码里定义，第一份语言定键和参数类型，值可以是函数，复数用函数写（`chord/src/surfaces/messages.ts:1-22`、`:84-107`）；
  - 服务器只发键和参数，由每个看的人的客户端说出来（`chord/docs/i18n/book.md`）；
  - 语言：用户选的 → 设备语言 → `en`（`chord/src/surfaces/language.ts:75-81`），按用户记在服务器上（`chord/src/surfaces/locale-choice.ts:1-19`）；终端读 `LANGUAGE`、`LC_ALL`、`LC_MESSAGES`、`LANG`（`chord/src/node/tui/launcher.ts:472-481`）；
  - 日期和数字用 `Intl` 按当前语言格式化（`chord/src/surfaces/format.ts:1-47`）。
  - pi 没有检查缺翻译或写死文字的脚本。
- **Java 一侧两边都没有可参照的。** Tantalum 现在也没有 `ResourceBundle`、`MessageSource`、`Accept-Language` 的用法（全仓库搜索）。服务器上的做法是自定。

### 范围

| 在范围内 | 不在范围内（原因） |
|---|---|
| 控制台全部文字的中、英、日三份 | 别的语言。DSH 有 8 种（`scripts/shipped-locales.ts:2`）；我们的做法加一种语言就是每处多一个文件，以后要加再说 |
| 设置页选语言，按用户记在服务器上；登录页跟浏览器 | 繁体中文单独一份（`zh-TW`、`zh-HK` 的浏览器按主语言 `zh` 显示简体，照 DSH 再比主语言的做法，`index.ts:539-555`） |
| 日期、时间、数字按语言格式化 | 从右往左的语言 |
| 接口错误改成代码加参数，控制台按语言说 | 服务器日志（给管理员看的，一律英文） |
| 插件作者写的说明、问题可以带三种语言 | 钩子停止运行的原因（`Stop.reason`）：写进会话文件的字符串，改它要改会话文件格式；照原样显示 |
| 连接程序、命令行子命令的终端文字 | 给模型看的文字：system、工具说明、不可信内容的说明、压缩提示词、工具结果、工具变化告诉模型的那句（BACKLOG 定“给模型看的文字不翻译”） |
| 本机沙箱用不了的原因改成代码，控制台和终端都按语言说 | MCP 工具的说明、模型的回复、轨迹页的原始数据（是别人写的数据，照 DSH 原样显示，locale-owned note:13） |
| 检查：缺翻译、参数不一致、组件里写死的文字 | README 和文档的翻译（DSH 另有一套文档配对检查，`docs/i18n/README.md:28-44`；我们已有 `README.en.md`，不在这里管） |
| 顺带修好现在写死的文字（插件页的英文说明、`trace/format.ts:5-6` 的“毫秒”“秒”、写死的 `zh-CN`） | 浏览器工具打开网页用的语言（`UserBrowser.java:91` 的 `zh-CN`）：影响的是模型读到的网页，属于给模型的内容 |
| | macOS 安装包 `Tantalum Connect.app` 的系统菜单文字 |

### 待批准的决定

批准本文档即批准这些决定。

| # | 问题 | 决定 |
|---|---|---|
| I1 | 哪几种语言 | 中文 `zh`（简体）、英文 `en`、日文 `ja`。`<html lang>` 写 `zh-CN`、`en`、`ja`，浏览器据此选字形：同一个汉字在日文页面用日文字形（照 DSH `index.ts:149-154`）。Yi 2026-11-06 定三种 |
| I2 | 没选过时用哪种 | 按 `navigator.languages` 的顺序，每一项先比整个标签、再只比主语言（`zh-TW` 算 `zh`），第一个对上的就用；都对不上用 `en`。照 DSH（`index.ts:539-555`、`:110`）和 pi chord（`language.ts:75-81`）。检测到的语言不写回服务器（DSH browser-derived note:24），换了浏览器语言就跟着换。登录页还不知道是谁，只跟浏览器 |
| I3 | 用户选的记在哪 | 记在服务器 `users.db` 的 `user_settings`，键 `language`，值 `zh`、`en`、`ja`；没有这一行就是“跟随浏览器”。每个用户只改自己的。DSH 是整个程序一份（README:12），我们是多用户，照 pi chord 按用户记（`locale-choice.ts:1-19`）。浏览器里另存一份 `tantalum.language`，只为刷新时第一帧就用对语言、不闪（同深浅色，`theme/theme.ts:10`、`index.html:10-18`）；退出登录时清掉，登录页回到跟浏览器 |
| I4 | 切换怎么生效 | 当场生效，不刷新页面，不重新挂载：设置窗口不关，正在输入的草稿不丢。照 DSH（rollout note:14，标签是取值函数）。组件在渲染时取当前语言的字典，不在模块加载时取 |
| I5 | 字典写成什么样 | 照 DSH 带类型的 TypeScript 字典，不引入 i18next 之类的库（`console/package.json` 里没有，DSH 也没有，`index.ts:166`）。`zh.ts` 是键的来源，`en.ts`、`ja.ts` 的类型由它推出来：缺一条、多一条、参数的个数或类型不对，`tsc` 就失败。带参数的文字照现在 `zh.ts` 的写法写成函数（`zh.ts:44`），参数有名字有类型，比 DSH 的 `{name}` 字符串多一层编译检查；复数在函数里按数量选（英文 `1 tool` / `2 tools`），同 pi chord（`messages.ts:15-16`）。控制台是一个包，一种语言一个文件；服务器、连接程序每个模块各有自己的一组文件（I12、I13），对应 DSH 每个包一组 |
| I6 | 怎么抓缺漏 | 四层：① `tsc`（I5）；② vitest 对比三份字典：键树相同、数组长度相同、函数参数个数相同，每个函数用样例参数调用后返回非空、不含 `undefined`；原文里的记号翻译后不变：`--allow-write` 这类参数、`snake_case` 名字、`tantalum.` 开头的配置项、反引号里的代码（照 DSH `locale-dictionary-parity.spec.ts:390-396`、`shipped-locales.ts:12-14`）；英文里不能有中日文字符；③ vitest 扫源码：`text/` 以外不许有中日文字符串、不许在 JSX 文字和 `aria-label`、`title`、`placeholder`、`alt`、`label` 里写字面文字，`text/` 以外不许直接引用某一种语言的字典（照 DSH `verify-client-ui-i18n.ts:1-8`、`:17-33`），扫到的文件少于 50 个就失败（照 DSH `:15`）；④ 用英文渲染几个主要页面，画面上不能有中日文字符（抓漏网的和“模块加载时取了字典”的）。找不到键不会发生：类型保证 |
| I7 | 日期、时间、数字 | 用 `Intl` 按当前语言格式化：时刻、日期、相对时间（“5 分钟前”用 `Intl.RelativeTimeFormat`）、数字和字节数的千分位。照 pi chord（`format.ts:1-47`）。DSH 规定不用 `Intl`、把日期格式写进字典（rollout note:24、35），不照它：三种语言 `Intl` 都支持得很好，少写一堆格式模板。时长（“1 分 5 秒”）还是写在字典里：`Intl.DurationFormat` 在 jsdom 和部分浏览器里还没有。`tok` 作为单位三种语言都不翻译 |
| I8 | 什么翻译、什么原样 | 产品自己写的文字都翻译：控制台的标签、提示、错误、内置工具在插件页上的名字和说明。别人写的数据原样：用户和模型写的、MCP 服务器给的工具说明、轨迹页的原始数据（照 DSH locale-owned note:13）。插件页的内置工具一律用字典里的名字和说明，不再退回显示给模型看的英文说明（`PluginsDialog.tsx:72-74`）；字典里没有的内置工具，测试失败 |
| I9 | 接口错误 | 错误 JSON 加两个字段：`{"error": "英文诊断", "code": "attachment/too-large", "params": {"limit": 52428800}}`。`code` 照 DSH 写成 `<领域>/<原因>`（ctx-remote-failure note:33）。控制台按 `code` 从字典里取文字，参数里的数字和字节数由控制台按语言格式化；`code` 认不出时显示“请求失败（状态码）”，下面小字带英文诊断。现在中文的服务器错误（附件、图片、提交）都改成英文诊断加代码。只有写错的客户端才会碰到的错误（JSON 写错、缺 `requestId`）统一用 `request/invalid`，不一条条翻。登录接口现在的 `invalid_credentials` 这类值保留，另加 `code` |
| I10 | 插件作者写的文字 | 钩子插件、插件工作的说明（`KernelHook.description`、`AfterToolHook.description`、`WorkType.description`），插件问的问题（`Question` 的标题、说明、按钮）改成 `LocalText`：一个字符串，或者 `{en, zh, ja}`，`en` 必填。控制台按“用户的语言 → `en`”选。照 DSH `LocalizedText`（`package-manifest/src/types.ts:44`、`index.ts:215-221`）。`tantalum-examples` 里的 8 个插件和审批问题写齐三种。钩子停止运行的原因照原样显示（见范围） |
| I11 | 运行失败、压缩失败的技术原因 | 标题按代码翻译（现在已经这样：`FailureReason`、`EndReason`，`conversation/runOutcome.ts:34-37`）；下面小字的技术原因（`RunStatus.detail`、压缩没采用的原因）照原样显示英文，同 DSH“代码加英文诊断”。这些字符串写在会话文件里，不改 |
| I12 | 连接程序的终端文字 | `tantalum-connect` 的提示、错误、启动摘要、重连消息放进资源文件，中、英、日三份。语言：`--lang zh|en|ja` → 环境变量 `LC_ALL`、`LC_MESSAGES`、`LANG` → Java 的系统语言 → `en`。照 pi chord 终端的做法（`launcher.ts:472-481`）。本机沙箱用不了的原因（`tantalum-local` 的 `Mxc.java`，现在是中文，经连接消息送到服务器）改成代码加参数：终端按连接程序的语言说，插件页按看的人的语言说；老版本连接程序只送原因文字时照原样显示。`--allow-shell was not given` 这句暗号（`Main.java:38`、`LocalWorkspaceCard.tsx:89`）换成代码。每次调用的那一行记录（`Journal.java:20-23`）不翻译，是给人查的固定格式 |
| I13 | 命令行子命令 | `users`、`check`、`browser install` 的输出放进资源文件，三种语言，选语言同 I12。服务器日志、启动失败的日志不翻译 |
| I14 | Java 的资源文件 | 每个模块一组 UTF-8 的 `.properties`：`Text.properties`（英文）、`Text_zh.properties`、`Text_ja.properties`。占位符用 `{name}`，不用 `MessageFormat`：它要把英文里的 `'` 写成 `''`，一不小心整句就错。读取时关掉 Java 按系统语言的回退（不然日文找不到的键会拿系统语言那份）。每个模块的键是一个枚举，测试检查：每个键三份都有、没有多的键、三份的占位符相同、英文里没有中日文字符。自定（DSH、pi 都不是 Java） |
| I15 | 译文的要求 | 英文：句首大写，其余小写（照 DSH `AGENTS.md:159`），短、直接。日文：按钮和标签用名词形，说明和提示用です・ます体。三种语言用同一张术语表（设计里），DSH 已有译法的照 DSH（例如会话 `Session` / `セッション`、轨迹 `Trajectory` / `軌跡`、插件 `Plugins` / `プラグイン`、压缩 `Compact` / `圧縮`，`ui-workspace/src/client/locales.ts:21`、`ui-trajectory/src/client/locales.ts:8`、`ui-plugin-manager/src/client/locales.ts:11`、`ui-commands/src/client/locales.ts:17`）。翻译写完，由另一个 agent 按术语表逐条审一遍 |
| I16 | 已有的测试 | 已有的 vitest 和 Playwright 断言是中文的，固定用中文跑：vitest 在启动文件里设成中文，Playwright 已经是 `locale: "zh-CN"`（`playwright.config.ts:17`）。DSH 的端到端固定 `en-US`（rollout note:26），不照它，免得改几百条断言。英文、日文另写测试 |
| I17 | 同时在做的 spec | `web-tools`（插件页“联网”卡片、工具变化）、`model-picker`（选模型）正在加中文文字。字典的类型保证：谁后合进来，谁补上英文和日文，不然构建不过 |
| I18 | 服务器拿用户的语言做什么 | 本 spec 里服务器自己不按语言说话（都是代码，由控制台说），存在服务器上是为了换一台电脑还是同一种语言（I3）。以后服务器要自己写给人看的文字（比如邮件），从这里取 |

## Requirements

### Requirement 1：选语言和记住

**User Story:** 作为用户，我想让控制台用我的语言显示，换电脑也不用重新选。

#### Acceptance Criteria

1. WHEN 用户没选过语言 THEN 控制台 SHALL 按 `navigator.languages` 选第一个能对上的语言（先整个标签、再主语言），都对不上用英文（I2）。
2. WHEN 打开登录页 THEN 登录页 SHALL 跟浏览器的语言，不看上一个登录过的用户（I2、I3）。
3. WHEN 用户在设置页选了一种语言 THEN 控制台 SHALL 当场换成这种语言，SHALL NOT 刷新页面或关掉设置窗口（I4），并 SHALL 存到服务器上这个用户的设置里（I3）。
4. WHEN 用户选“跟随浏览器” THEN 服务器上的 `language` SHALL 被删掉，控制台按 I2 选。
5. WHEN 同一个用户在另一个浏览器登录 THEN 控制台 SHALL 用他存在服务器上的语言；另一个用户的语言 SHALL NOT 受影响（I3）。
6. WHEN 刷新页面 THEN 第一帧 SHALL 已经是用户的语言，不先闪一下别的语言（I3）。
7. WHEN 退出登录 THEN 浏览器里存的语言 SHALL 被清掉，登录页跟浏览器（I3）。
8. `<html lang>` SHALL 随当前语言变成 `zh-CN`、`en` 或 `ja`（I1）。
9. WHEN 服务器收到 `zh`、`en`、`ja` 以外的语言 THEN SHALL 回 400 和代码 `settings/invalid`，不存。

### Requirement 2：控制台文字三种语言齐全

**User Story:** 作为用户，我想看到的每一处文字都是我选的语言，不再中英混着。

#### Acceptance Criteria

1. 控制台自己的每一条文字 SHALL 有中、英、日三份；少一份时 `pnpm build` SHALL 失败（I5、I6）。
2. 带参数的文字三份的参数 SHALL 相同；参数对不上时 `tsc` 或 vitest SHALL 失败（I5、I6）。
3. 组件里 SHALL NOT 写死给人看的文字；有写死的，vitest SHALL 失败并写明文件和行号（I6）。
4. 插件页的每个内置工具 SHALL 显示字典里的名字和说明，SHALL NOT 显示给模型看的英文说明；MCP 工具的说明 SHALL 原样显示（I8）。
5. WHEN 用英文打开对话页、轨迹页、统计页、插件页、设置页 THEN 画面上（除了用户、模型、MCP 写的内容）SHALL NOT 有中日文字符（I6）。
6. 英文、日文 SHALL 用设计里的术语表；同一个概念在同一种语言里 SHALL 只有一种说法（I15）。
7. 现在写死的文字 SHALL 改掉：`trace/format.ts:5-6` 的“毫秒”“秒”、写死的 `zh-CN`、插件页退回英文说明的地方。

### Requirement 3：日期、时间、数字

**User Story:** 作为用户，我想看到的时间和数字是我习惯的写法。

#### Acceptance Criteria

1. 时刻、日期、相对时间、数字、字节数 SHALL 用 `Intl` 按当前语言格式化，代码里 SHALL NOT 再写死 `zh-CN`（I7）。
2. WHEN 语言是日文 THEN 侧栏的相对时间 SHALL 是 `5 分前`、`昨日` 这样的日文写法；英文是 `5 minutes ago`、`yesterday`（I7）。
3. 时长 SHALL 用字典里的写法（`1 分 5 秒`、`1m 5s`、`1分5秒`）（I7）。

### Requirement 4：服务器给画面的文字

**User Story:** 作为用户，我想让服务器报的错、插件写的说明也是我的语言。

#### Acceptance Criteria

1. 会显示给用户的每个接口错误 SHALL 带 `code`，需要数字的带 `params`；`error` SHALL 是英文诊断（I9）。
2. WHEN 控制台收到认得的 `code` THEN SHALL 用当前语言显示，参数里的数字和字节数按当前语言格式化（I9、I7）。
3. WHEN 控制台收到认不得的 `code` 或没有 `code` THEN SHALL 显示“请求失败（状态码）”，下面带英文诊断（I9）。
4. 服务器的每个错误代码 SHALL 在控制台的三份字典里都有；少一个时测试 SHALL 失败（I9、I6）。
5. WHEN 插件的说明或问题带了多种语言 THEN 控制台 SHALL 显示用户的语言那份，没有就显示英文；只给一个字符串的照原样显示（I10）。
6. 运行失败、压缩没采用的标题 SHALL 按当前语言显示，技术原因照原样显示（I11）。
7. WHEN 连上的电脑不能跑命令 THEN 插件页 SHALL 按看的人的语言说明原因（I12）。
8. 给模型的文字 SHALL NOT 变：同一个会话分别用三种语言的控制台提问，发给模型的请求 SHALL 完全相同（范围）。

### Requirement 5：终端里的文字

**User Story:** 作为用户和管理员，我想让连接程序和命令行用我的系统语言说话。

#### Acceptance Criteria

1. `tantalum-connect` 的提示、错误、启动摘要、重连和断开的消息 SHALL 有中、英、日三份（I12）。
2. WHEN 给了 `--lang` THEN SHALL 用它；没给时 SHALL 按 `LC_ALL`、`LC_MESSAGES`、`LANG`、系统语言的顺序选，都对不上用英文（I12）。
3. WHEN `--lang` 的值不是 `zh`、`en`、`ja` THEN SHALL 用英文说明可选的值并退出（I12）。
4. `users`、`check`、`browser install` 的输出 SHALL 同样有三份，同样选语言（I13）。
5. 每个模块的每个键 SHALL 三份都有、占位符相同；不齐时测试 SHALL 失败（I14）。
6. 服务器日志 SHALL NOT 翻译（I13）。

### Requirement 6：在 8091 上看一遍

**User Story:** 作为 Yi，我想亲眼看到三种语言都对。

#### Acceptance Criteria

1. 在 8091 上把语言换成中文、英文、日文，各截登录页、对话页、轨迹页、统计页、插件页、设置页的图给 Yi。
2. 截图里 SHALL NOT 有别的语言的产品文字（用户、模型、MCP 写的除外）。

## Non-Functional Requirements

### Performance

- 在有 1,000 行的轨迹页上切换语言，到画面换完 ≤ 300 ms（Yi 的 Mac，生产构建）。
- 三种语言的字典都打进主包，主包 gzip 后增加 ≤ 30 KB；不按语言分包加载（一共三种，分包多一次请求，得不偿失）。

### Security

- 服务器只收 `zh`、`en`、`ja` 三个值（需求 1.9）。
- 参数作为文字显示，不当 HTML；字典里不写 HTML。
- 错误的 `params` 只放数字和名字这类显示用的值，不放路径、令牌、用户的内容。

### Reliability

- 服务器上存的语言读不懂时当没选过（同 `compaction_percent` 的做法，`UserSettings.java:110`）。
- 浏览器禁用本地存储时照样能用，只是刷新时可能先闪一下浏览器的语言（同深浅色，`ui/storage.ts:1-2`）。
- 老版本连接程序送来的原因文字照原样显示，不报错（I12）。

### Usability

- 语言的选项用各自的名字显示：`中文`、`English`、`日本語`，另加“跟随浏览器”（用当前语言写）。
- 日文页面的汉字用日文字形（I1）。
