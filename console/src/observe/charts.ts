// 按天的两张图的数据（observe-page 需求 3.2）：把服务器给的天（没有运行的天不给）补成连续的天，算刻度。纯函数。
// 天按浏览器所在时区，和请求汇总时带的 tz 一致。

import type { RunEnd, RunsSummary } from "../api/types";

export interface DayBar {
  /** YYYY-MM-DD，浏览器时区。 */
  day: string;
  ends: Partial<Record<RunEnd, number>>;
  runs: number;
  tokens: number;
}

/** 堆叠的顺序，从下到上：相邻两段在色盲下也分得开（校验过），失败放在最上面最显眼。 */
export const STACK: readonly RunEnd[] = ["COMPLETED", "RUNNING", "CANCELLED", "STOPPED", "FAILED"];

export const MAX_DAYS = 90;

function key(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function parseDay(day: string): Date {
  const [year = 1970, month = 1, date = 1] = day.split("-").map(Number);
  return new Date(year, month - 1, date);
}

/**
 * 从 {@code from} 那天（没有起点时从第一个有运行的天）到今天，每天一根柱；最多 {@link MAX_DAYS} 根，多了只留最近的。
 */
export function fillDays(summary: Pick<RunsSummary, "days">, from: Date | undefined, now: number): DayBar[] {
  const byDay = new Map(summary.days.map((day) => [day.day, day]));
  const today = new Date(now);
  today.setHours(0, 0, 0, 0);
  const firstDay = summary.days[0]?.day;
  let cursor = from !== undefined ? new Date(from) : firstDay !== undefined ? parseDay(firstDay) : new Date(today);
  cursor.setHours(0, 0, 0, 0);
  const earliest = new Date(today);
  earliest.setDate(earliest.getDate() - (MAX_DAYS - 1));
  if (cursor < earliest) cursor = earliest;
  const bars: DayBar[] = [];
  while (cursor <= today) {
    const day = key(cursor);
    const found = byDay.get(day);
    const ends = found?.ends ?? {};
    bars.push({ day, ends, runs: Object.values(ends).reduce((total, count) => total + (count ?? 0), 0), tokens: found?.tokens ?? 0 });
    cursor = new Date(cursor);
    cursor.setDate(cursor.getDate() + 1);
  }
  return bars;
}

/** 坐标轴的上限和刻度：1、2、5 乘 10 的幂，三到四个刻度，上限不小于最大值。 */
export function scale(max: number): { top: number; ticks: number[] } {
  if (max <= 0) return { top: 1, ticks: [0, 1] };
  const rough = max / 3;
  const power = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 5, 10].map((factor) => factor * power).find((candidate) => candidate >= rough) ?? 10 * power;
  const top = Math.ceil(max / step) * step;
  const ticks: number[] = [];
  for (let value = 0; value <= top + step / 2; value += step) ticks.push(Math.round(value * 1e6) / 1e6);
  return { top, ticks };
}
