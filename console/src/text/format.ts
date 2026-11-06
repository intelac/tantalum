// 日期、时间、数字按当前语言格式化（console-i18n I7）：用 Intl，照 pi chord `surfaces/format.ts`。
// 时长（“1 分 5 秒”）写在字典里：Intl.DurationFormat 在 jsdom 和部分浏览器里还没有。

import { currentLanguage, currentText } from "./index";
import { intlLocale } from "./language";

function locale(): string {
  return intlLocale(currentLanguage());
}

/** 千分位：`1,234,567`。 */
export function formatNumber(value: number): string {
  return new Intl.NumberFormat(locale()).format(value);
}

const KIB = 1024;
const MIB = 1024 * 1024;
const GIB = 1024 * MIB;

/** 字节数按 1024 进位选 B、KB、MB、GB；单位三种语言写法相同，数字部分按语言。 */
export function formatBytes(bytes: number): string {
  if (bytes < KIB) return `${formatNumber(bytes)} B`;
  if (bytes < MIB) return `${formatNumber(Math.round(bytes / KIB))} KB`;
  const [unit, size] = bytes < GIB ? ["MB", bytes / MIB] : ["GB", bytes / GIB];
  return `${formatNumber(Number.isInteger(size) ? size : Number(size.toFixed(1)))} ${unit}`;
}

/** 几个里选一个：“低、中或高”“low, medium, or high”。 */
export function formatChoices(items: readonly string[], language = currentLanguage()): string {
  return new Intl.ListFormat(intlLocale(language), { type: "disjunction" }).format(items);
}

/** 消息下方的时间：当天只显示时分，否则带月日。 */
export function clockTime(iso: string, now: Date = new Date()): string {
  const date = new Date(iso);
  const sameDay = date.toDateString() === now.toDateString();
  // hourCycle 而不是 hour12:false：部分引擎在 hour12:false 下把零点显示成 24:05。
  const time = date.toLocaleTimeString(locale(), { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  return sameDay ? time : `${date.toLocaleDateString(locale(), { month: "numeric", day: "numeric" })} ${time}`;
}

/** 侧栏里的相对时间：取能放下的最粗的单位；一分钟以内是“现在”。 */
export function relativeTime(iso: string, now: Date = new Date()): string {
  const format = new Intl.RelativeTimeFormat(locale(), { numeric: "auto" });
  const minutes = Math.floor((now.getTime() - new Date(iso).getTime()) / 60_000);
  if (minutes < 1) return format.format(0, "second");
  if (minutes < 60) return format.format(-minutes, "minute");
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return format.format(-hours, "hour");
  const days = Math.floor(hours / 24);
  // 24–47 小时算“昨天”，和原来的分界一样。
  return format.format(days < 2 ? -1 : -days, "day");
}

/** 过程区和工具行的耗时：1 秒以下毫秒，1 分钟以下秒，再长分加秒。 */
export function formatDuration(ms: number): string {
  const text = currentText().time.duration;
  if (ms < 1000) return text.milliseconds(Math.max(0, Math.round(ms)));
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return text.seconds(seconds);
  return text.minutesSeconds(Math.floor(seconds / 60), seconds % 60);
}

/** 轨迹页和统计页的精确耗时：1 秒以下取整显示毫秒，10 秒以下两位小数，再长一位小数（照 DSH `TrajectoryTable.tsx`）。 */
export function formatPreciseDuration(ms: number): string {
  const text = currentText().time.duration;
  if (ms < 1_000) return text.milliseconds(Math.round(ms));
  return text.preciseSeconds((ms / 1_000).toFixed(ms < 10_000 ? 2 : 1));
}
