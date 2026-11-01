# 待办总表

所有未完成事项只记在这里。具体任务仍在各 spec 的 `tasks.md`，这里只记位置和状态。最后核对：2026-11-02。

## 1. 正在做

| 事项 | 状态 | 位置 |
|---|---|---|
| `ops-hardening` | 0/44，正在做任务 1；任务 5 是检查点，要 Yi 确认基线和预算 | `specs/ops-hardening/tasks.md` |

## 2. 已定要做，还没开 spec

按计划顺序。

| 事项 | 已知内容 | 还缺什么 |
|---|---|---|
| 沙箱 | 基于开源项目，不自己写；候选要实测对比 | 开 spec |
| 子 agent | 已确认要做，排在沙箱附近 | 开 spec；和沙箱的先后未定 |
| MCP 后续 | 每用户令牌、OAuth、stdio、人工审批 | Yi 决定要不要做、做哪几项 |

## 3. 等 Yi 决定

| 事项 | 说明 |
|---|---|
| 两条遗留审批 | `core-skeleton` 需求（2026-10-31）和 `tech.md`（2026-10-31）在仪表板上仍是 pending。两份文档的工作早已展开。未说“批准”不替 Yi 点 |

## 4. 已知缺口

已完成的 spec 留下的，还没有对应任务。

| 来源 | 缺口 | 处理 |
|---|---|---|
| `spec-audit-fixes` | A8 重试范围比需求窄 | 未排期 |
| `spec-audit-fixes` | A7 “在途调用走完”只对普通失败成立；MCP 会话被服务端终止时在途调用会一起失败（SDK 2.1.0 行为） | 未排期，受 SDK 限制 |
| `spec-audit-fixes` | 需求 5.4 没有在连接层用两个账号验证 | 未排期 |
| `spec-audit-fixes` | 提交 b885dfd 补的 2 个测试只跑了 `CompactStepsTest`，之后没有跑全量 | 由 `ops-hardening` 任务 15 的 `mvn verify` 覆盖 |

## 5. 长期限制

不是任务，记在 `steering/tech.md` 的 Known Limitations：Disruptor 未针对 Java 25 正式声明支持、SQLite 原生访问警告、登录会话不持久化、没有用户管理界面、模型并发默认为 1、strata 与其他任务共用。

`ops-hardening` 完成后会多两条，写进 README：内核语义变化后旧会话回放可能不再逐字节相同；macOS 上 `force` 的掉电保证弱于 Linux。
