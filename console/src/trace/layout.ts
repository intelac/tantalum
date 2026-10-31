// 轨迹页的纯计算：时间条的位置和事件的相对时间。

import type { Trace, TraceSegment } from "../api/types";

export type BarRow = "input" | "model" | "tool";

export interface Bar {
  row: BarRow;
  handler: string;
  /** 占总时长的百分比。 */
  left: number;
  width: number;
  durationMs: number;
  open: boolean;
}

export function rowOf(handler: string): BarRow {
  switch (handler) {
    case "ContextHandler":
      return "input";
    case "ModelHandler":
    case "TimerHandler":
      return "model";
    default:
      return "tool";
  }
}

/** 时间轴的起止：第一个事件到最后一个事件，还在进行时到 now。 */
export function spanOf(trace: Trace, now: number): { start: number; end: number } {
  const first = trace.events[0];
  const last = trace.events[trace.events.length - 1];
  if (first === undefined || last === undefined) return { start: now, end: now };
  const start = new Date(first.at).getTime();
  const openEnded = trace.segments.some((segment) => segment.to === undefined);
  const end = openEnded ? Math.max(now, start) : new Date(last.at).getTime();
  return { start, end: Math.max(end, start + 1) };
}

export function layoutBars(trace: Trace, now: number): Bar[] {
  const { start, end } = spanOf(trace, now);
  const total = end - start;
  return trace.segments.map((segment: TraceSegment) => {
    const from = new Date(segment.from).getTime();
    const to = segment.to === undefined ? end : new Date(segment.to).getTime();
    return {
      row: rowOf(segment.handler),
      handler: segment.handler,
      left: ((from - start) / total) * 100,
      width: Math.max(((to - from) / total) * 100, 0.5),
      durationMs: to - from,
      open: segment.to === undefined,
    };
  });
}

/** 事件相对第一个事件的秒数，一位小数。 */
export function offsetSeconds(iso: string, start: number): string {
  return ((new Date(iso).getTime() - start) / 1000).toFixed(1) + "s";
}
