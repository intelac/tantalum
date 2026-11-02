import type { TokenUsage } from "../api/types";
import { zh } from "../text/zh";

/** 1234 → 1.2K，1234567 → 1.2M。 */
export function tokenCount(count: number): string {
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1).replace(/\.0$/, "")}M`;
  if (count >= 1000) return `${(count / 1000).toFixed(1).replace(/\.0$/, "")}K`;
  return String(count);
}

export interface StatusParts {
  turns: string | null;
  tokens: string | null;
  inOut: string | null;
  context: string | null;
  /** 占比达到 80%：下一次模型调用前会自动压缩，状态行变色提示。 */
  warn: boolean;
}

export const WARN_PERCENT = 80;

/**
 * 状态行的几段文字。上下文占比 = 最近一次调用的输入（含缓存读取）/ 窗口大小；窗口未知时不显示。
 */
export function statusParts(turns: number, usage: TokenUsage | undefined, contextWindow: number | undefined): StatusParts {
  const input = usage === undefined ? 0 : usage.input + usage.cacheRead + usage.cacheWrite;
  const total = usage === undefined ? 0 : input + usage.output;
  const percent = usage !== undefined && contextWindow !== undefined && contextWindow > 0 ? Math.min(100, Math.round((input / contextWindow) * 100)) : null;
  return {
    turns: turns > 0 ? zh.status.turns(turns) : null,
    tokens: usage === undefined ? null : zh.status.tokens(tokenCount(total)),
    inOut: usage === undefined ? null : zh.status.inOut(tokenCount(input), tokenCount(usage.output)),
    context: percent === null ? null : zh.status.context(percent),
    warn: percent !== null && percent >= WARN_PERCENT,
  };
}
