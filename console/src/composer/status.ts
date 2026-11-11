import type { ContextUsage } from "../api/types";
import { currentText } from "../text";

/** 1234 → 1.2K，1234567 → 1.2M。 */
export function tokenCount(count: number): string {
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1).replace(/\.0$/, "")}M`;
  if (count >= 1000) return `${(count / 1000).toFixed(1).replace(/\.0$/, "")}K`;
  return String(count);
}

/** 48210 → 48,210。三种语言都用逗号分千位。 */
export function grouped(count: number): string {
  return Math.round(count).toLocaleString("en-US");
}

export interface StatusParts {
  context: string | null;
  /** 上下文占窗口的百分比（0–100）；不知道时为 null。 */
  percent: number | null;
  /** 快到自动压缩的点了：状态行变色提示。 */
  warn: boolean;
  /** 自动压缩的点占窗口的百分比，悬停说明用；不知道压缩点时为 null。 */
  pointPercent: number | null;
}

/** 不知道用户选的压缩点时（设置还没读到），按窗口的 80% 提醒。 */
export const WARN_PERCENT = 80;
/** 知道压缩点时，到它的 90% 提醒（compaction-threshold T9）。 */
export const WARN_OF_POINT = 0.9;

/**
 * 状态行的上下文占比 = 已用 / 窗口大小。已用是服务器算的（最近一次模型调用实测的加之后新增的估算，和压缩判断用同一个数）；
 * 还没调过模型或窗口未知时不显示。{@code point} 是这个用户选的自动压缩点（token 数）。
 */
export function statusParts(used: number | undefined, contextWindow: number | undefined, point?: number): StatusParts {
  const t = currentText();
  const percent = used !== undefined && contextWindow !== undefined && contextWindow > 0 ? Math.min(100, Math.round((used / contextWindow) * 100)) : null;
  return {
    context: percent === null ? null : t.status.context(percent),
    percent,
    warn: percent !== null && used !== undefined && (point === undefined ? percent >= WARN_PERCENT : used >= point * WARN_OF_POINT),
    pointPercent: point === undefined || contextWindow === undefined || contextWindow <= 0 ? null : Math.round((point / contextWindow) * 100),
  };
}

export interface MeterPart {
  key: "system" | "tools" | "messages";
  tokens: number;
  /** 这一项占已用的百分比，画条用；三项加起来 100。 */
  share: number;
}

/** 明细的三项，按系统提示、工具定义、消息的顺序；没有明细时为空。 */
export function meterParts(usage: ContextUsage | undefined): MeterPart[] {
  if (usage?.parts === undefined || usage.used === undefined || usage.used <= 0) return [];
  const used = usage.used;
  return (["system", "tools", "messages"] as const).map((key) => ({ key, tokens: usage.parts![key], share: (usage.parts![key] / used) * 100 }));
}

export type QuotaLevel = "ok" | "warn" | "over";

/** 今日用量占上限的百分比（往下取整，没到上限不显示 100%）和它的档：90% 起提醒，到了上限是用完。 */
export function quota(used: number, limit: number): { percent: number; level: QuotaLevel } {
  const percent = limit <= 0 ? 100 : Math.floor((used / limit) * 100);
  return { percent, level: used >= limit ? "over" : percent >= 90 ? "warn" : "ok" };
}
