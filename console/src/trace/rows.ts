// 纯函数：记录 → 轮、步、行。分组和收起照 DSH（TrajectoryTable.tsx 的 collapseTurnRecords、collapseCallRecords、summarizeTurn）。

import type { ModelRequest, RunHead, ToolCall, TraceRecord } from "../api/types";
import { comparePos, runStart } from "./TraceStore";

/** 轮的摘要：有请求的步数、工具调用数。 */
export interface TurnSummary {
  steps: number;
  toolCalls: number;
}

export type Row =
  /** 一轮的标题；`turn` 为空是手动压缩，显示在“轮次之间”。 */
  | { type: "turn"; key: string; run: string; turn?: number; head?: RunHead; collapsed: boolean; summary: TurnSummary }
  /** 第几步，或一个压缩组。 */
  | { type: "group"; key: string; run: string; step?: number; compaction?: string }
  /** 一条记录。请求行带“请求 #n”分隔，`collapsed` 表示它的工具调用收起了。 */
  | { type: "record"; key: string; record: TraceRecord; collapsed?: boolean }
  /** 收起后留下的一行摘要；`hidden` 是被收起的记录，搜索命中时标出这一行。 */
  | { type: "summary"; key: string; of: "turn" | "calls"; summary: TurnSummary; names: string[]; hidden: string[] };

/** 工具名：MCP 工具写成“服务器/工具”，去掉 `mcp__服务器__` 前缀；名字被截短加哈希时，拆出来的只是前缀，原样保留其余部分。 */
export function toolLabel(record: ToolCall): string {
  const prefix = record.server === undefined ? "" : `mcp__${record.server}__`;
  return prefix !== "" && record.name.startsWith(prefix) ? `${record.server}/${record.name.slice(prefix.length)}` : record.name;
}

/** 请求前的记录（用户输入、插话、上下文、system、模型调用前的钩子和它要的工作）排在一步里请求行之前。 */
function beforeRequest(record: TraceRecord): boolean {
  switch (record.kind) {
    case "user":
    case "context":
    case "system":
      return true;
    case "hook":
    case "work":
      return record.point === "BEFORE_MODEL_CALL";
    default:
      return false;
  }
}

function groupKey(record: TraceRecord): string {
  if (record.step !== undefined) return `s${record.step}`;
  if (record.compaction !== undefined) return `c${record.compaction}`;
  return "-";
}

function summarize(records: readonly TraceRecord[]): TurnSummary {
  const steps = new Set(records.filter((record) => record.kind === "request").map(groupKey)).size;
  return { steps, toolCalls: records.filter((record) => record.kind === "tool").length };
}

interface RunRecords {
  run: string;
  head?: RunHead;
  turn?: number;
  records: TraceRecord[];
}

function byRun(records: readonly TraceRecord[]): RunRecords[] {
  const runs = new Map<string, RunRecords>();
  for (const record of records) {
    let entry = runs.get(record.run);
    if (entry === undefined) {
      entry = { run: record.run, records: [] };
      runs.set(record.run, entry);
    }
    if (record.turn !== undefined) entry.turn = record.turn;
    if (record.kind === "run") entry.head = record;
    else entry.records.push(record);
  }
  return [...runs.values()].sort((a, b) => runStart(a.run) - runStart(b.run));
}

/**
 * 一轮里的步和压缩组。组按请求行的位置排（没有请求的组按第一条记录）：自动压缩发生在下一步的请求之前，
 * 即使这一步的用户输入更早，请求编号也保持递增。组内请求前的记录在前，其余按位置。
 */
function groups(records: readonly TraceRecord[]): { key: string; records: TraceRecord[] }[] {
  const map = new Map<string, TraceRecord[]>();
  for (const record of records) {
    const key = groupKey(record);
    const members = map.get(key);
    if (members === undefined) map.set(key, [record]);
    else members.push(record);
  }
  const anchor = (members: readonly TraceRecord[]) => (members.find((record) => record.kind === "request") ?? members[0])?.pos ?? "0";
  return [...map.entries()]
    .map(([key, members]) => ({
      key,
      anchor: anchor(members),
      records: [...members].sort((a, b) => Number(beforeRequest(b)) - Number(beforeRequest(a)) || comparePos(a.pos, b.pos)),
    }))
    .sort((a, b) => comparePos(a.anchor, b.anchor));
}

