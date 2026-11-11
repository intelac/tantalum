// 本轮用量的数字（input-area 需求 5）：合计、紧凑写法、缓存命中率。写法照 DSH `chat/token-format.ts`。

import type { TokenUsage } from "../api/types";

/** 这一轮一共多少 token：三种输入加输出。 */
export function total(usage: TokenUsage): number {
  return usage.input + usage.cacheRead + usage.cacheWrite + usage.output;
}

function scaled(value: number): string {
  return value >= 100 ? String(Math.round(value)) : String(Math.round(value * 10) / 10);
}

/** 紧凑写法：一千以下写原数，以上 `12.2K`、`517K`、`1.2M`。 */
export function compact(count: number): string {
  if (count < 1000) return String(count);
  // 四舍五入后到了 1000K 的写成 1M
  if (count < 999_500) return `${scaled(count / 1000)}K`;
  return `${scaled(count / 1_000_000)}M`;
}

/**
 * 缓存命中率，一位小数的百分数（不带 %）：命中的输入 ÷ 全部输入。没有输入返回 null；
 * 没有全部命中就不写 100，最多 99.9。
 */
export function cacheHit(usage: TokenUsage): string | null {
  const prompt = usage.input + usage.cacheRead + usage.cacheWrite;
  if (prompt === 0) return null;
  if (usage.cacheRead === prompt) return "100";
  const tenths = Math.min(999, Math.round((usage.cacheRead * 1000) / prompt));
  return (tenths / 10).toFixed(1);
}
