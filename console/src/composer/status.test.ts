import { describe, expect, it } from "vitest";
import { grouped, meterParts, quota, statusParts, tokenCount } from "./status";

describe("status", () => {
  it("compacts counts", () => {
    expect(tokenCount(999)).toBe("999");
    expect(tokenCount(1234)).toBe("1.2K");
    expect(tokenCount(18000)).toBe("18K");
    expect(tokenCount(2_500_000)).toBe("2.5M");
  });

  it("groups thousands", () => {
    expect(grouped(999)).toBe("999");
    expect(grouped(48210)).toBe("48,210");
    expect(grouped(300_000_000)).toBe("300,000,000");
  });

  it("shows the context share when the window is known", () => {
    expect(statusParts(18000, 131072)).toEqual({ context: "上下文 14%", percent: 14, warn: false, pointPercent: null });
  });

  it("omits what it does not know", () => {
    expect(statusParts(undefined, 131072)).toEqual({ context: null, percent: null, warn: false, pointPercent: null });
    expect(statusParts(100, undefined).context).toBeNull();
    expect(statusParts(500000, 1000).context).toBe("上下文 100%");
  });

  it("warns at 90 percent of the chosen compaction point", () => {
    // 窗口 1M、压缩点 50 万：45 万开始提醒
    const at = (used: number) => statusParts(used, 1_000_000, 500_000);
    expect(at(449_999).warn).toBe(false);
    expect(at(450_000).warn).toBe(true);
    expect(at(450_000).context).toBe("上下文 45%");
    expect(at(1).pointPercent).toBe(50);
    // 窗口的 80% 离 93.6% 的压缩点还远
    expect(statusParts(800_000, 1_000_000, 936_000).warn).toBe(false);
  });

  it("warns from 80 percent on while the compaction point is unknown", () => {
    expect(statusParts(790, 1000).warn).toBe(false);
    expect(statusParts(800, 1000).warn).toBe(true);
    expect(statusParts(800, undefined).warn).toBe(false);
  });

  it("lists the three parts with their share of what is used", () => {
    const parts = meterParts({ used: 48210, window: 200000, limit: 160000, parts: { system: 5200, tools: 9100, messages: 33910 } });
    expect(parts.map((part) => [part.key, part.tokens])).toEqual([
      ["system", 5200],
      ["tools", 9100],
      ["messages", 33910],
    ]);
    expect(Math.round(parts.reduce((sum, part) => sum + part.share, 0))).toBe(100);
    expect(meterParts({})).toEqual([]);
    expect(meterParts(undefined)).toEqual([]);
    expect(meterParts({ used: 10 })).toEqual([]);
  });

  it("grades today's usage against the limit", () => {
    expect(quota(2_606_090, 300_000_000)).toEqual({ percent: 0, level: "ok" });
    expect(quota(899, 1000)).toEqual({ percent: 89, level: "ok" });
    expect(quota(900, 1000)).toEqual({ percent: 90, level: "warn" });
    expect(quota(999, 1000)).toEqual({ percent: 99, level: "warn" });
    expect(quota(1000, 1000)).toEqual({ percent: 100, level: "over" });
    expect(quota(2500, 1000)).toEqual({ percent: 250, level: "over" });
  });
});
