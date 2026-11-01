import { zh } from "./zh";

/** 侧栏里的相对时间。 */
export function relativeTime(iso: string, now: Date = new Date()): string {
  const diff = now.getTime() - new Date(iso).getTime();
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 1) return zh.time.justNow;
  if (minutes < 60) return zh.time.minutesAgo(minutes);
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return zh.time.hoursAgo(hours);
  const days = Math.floor(hours / 24);
  if (days < 2) return zh.time.yesterday;
  return zh.time.daysAgo(days);
}

/** 过程区和工具行的耗时。 */
export function formatDuration(ms: number): string {
  if (ms < 1000) return zh.time.milliseconds(Math.max(0, Math.round(ms)));
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return zh.time.seconds(seconds);
  return zh.time.minutesSeconds(Math.floor(seconds / 60), seconds % 60);
}

/** 消息下方的时间：当天只显示时分，否则带日期。 */
export function clockTime(iso: string, now: Date = new Date()): string {
  const date = new Date(iso);
  const sameDay = date.toDateString() === now.toDateString();
  // hourCycle 而不是 hour12:false：部分引擎在 hour12:false 下把零点显示成 24:05。
  const time = date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  return sameDay ? time : `${date.toLocaleDateString("zh-CN", { month: "numeric", day: "numeric" })} ${time}`;
}
