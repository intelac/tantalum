import { describe, expect, it } from "vitest";
import { tokenCount, statusParts } from "./status";

describe("status", () => {
  it("compacts counts", () => {
    expect(tokenCount(999)).toBe("999");
    expect(tokenCount(1234)).toBe("1.2K");
    expect(tokenCount(18000)).toBe("18K");
    expect(tokenCount(2_500_000)).toBe("2.5M");
  });

  it("shows turns, tokens and the context share when the window is known", () => {
    const parts = statusParts(3, { input: 15000, output: 800, cacheRead: 3000, cacheWrite: 0 }, 131072);
    expect(parts).toEqual({ turns: "3 轮", tokens: "18.8K tok", inOut: "输入 18K · 输出 800", context: "上下文 14%", warn: false, pointPercent: null });
  });

  it("omits what it does not know", () => {
    expect(statusParts(0, undefined, 131072)).toEqual({ turns: null, tokens: null, inOut: null, context: null, warn: false, pointPercent: null });
    expect(statusParts(2, { input: 100, output: 10, cacheRead: 0, cacheWrite: 0 }, undefined).context).toBeNull();
    expect(statusParts(1, { input: 500000, output: 1, cacheRead: 0, cacheWrite: 0 }, 1000).context).toBe("上下文 100%");
  });

  it("warns at 90 percent of the chosen compaction point", () => {
    // 窗口 1M、压缩点 50 万：45 万开始提醒
    const at = (input: number) => statusParts(1, { input, output: 1, cacheRead: 0, cacheWrite: 0 }, 1_000_000, 500_000);
    expect(at(449_999).warn).toBe(false);
    expect(at(450_000).warn).toBe(true);
    expect(at(450_000).context).toBe("上下文 45%");
    expect(at(1).pointPercent).toBe(50);
    // 窗口的 80% 离 93.6% 的压缩点还远
    expect(statusParts(1, { input: 800_000, output: 1, cacheRead: 0, cacheWrite: 0 }, 1_000_000, 936_000).warn).toBe(false);
  });

  it("warns from 80 percent on while the compaction point is unknown", () => {
    expect(statusParts(1, { input: 790, output: 1, cacheRead: 0, cacheWrite: 0 }, 1000).warn).toBe(false);
    expect(statusParts(1, { input: 800, output: 1, cacheRead: 0, cacheWrite: 0 }, 1000).warn).toBe(true);
    expect(statusParts(1, { input: 800, output: 1, cacheRead: 0, cacheWrite: 0 }, undefined).warn).toBe(false);
  });
});
