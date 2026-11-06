// 轨迹页的数字和时刻格式，照 DSH（`TrajectoryTable.tsx` 的 formatDuration、formatStartedAt）。

import { formatPreciseDuration } from "../text/format";

/** 毫秒数：1 秒以下取整显示毫秒，10 秒以下两位小数，再长一位小数；单位按当前语言。 */
export function formatMs(milliseconds: number): string {
  return formatPreciseDuration(milliseconds);
}

/** 两个 ISO 时刻之间的毫秒数；缺一个就是 undefined。 */
export function between(from: string | undefined, to: string | undefined): number | undefined {
  if (from === undefined || to === undefined) return undefined;
  const span = Date.parse(to) - Date.parse(from);
  return Number.isFinite(span) ? span : undefined;
}

/** 本地时间 `YYYY-MM-DD HH:MM:SS.mmm`。 */
export function formatLocal(iso: string): string {
  const time = new Date(iso);
  const two = (value: number) => String(value).padStart(2, "0");
  return (
    `${time.getFullYear()}-${two(time.getMonth() + 1)}-${two(time.getDate())} ` +
    `${two(time.getHours())}:${two(time.getMinutes())}:${two(time.getSeconds())}.${String(time.getMilliseconds()).padStart(3, "0")}`
  );
}

/** Unix 时间戳，秒，三位小数。 */
export function formatUnix(iso: string): string {
  return (Date.parse(iso) / 1_000).toFixed(3);
}

/** token 数：一千以上用 k，一百万以上用 M。 */
export function formatTokens(count: number): string {
  if (count < 1_000) return String(count);
  if (count < 1_000_000) return `${(count / 1_000).toFixed(count < 10_000 ? 1 : 0)}k`;
  return `${(count / 1_000_000).toFixed(1)}M`;
}

/** 输出速度 tok/s；没有生成时长时为 undefined（DSH“时长过短”）。 */
export function formatSpeed(outputTokens: number, generationMs: number | undefined): string | undefined {
  if (generationMs === undefined || generationMs <= 0) return undefined;
  return `${(outputTokens / (generationMs / 1_000)).toFixed(1)} tok/s`;
}

/** ISO-8601 时长（后端 `Duration`，如 `PT2S`、`PT1M30.5S`）转毫秒；认不出来为 undefined。 */
export function parseIsoDuration(text: string | undefined): number | undefined {
  if (text === undefined) return undefined;
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(text);
  if (match === null) return undefined;
  const [, hours = "0", minutes = "0", seconds = "0"] = match;
  return (Number(hours) * 3600 + Number(minutes) * 60 + Number(seconds)) * 1_000;
}
