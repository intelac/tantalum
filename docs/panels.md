# 插件面板

插件可以在对话右边放一个面板，像 artifacts 那样：用户在会话顶栏的“面板”按钮里打开它，可以拖宽、放大、关闭。每个会话记住开着哪个面板。面板是插件 jar 里的一个普通网页，控制台用沙箱 iframe 打开，数据经过一条消息桥拿。

示例：`tantalum-examples` 里的“本轮概览”（`RunOverviewPanel`，页面在 `src/main/resources/tantalum/panels/run-overview/`），显示当前会话最近一轮的步骤、模型调用和工具，运行时实时更新。

## 声明面板

```java
public final class MyPanel implements Panel {
    public String name() { return "my-panel"; }                 // 小写字母、数字、连字符，全局唯一
    public LocalText title() { return LocalText.of("My panel", "我的面板", "マイパネル"); }
    public String icon() { return "activity"; }                 // activity、chart-bar、gauge、list-tree、panel-right
    public String base() { return "tantalum/panels/my-panel/"; } // jar 里的目录，以 / 结尾
}
```

把它注册成 Spring bean（和钩子、工具一样）。面板默认属于同名的插件，在插件页上有自己的开关；要和别的钩子算同一个插件，覆盖 `plugin()`。`PluginInfo` 的说明、默认开不开、必须开启在面板上照样能用。

服务器在 `/panels/<name>/` 下给出 `base` 目录里的文件。只认这些扩展名：html、js、mjs、css、json、svg、png、jpg、webp、woff2。文件是公开的静态文件，不要放任何秘密。

## 面板能做什么、不能做什么

面板文件的响应带这样的 CSP：

```
default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:;
connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'; frame-ancestors 'self'
```

iframe 的 `sandbox` 只有 `allow-scripts`，所以：

- 面板是不透明的源，读不到控制台的 cookie 和本地存储，也不能改控制台的地址。
- 面板不能自己联网（`fetch`、`XMLHttpRequest`、WebSocket 都被拦），也不能从别处加载脚本、嵌框架、提交表单。所有脚本和样式放在自己的目录里。
- 面板只能经消息桥拿当前会话的只读数据，见下面的白名单。

要说清楚的是：面板是服务器插件的一部分，插件 jar 本身在服务器上以完全的权限运行。沙箱挡的是“面板用用户的登录身份做事”和“面板直接调接口”，但挡不住一个恶意面板把自己的页面导航到别的网站、把拿到的数据带出去。所以只装你信任的插件，和服务器插件的规矩一样。

## 消息协议 v1

面板和控制台只靠 `postMessage` 通信。每条消息都是 `{ tantalum: 1, type, ... }`。面板发给 `window.parent`；控制台只认来自这个 iframe 的消息，单条最多 1 MB，格式不对的丢掉（浏览器控制台里有 `[panel]` 警告）。

面板发给控制台：

| type | 字段 | 作用 |
|---|---|---|
| `ready` | | 处理函数都注册好了；控制台回 `init` |
| `get` | `id`、`what`、`params` | 只读请求，控制台回 `result` |
| `subscribe` | `what: "trace"` 或 `"monitor"` | 开始接收当前会话的实时轨迹记录，或流水线的实时阶段 |
| `open` | `target: {run}` 或 `{record}` | 请控制台打开这次运行的详情或轨迹页的这条记录（只限当前会话） |
| `error` | `message` | 显示在面板上方的错误条里 |

控制台发给面板：

| type | 字段 |
|---|---|
| `init` | `session`、`run`（在轨迹页定位到某一轮时有）、`theme`（`light`/`dark`）、`language`（`zh`/`en`/`ja`）、`readOnly`（管理员只读查看别人时为 true） |
| `context` | 同 `init`，会话、运行、主题、语言变了时再发 |
| `result` | `id`、`ok`、`status`、`body` 或 `error` |
| `trace` | `records`：当前会话刚变化的轨迹记录（同一条只给最新版本，一批最多 500 条），格式同 `/api/sessions/{s}/trace` |
| `monitor` | `messages`：当前会话的事件刚走过的流水线阶段，按到达的先后（一批最多 500 条），见下面 |

`get` 的白名单（都限定在当前会话，用当前用户的身份）：

| what | params | 等于 |
|---|---|---|
| `tree` | `run`（当前会话的运行） | `GET /api/sessions/{s}/runs/{run}/tree` |
| `trace` | `before` 或 `run`（可选） | `GET /api/sessions/{s}/trace` |
| `runs` | `limit`（1–200，默认 20） | `GET /api/runs?session={s}` |
| `summary` | | `GET /api/runs/summary?session={s}&tz=<浏览器时区>` |

## 实时监视消息

订阅 `monitor` 后，控制台打开一条只在内存里的监视流（`/api/monitor/stream`，没有面板订阅时关掉），把当前会话的消息转给面板。每条消息：

| 字段 | 含义 |
|---|---|
| `stage` | `INTAKE`（提交被接受）、`INPUT`（进输入环，含处理器结果回来）、`LANE`（用户通道取号）、`KERNEL`（内核走完一步）、`JOURNAL`（落盘）、`PUSH`（推给页面）、`OUTPUT`（命令进输出环）、`HANDLER_START`、`HANDLER_END`、`REMOTE_SEND`、`REMOTE_DONE`（发给用户电脑的连接程序、拿回结果） |
| `session`、`run`、`seq` | 会话、运行、事件序号（取号以后才有） |
| `command` | 命令 ID（命令和处理器的阶段，以及处理器结果回到输入环时） |
| `name` | 事件或命令的类型，工具调用时是工具名 |
| `detail` | 一句数值说明，如 `seq 42`、`AWAITING_TOOLS → CALLING_MODEL, 1 command, 35 µs`、`fsync 2 ms`、`1830 ms` |
| `at` | 毫秒时刻 |
| `dropped` | 这条之前因为跟不上丢了几条（服务器每个订阅最多排 2048 条） |

阶段名是约定：服务器的组件以后改名、重构，阶段不变。示例面板“实时监视”（`live-monitor`）把它画成架构图上的光点。

## 辅助脚本

把示例里的 `bridge.js` 拷进你的面板目录，在自己的脚本之前加载：

```html
<script src="bridge.js"></script>
<script src="panel.js"></script>
```

```js
tantalum.on("init", (context) => { /* context.session, context.theme … */ tantalum.subscribe(); /* 或 subscribe("monitor") */ });
tantalum.on("context", (context) => { /* 会话或主题变了 */ });
tantalum.on("trace", (message) => { /* message.records */ });
tantalum.ready();

const page = await tantalum.get("runs", { limit: 1 });
const tree = await tantalum.get("tree", { run: page.runs[0].run });
tantalum.open({ run: page.runs[0].run });
```

`bridge.js` 也会把面板里没接住的异常用 `error` 报给控制台。

主题：控制台在 `init` 和 `context` 里给 `theme`，面板自己按它换颜色（示例在根元素上设 `data-theme`，CSS 变量分两套）。
