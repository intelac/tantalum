import { describe, expect, it } from "vitest";
import { formatMs } from "../trace/format";
import { clockTime, formatBytes, formatDuration, formatNumber, relativeTime } from "./format";

// 需求 3.1、3.3。setup.ts 把语言固定成中文；英文、日文的用例在各自的字典加上后补。
const now = new Date("2026-10-31T12:00:00Z");

/** 本地时间的 ISO 串：clockTime 按本地日期判断“当天”。 */
function local(year: number, month: number, day: number, hour: number, minute: number): string {
  return new Date(year, month - 1, day, hour, minute).toISOString();
}

describe("relativeTime", () => {
  // 原来 time.test.ts 的用例，分界不变。改用 Intl 以后的变化：“刚刚”成了 Intl 的“现在”，中文数字和单位之间不再有空格。
  it("picks the coarsest unit that fits", () => {
    expect(relativeTime("2026-10-31T11:59:30Z", now)).toBe("现在");
    expect(relativeTime("2026-10-31T11:55:00Z", now)).toBe("5分钟前");
    expect(relativeTime("2026-10-31T09:00:00Z", now)).toBe("3小时前");
    expect(relativeTime("2026-10-30T09:00:00Z", now)).toBe("昨天");
    expect(relativeTime("2026-10-28T09:00:00Z", now)).toBe("3天前");
    expect(relativeTime("2026-10-25T09:00:00Z", now)).toBe("6天前");
  });
});

describe("formatDuration", () => {
  it("formats milliseconds, seconds and minutes", () => {
    expect(formatDuration(420)).toBe("420 毫秒");
    expect(formatDuration(32_400)).toBe("32 秒");
    expect(formatDuration(95_000)).toBe("1 分 35 秒");
  });

  it("轨迹页的精确耗时取同一份字典", () => {
    expect(formatMs(850)).toBe("850 毫秒");
    expect(formatMs(1_250)).toBe("1.25 秒");
    expect(formatMs(12_340)).toBe("12.3 秒");
  });
});

describe("clockTime", () => {
  const today = new Date(2026, 10, 6, 15, 0);

  it("当天只显示时分，跨天带月日", () => {
    expect(clockTime(local(2026, 11, 6, 9, 10), today)).toBe("09:10");
    expect(clockTime(local(2026, 11, 5, 9, 10), today)).toBe("11/5 09:10");
  });

  it("零点是 00:05，不是 24:05", () => {
    expect(clockTime(local(2026, 11, 6, 0, 5), today)).toBe("00:05");
  });
});

describe("numbers", () => {
  it("千分位和字节数", () => {
    expect(formatNumber(1_234_567)).toBe("1,234,567");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(2048)).toBe("2 KB");
    expect(formatBytes(50 * 1024 * 1024)).toBe("50 MB");
    expect(formatBytes(1.5 * 1024 * 1024)).toBe("1.5 MB");
    expect(formatBytes(3 * 1024 * 1024 * 1024)).toBe("3 GB");
  });
});
