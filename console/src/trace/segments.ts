// 时间概览的计算（纯函数），照 DSH `timeline.ts` 和 `TrajectoryTimeline.tsx`：
// 两种画法——按实际时长（压掉没有任何活动的空档）、每个操作等宽；闭区间过滤；以指针为锚点缩放；平移。
// 在 DSH 的三道（输入、模型、工具）之外加一道“插件”，放插件工作和钩子决定。

import type { TraceRecord } from "../api/types";
import type { Row } from "./rows";

export type TimelineMode = "duration" | "sequence";

export interface TimeRange {
  start: number;
  end: number;
}

export const LANES = ["input", "model", "tool", "plugin"] as const;

/** 一条记录在当前画法下的段。`firstToken` 是助手段首 token 的位置（只在按实际时长时有）；`running` 的段只画开始标记。 */
export interface Span extends TimeRange {
  id: string;
  record: TraceRecord;
  lane: number;
  running: boolean;
  firstToken?: number;
}

export interface TimelineModel extends TimeRange {
  spans: Span[];
  /** 每一轮的起点，画轮次分隔线。 */
  turns: { run: string; turn?: number; time: number }[];
}

/** 最少显示几个操作宽（DSH MINIMUM_ZOOM_OPERATIONS），按实际时长时最少 20 毫秒。 */
export const MINIMUM_ZOOM_OPERATIONS = 4;
export const MINIMUM_ZOOM_MS = 20;
/** 滚轮缩放系数 exp(deltaY × 0.0015)。 */
export const ZOOM_RATE = 0.0015;

export function laneOf(record: TraceRecord): number {
  switch (record.kind) {
    case "system":
    case "user":
    case "context":
      return 0;
    case "request":
    case "compacted":
      return 1;
    case "tool":
      return 2;
    default:
      return 3;
  }
}

/** 按记录表的顺序（不收起时）取出要画的记录；运行本身不画，它是轮的标题。 */
function ordered(rows: readonly Row[]): { record: TraceRecord; run: string }[] {
  return rows.flatMap((row) => (row.type === "record" && row.record.kind !== "run" ? [{ record: row.record, run: row.record.run }] : []));
}

function timeOf(iso: string | undefined): number | undefined {
  if (iso === undefined) return undefined;
  const value = Date.parse(iso);
  return Number.isFinite(value) ? value : undefined;
}

function firstTokenOf(record: TraceRecord): number | undefined {
  return record.kind === "request" ? timeOf(record.attempts.at(-1)?.firstTokenAt) : undefined;
}

/** 由不收起的记录表行算出整个时间域的模型；没有可画的记录时为 null。 */
export function buildTimeline(rows: readonly Row[], mode: TimelineMode): TimelineModel | null {
  const items = ordered(rows);
  if (items.length === 0) return null;
  if (mode === "sequence") {
    const turns: TimelineModel["turns"] = [];
    const spans = items.map(({ record, run }, index): Span => {
      if (turns.at(-1)?.run !== run) turns.push({ run, turn: record.turn, time: index });
      return { id: record.id, record, lane: laneOf(record), running: record.end === undefined, start: index, end: index + 1 };
    });
    return { start: 0, end: spans.length, spans, turns };
  }

  const raw = items.flatMap(({ record, run }) => {
    const start = timeOf(record.at);
    if (start === undefined) return [];
    const end = timeOf(record.end);
    return [{ record, run, start, end: end === undefined ? start : Math.max(start, end), running: end === undefined }];
  });
  if (raw.length === 0) return null;
  // 压掉没有任何活动的空档：按开始排序扫一遍，每段前面累计的空档都减掉（DSH deriveTimedTimeline）。
  const removed = new Map<(typeof raw)[number], number>();
  let idle = 0;
  let covered: number | null = null;
  for (const entry of [...raw].sort((a, b) => a.start - b.start || a.end - b.end)) {
    if (covered !== null && entry.start > covered) idle += entry.start - covered;
    removed.set(entry, idle);
    covered = covered === null ? entry.end : Math.max(covered, entry.end);
  }
  const turns: TimelineModel["turns"] = [];
  const spans = raw.map((entry): Span => {
    const offset = removed.get(entry) ?? 0;
    const first = firstTokenOf(entry.record);
    const span: Span = {
      id: entry.record.id,
      record: entry.record,
      lane: laneOf(entry.record),
      running: entry.running,
      start: entry.start - offset,
      end: entry.end - offset,
    };
    if (first !== undefined && first >= entry.start && first <= entry.end) span.firstToken = first - offset;
    const last = turns.at(-1);
    if (last?.run !== entry.run) turns.push({ run: entry.run, turn: entry.record.turn, time: span.start });
    else last.time = Math.min(last.time, span.start);
    return span;
  });
  return {
    start: Math.min(...spans.map((span) => span.start)),
    end: Math.max(...spans.map((span) => span.end)),
    spans,
    turns,
  };
}

