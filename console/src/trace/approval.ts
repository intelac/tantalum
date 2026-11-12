// 等批准、等回答并进工具那一行（trace-rounds 需求 3）。纯函数：哪些记录并进哪次工具调用，以及那一行上多写的一句话。
// “问了用户”的记录带着它是为哪次工具调用问的（`tool`）；为这次调用而问的那条钩子决定也跟着并进去。

import type { HookDecision, QuestionRecord, ToolCall, TraceRecord } from "../api/types";
import type { Text } from "../text";
import { between, formatMs } from "./format";
import { comparePos } from "./TraceStore";

export interface Approval {
  question: QuestionRecord;
  /** 为这次调用问用户的那条钩子决定；`ask_user` 没有。 */
  hook?: HookDecision;
}

export interface Folding {
  /** 工具记录 id → 并进它的问题（和钩子决定）。 */
  byTool: ReadonlyMap<string, Approval>;
  /** 被并掉的记录 id → 它并进的工具记录 id。 */
  into: ReadonlyMap<string, string>;
}

export const NO_FOLDING: Folding = { byTool: new Map(), into: new Map() };

/**
 * 找出能并的：问题的 `tool` 指向一条在场的工具记录才并（旧服务器不给 `tool`、工具不在已加载的页里，都照旧各占一行）。
 * 一个工具被问了不止一次时只并最后一次，前面的照旧各占一行。
 */
export function approvalsOf(records: readonly TraceRecord[]): Folding {
  const tools = new Set(records.flatMap((record) => (record.kind === "tool" ? [record.id] : [])));
  const byTool = new Map<string, Approval>();
  for (const record of records) {
    if (record.kind !== "question" || record.tool === undefined || !tools.has(record.tool)) continue;
    const current = byTool.get(record.tool);
    if (current === undefined || comparePos(current.question.pos, record.pos) < 0) byTool.set(record.tool, { question: record });
  }
  if (byTool.size === 0) return NO_FOLDING;
  for (const record of records) {
    if (record.kind !== "hook" || record.result !== "ASK" || record.tool === undefined) continue;
    const approval = byTool.get(record.tool);
    // 问在前、答在后：取这个问题之前最近的那条“问用户”的决定
    if (approval === undefined || approval.question.run !== record.run || comparePos(record.pos, approval.question.pos) > 0) continue;
    if (approval.hook === undefined || comparePos(approval.hook.pos, record.pos) < 0) approval.hook = record;
  }
  const into = new Map<string, string>();
  for (const [tool, approval] of byTool) {
    into.set(approval.question.id, tool);
    if (approval.hook !== undefined) into.set(approval.hook.id, tool);
  }
  return { byTool, into };
}

/** 模型自己用 `ask_user` 问的（回答就是工具结果），还是插件在调用前问要不要放行。 */
export function isAskUser(approval: Approval): boolean {
  return approval.hook === undefined && approval.question.askedBy === "ask_user";
}

/** 等了多久：从问到答；还在等就到 `now`。 */
export function waitedMs(approval: Approval, now: number): number | undefined {
  const question = approval.question;
  if (question.end !== undefined) return between(question.at, question.end);
  const start = Date.parse(question.at);
  return Number.isFinite(start) ? Math.max(0, now - start) : undefined;
}

/**
 * 工具那一行上多写的一句：等了多久、结果怎样。工具自己的“用时”列不含这段等人的时间。
 *
 * | 问题的状态 | 插件问的 | ask_user |
 * |---|---|---|
 * | 还在等 | 等批准 12 秒 | 等回答 12 秒 |
 * | 答了，工具没被拦 | 等了 3.4 秒 · 已批准 | 等了 3.4 秒 · 答了 keep |
 * | 答了，工具被拦下 | 等了 3.4 秒 · 已拒绝 | — |
 * | 过期 / 取消 | 等了 30 秒 · 没等到回答 / 已取消 | 同左 |
 */
export function approvalText(approval: Approval, tool: ToolCall, now: number, text: Text["trace"]["approval"]): string {
  const question = approval.question;
  const waited = waitedMs(approval, now);
  const time = waited === undefined ? "" : formatMs(waited);
  const ask = isAskUser(approval);
  if (question.status === "WAITING") return ask ? text.waitingAnswer(time) : text.waiting(time);
  const outcome =
    question.status === "EXPIRED"
      ? text.expired
      : question.status === "CANCELLED"
        ? text.cancelled
        : ask
          ? text.answered([question.choice, question.text].filter((part) => part !== undefined && part !== "").join(" · "))
          : tool.status === "BLOCKED"
            ? text.denied
            : text.approved;
  return time === "" ? outcome : `${text.waited(time)} · ${outcome}`;
}
