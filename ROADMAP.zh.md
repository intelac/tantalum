# Tantalum 路线图

[English](ROADMAP.md) | 中文

Tantalum 是企业 agent 的事件驱动底座：一台服务器、很多用户、每一步落盘可回放、功能靠插件。0.1 已经发布（2026-11）。下面是我们接下来要做的事，按顺序，带着为什么。欢迎在 issue 里讨论。

## 一句话

**让一家公司不再失忆，并且让 AI 在公司里有据可查地做事。**

Tantalum 的底子是一本账：每个人和 AI 之间发生的每一步记一行、能原样回放（0.1 已有）。有了这本账，三样过去要分别建的东西可以长出来：每个人的记忆（第 1 条）、公司自己长出来的知识地图（第 1 条的团队部分）、按公司规则自主做事的代理（第 2、3 条）。用得越多账越厚，记忆和地图越准，代理能接的活越多。开源社区已经分别验证了这三层各自可行——阿里 AgentScope 的 ReMe（记忆）、NVIDIA 的 Auto Ontology（知识地图）、DeepSeek 的 super-agent 原型（行动）——它们共同缺的是这本账，和多用户的企业底座。

## 1. LLM wiki：不断实时更新的长期记忆（设计中）

标签：`long-term-memory` `llm-wiki` `auto-ontology`

一座由 LLM 维护、用户能看能改的 wiki，作为跨会话的记忆。本体（人、项目、决定、偏好、做法、术语之间的关系）不预先设计，由页面和 `[[链接]]` 长出来。

- 页面是 Markdown 文件加 frontmatter，每段话带 journal 出处，没有出处的标“未经证实”。
- 运行里只**追加**新事实（实时可见），改写和合并只由定时的“图书管理员”运行做，按量触发、每次有限额——因为持续改写记忆会让记忆变坏（arXiv 2605.12978）。
- journal 永远是原始证据，页面只是索引和归纳，任何页面都能从 journal 重做。
- 每个用户一座 wiki；团队 wiki 另一座，页面从个人升到团队要人点头。
- 参考：阿里 AgentScope 的 [ReMe](https://github.com/agentscope-ai/ReMe)（分层、文件即记忆、钩子捕获、定时整理）、Karpathy 的 LLM Wiki（原始来源 / wiki / schema 三层）、NVIDIA 的 [Auto Ontology](https://github.com/NVIDIA/auto-ontology)（企业数据的术语和关系由 LLM 抽、人认证后生效）。


## 2. 走出会话（设计中）

标签：`autonomous-agent` `event-driven`

wiki 定了以后，让 agent 不只在有人提问时存在，内核只需要加三样东西：

- **外部事件**：一类新事件 `Trigger(source, actor, payload)`，和用户提问走同一条管线、同一份 journal；先用代码筛（允许名单、团队规则），再让模型判断。
- **系统身份**：没人叫它的时候它以谁的身份做事——永远绑定一个用户，团队级的东西走团队 wiki 和管理员规则，不做全局的 agent。
- **委派**：一次运行可以开另一个会话里的运行，等结果（像等一个工具）或不等（对方的汇报是将来的一个事件）。这就是通常说的子 agent，在我们这里只是一种归属关系，不是新实体。

画面上：一个主窗口为主，长活自动分到“话题”里；外部事件触发的活带来源标签；它没在干活时画面上什么都没有。

## 3. Super-Agent（设计中）

标签：`super-agent` `multi-agent`

DeepSeek 在 *Exploring Super-agent Design* 和 [dsh-superagent-prototype](https://github.com/deepseek-ai/dsh-superagent-prototype) 里描述的方向：agent 听着团队的消息、代码评审评论、CI 结果，只在有人明确要求或团队规则允许时动手，只在有人必须做决定或能用上结果时开口。

- 第一个事件来源接 **GitHub**（issue、评论、CI 结果），用它盯 Tantalum 自己的仓库接活、提 PR——用自己验证自己。
- 允许名单、团队规则、**观察模式**（先记下“本来会接的活”，看对了再放开）。
- Router 只分派、Worker 并行干活，在我们这里是“不等的委派”加受限的工具集；看门狗是定时事件加超时。
- 飞书之类的聊天来源往后放。

## 4. 定时事件和看门狗

标签：`scheduler`

定时器作为事件来源：定期的回顾、图书管理员的整理、检查 Worker 有没有真进展。内核的 `StartTimer`/`TimerFired` 已有，缺的是跨重启的定时表和画面。

## 5. 持续的工程项

- **安全加固**：0.1 后的整体 review 修完了五个阶段；以后每个版本前再做一次整体 review 和 [open-code-review](https://github.com/alibaba/open-code-review) 扫描。
- **Windows**：服务器在 Windows 上已可运行（计划任务后台运行），靠 CI 验证；连接程序的 Windows 沙箱还没有。
- **本机工作区的命令**：2026-11-14 起没有操作系统沙箱（以前 macOS 才有的 MXC 去掉了），三个系统一样，每条命令先问用户（`connector-jar`）。跨平台的沙箱以后再看。
- **联网**：有服务器上的 `web_fetch` 和用户电脑上的浏览器（本机的 Playwright MCP）；`web_search` 接口和读 PDF 以后另开。
- **journal 格式升版**：把 `RunState`、`SessionState` 真正按阶段分组（现在只是视图），升版时一起做。

## 不打算做的

- 全局的、不绑定用户的 agent：多用户底座上隔离是底线。
- 每轮都重写记忆：见第 1 条的论文。
- 收费的搜索接口当唯一方案：换本地模型就没了。

## 版本

- **0.1**（2026-11-07）：内核、journal 与回放、多用户控制台、插件、记忆、附件、本机工作区、联网、模型路线、等用户回答、Windows。见 [Release](https://github.com/intelac/tantalum/releases/latest)。
- **0.2**：第 1 条（LLM wiki）和第 2 条的前半（外部事件、系统身份）。
- **1.0**：企业里真用过，Super-Agent 做出来。