/** 和闭区间相交的记录：`start <= range.end && end >= range.start`（DSH trajectoryTimelineFocusIndexes）。 */
export function spansInRange(model: TimelineModel, range: TimeRange): ReadonlySet<string> {
  return new Set(model.spans.filter((span) => span.start <= range.end && span.end >= range.start).map((span) => span.id));
}

/** 离某个时刻最近的段（点空白处时让记录表滚过去）。 */
export function nearestSpan(model: TimelineModel, time: number): Span {
  const distance = (span: Span) => (time < span.start ? span.start - time : time > span.end ? time - span.end : 0);
  return model.spans.reduce((best, span) => (distance(span) < distance(best) ? span : best));
}

/** 拖选不足最小宽时，以点击位置为中心展开到最小宽：一个操作宽或视口宽中较小的。 */
export function minimumSelection(model: TimelineModel, domain: number): number {
  return Math.min(domain, (model.end - model.start) / model.spans.length);
}

export function centered(center: number, width: number, model: TimeRange): TimeRange {
  const clamped = Math.min(model.end - model.start, Math.max(0, width));
  const start = Math.min(Math.max(center - clamped / 2, model.start), model.end - clamped);
  return { start, end: start + clamped };
}

/**
 * 以指针为锚点缩放。{@code viewport} 为空表示看整个时间域；缩到比整个域还宽时回到 null。
 * {@code anchor} 是指针在轨道上的比例（0–1）。
 */
export function zoomed(model: TimelineModel, viewport: TimeRange | null, anchor: number, deltaY: number, mode: TimelineMode): TimeRange | null {
  const full = Math.max(1, model.end - model.start);
  const domainStart = viewport?.start ?? model.start;
  const domain = viewport === null ? full : viewport.end - viewport.start;
  const minimum = Math.min(mode === "sequence" ? MINIMUM_ZOOM_OPERATIONS : MINIMUM_ZOOM_MS, full);
  const next = Math.min(full, Math.max(minimum, domain * Math.exp(deltaY * ZOOM_RATE)));
  if (next >= full * 0.999) return null;
  const at = domainStart + anchor * domain;
  const start = Math.min(Math.max(at - anchor * next, model.start), model.end - next);
  return { start, end: start + next };
}

/** 平移：视口宽不变，限制在时间域里。 */
export function panned(model: TimelineModel, viewport: TimeRange, start: number): TimeRange {
  const width = viewport.end - viewport.start;
  const clamped = Math.min(Math.max(start, model.start), model.end - width);
  return { start: clamped, end: clamped + width };
}

/** 选中的段在视口外时，平移到刚好看得见它。 */
export function revealed(model: TimelineModel, viewport: TimeRange | null, span: Span | undefined): TimeRange | null {
  if (viewport === null || span === undefined) return viewport;
  if (span.end > viewport.start && span.start < viewport.end) return viewport;
  const width = Math.max(1, viewport.end - viewport.start);
  return panned(model, viewport, span.end <= viewport.start ? span.start : span.end - width);
}
