# Tantalum

[English](README.md) | 中文

**企业 agent 的运行平台，事件驱动，Java 写的。** 公司自己部署一台服务器，很多人一起用，底下是一本账：人和 agent 之间的每一步，先写成只追加的 journal 的一行，再生效。模型、工具、插件都可以换，账不换；agent 记得的、知道的、做过的，每一条都能回到账里的某一行。

版本 **0.1** · [下载](https://github.com/intelac/tantalum/releases/latest)（一个 jar，macOS、Linux、Windows 通用，需要 JDK 25）· [路线图](ROADMAP.zh.md) · MIT

## 它是什么

运行平台，不是框架。框架管模型怎么想、提示词和工具怎么编排；运行平台管一件活怎么被事件叫起、怎么推进、怎么暂停和恢复、谁有权做、事后怎么查。

- **持久。** 每一步和它的结果是账里的一行，落盘后才走下一步。重启按记下的结果还原，不重算。
- **事件驱动。** 用户的话、模型的回复、工具结果、插件的工作都是事件。内核是纯函数：收到这个事件，下一步做什么。所有 I/O 在内核外做，结果作为新事件回来，所以模型跑着的时候用户能插话，慢活不卡别的会话。
- **可重建。** 任何会话能逐步回放；`java -jar tantalum.jar check` 核对新版本重写旧会话是否逐字节相同。
- **可追溯。** 每行带谁执行、哪个会话、哪次运行。控制台的轨迹页能看到每一次模型调用、工具调用和钩子。
- **可扩展。** 加功能写插件，只经一个闸进来，每个用户自己开关。八个钩子点；钩子可以问用户一个问题然后等几天。
- **边界由服务器守，不靠模型自觉。** 插件只经 `PluginGate`；文件、Git、命令行工具经连接程序在用户自己的电脑上、沙箱里、用户允许的目录内执行；API 密钥不进 journal、日志和模型上下文。

## 0.1 有什么

多用户控制台（中文、English、日本語）· 每会话自选模型和思考档位（Claude，或任何 OpenAI 兼容接口，比如本机 llama.cpp）· 只追加的 journal，回放和 `check` · 轨迹页 · MCP 客户端 · 自动压缩上下文 · 附件和图片 · 每人一份长期记忆 · 八个钩子的插件机制和示例插件（只读策略、工具审批、每日额度、退避、引用检查）· 本机工作区：macOS 连接程序加 MXC 沙箱 · 联网抓取和每人一个无头浏览器 · macOS、Linux、Windows 都能跑。

## 运行

```sh
export ANTHROPIC_API_KEY=...
java -jar tantalum-0.1.0.jar          # 然后打开 http://127.0.0.1:8080
```

全部留在本地，用一条 OpenAI 兼容路线：

```sh
java -jar tantalum-0.1.0.jar --tantalum.provider=local \
  --tantalum.openai-routes.local.base-url=http://127.0.0.1:8090/v1 \
  --tantalum.openai-routes.local.model=<模型名>
```

从源码构建（JDK 25、Maven 3.9、Node 25、pnpm）：`(cd console && pnpm install && pnpm build) && mvn clean package -DskipTests`，产物在 `tantalum-shell/target/tantalum-shell-0.1.0-exec.jar`。Windows 见 [docs/windows](docs/windows)。

## 往哪儿走

账在中心。三样东西从账里长出来，是投影，不是另建的系统：每个人的记忆（一座 wiki，照阿里的 [ReMe](https://github.com/agentscope-ai/ReMe)）、公司经人认证的知识（照 NVIDIA 的 [Auto Ontology](https://github.com/NVIDIA/auto-ontology)）、按团队规则从外部事件接活的代理（照 DeepSeek 的 [super-agent 原型](https://github.com/deepseek-ai/dsh-superagent-prototype)）。内核只加五样原语、不加阶段：外部事件、委派、两种新归属、带负责人的代理身份、每次工具调用前的闸。第一个里程碑是一个能证伪的实验：两个身份和权限不同的代理，在没人在线的情况下合作完成一件长活，中途重启一次，全程在账里，内核不打补丁。细节见 [ROADMAP.zh.md](ROADMAP.zh.md)。

## 代码结构

| 模块 | 内容 |
|---|---|
| `tantalum-value` | 不可变类型：事件、命令、状态 |
| `tantalum-kernel` | 纯逻辑：迁移表和钩子，无 I/O |
| `tantalum-shell` | 所有 I/O：环、journal、处理器、模型客户端、web |
| `tantalum-local` | 在用户电脑上跑的工具、MXC 沙箱、线上格式 |
| `tantalum-connect` | 用户在自己 Mac 上跑的连接程序 |
| `tantalum-examples` | 示例插件 |
| `console/` | 网页控制台（React） |

依赖只朝一个方向：`value ← kernel ← shell`，由 ArchUnit 守着。spec 先行、指挥 AI 编码 agent 写成：每个功能先过需求、设计、任务再写代码，每条需求都标出 pi 和 DeepSeek Harness 怎么解决同一个问题。

## 文档

- 完整手册：[docs/manual.zh.md](docs/manual.zh.md)。配置、模型路线、用户、journal 格式、运维、Windows、记忆、附件、本机工作区、联网、MCP、写插件、测试。
- Windows：[docs/windows](docs/windows)。
- 许可：MIT，见 [LICENSE](LICENSE)。第三方声明：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
