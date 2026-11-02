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
    expect(parts).toEqual({ turns: "3 轮", tokens: "18.8K tok", inOut: "输入 18K · 输出 800", context: "上下文 14%", warn: false });
  });

  it("omits what it does not know", () => {
    expect(statusParts(0, undefined, 131072)).toEqual({ turns: null, tokens: null, inOut: null, context: null, warn: false });
    expect(statusParts(2, { input: 100, output: 10, cacheRead: 0, cacheWrite: 0 }, undefined).context).toBeNull();
    expect(statusParts(1, { input: 500000, output: 1, cacheRead: 0, cacheWrite: 0 }, 1000).context).toBe("上下文 100%");
  });

  it("warns from 80 percent on", () => {
    expect(statusParts(1, { input: 790, output: 1, cacheRead: 0, cacheWrite: 0 }, 1000).warn).toBe(false);
    expect(statusParts(1, { input: 800, output: 1, cacheRead: 0, cacheWrite: 0 }, 1000).warn).toBe(true);
    expect(statusParts(1, { input: 800, output: 1, cacheRead: 0, cacheWrite: 0 }, undefined).warn).toBe(false);
  });
});