/** 一个请求收起时藏起来的记录：它的工具调用，以及拦这些调用的钩子和钩子要的工作。 */
function callRecords(request: ModelRequest, members: readonly TraceRecord[]): TraceRecord[] {
  const tools = new Set(members.filter((record) => record.kind === "tool" && record.request === request.id).map((record) => record.id));
  const hooks = new Set(members.filter((record) => record.kind === "hook" && record.tool !== undefined && tools.has(record.tool)).map((record) => record.id));
  return members.filter(
    (record) =>
      tools.has(record.id) || hooks.has(record.id) || (record.kind === "work" && record.decision !== undefined && hooks.has(record.decision)),
  );
}

/**
 * 把排好序的记录分成行。{@code collapsedTurns} 是收起的运行，{@code collapsedCalls} 是收起了工具调用的请求记录 ID。
 * 收起的轮留标题、system 行、第一条内容和一行摘要；收起的调用留请求行和一行摘要。
 */
export function buildLedger(records: readonly TraceRecord[], collapsedTurns: ReadonlySet<string>, collapsedCalls: ReadonlySet<string>): Row[] {
  const rows: Row[] = [];
  for (const run of byRun(records)) {
    const collapsed = collapsedTurns.has(run.run);
    rows.push({ type: "turn", key: `turn:${run.run}`, run: run.run, turn: run.turn, head: run.head, collapsed, summary: summarize(run.records) });
    if (collapsed) {
      const ordered = groups(run.records).flatMap((group) => group.records);
      const content = ordered.filter((record) => record.kind !== "system");
      for (const record of ordered) {
        if (record.kind === "system" || record === content[0]) rows.push({ type: "record", key: record.id, record });
      }
      const hidden = content.slice(1);
      if (hidden.length > 0) {
        rows.push({ type: "summary", key: `sum:${run.run}`, of: "turn", summary: summarize(hidden), names: [], hidden: hidden.map((record) => record.id) });
      }
      continue;
    }
    for (const group of groups(run.records)) {
      const first = group.records[0];
      rows.push({ type: "group", key: `group:${run.run}:${group.key}`, run: run.run, step: first?.step, compaction: first?.compaction });
      const hiddenIds = new Set<string>();
      for (const record of group.records) {
        if (hiddenIds.has(record.id)) continue;
        if (record.kind !== "request" || !collapsedCalls.has(record.id)) {
          rows.push({ type: "record", key: record.id, record });
          continue;
        }
        const calls = callRecords(record, group.records);
        rows.push({ type: "record", key: record.id, record, collapsed: calls.length > 0 });
        if (calls.length === 0) continue;
        for (const call of calls) hiddenIds.add(call.id);
        const names = [...new Set(calls.flatMap((call) => (call.kind === "tool" ? [toolLabel(call)] : [])))];
        rows.push({ type: "summary", key: `sum:${record.id}`, of: "calls", summary: summarize(calls), names, hidden: calls.map((call) => call.id) });
      }
    }
  }
  return rows;
}

/** 让这些记录可见要展开的轮和调用（搜索跳转、按记录定位时用）。 */
export function expansionFor(records: readonly TraceRecord[], ids: Iterable<string>): { turns: Set<string>; calls: Set<string> } {
  const byId = new Map(records.map((record) => [record.id, record]));
  const turns = new Set<string>();
  const calls = new Set<string>();
  const toolRequest = (toolId: string | undefined) => {
    const tool = toolId === undefined ? undefined : byId.get(toolId);
    if (tool?.kind === "tool" && tool.request !== undefined) calls.add(tool.request);
  };
  for (const id of ids) {
    const record = byId.get(id);
    if (record === undefined) continue;
    turns.add(record.run);
    if (record.kind === "tool") toolRequest(record.id);
    if (record.kind === "hook") toolRequest(record.tool);
    if (record.kind === "work" && record.decision !== undefined) {
      const hook = byId.get(record.decision);
      if (hook?.kind === "hook") toolRequest(hook.tool);
    }
  }
  return { turns, calls };
}
