import { describe, expect, it } from "vitest";
import { formatDuration, relativeTime } from "./time";

const now = new Date("2026-10-31T12:00:00Z");

describe("relativeTime", () => {
  it("picks the coarsest unit that fits", () => {
    expect(relativeTime("2026-10-31T11:59:30Z", now)).toBe("刚刚");
    expect(relativeTime("2026-10-31T11:55:00Z", now)).toBe("5 分钟前");
    expect(relativeTime("2026-10-31T09:00:00Z", now)).toBe("3 小时前");
    expect(relativeTime("2026-10-30T09:00:00Z", now)).toBe("昨天");
    expect(relativeTime("2026-10-25T09:00:00Z", now)).toBe("6 天前");
  });
});

describe("formatDuration", () => {
  it("formats milliseconds, seconds and minutes", () => {
    expect(formatDuration(420)).toBe("420 毫秒");
    expect(formatDuration(32_400)).toBe("32 秒");
    expect(formatDuration(95_000)).toBe("1 分 35 秒");
  });
});
