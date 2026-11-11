import { describe, expect, it } from "vitest";
import { cacheHit, compact, total } from "./usage";

describe("total", () => {
  it("adds the three kinds of input and the output", () => {
    expect(total({ input: 100, output: 20, cacheRead: 3000, cacheWrite: 7 })).toBe(3127);
  });
});

describe("compact", () => {
  it.each([
    [0, "0"],
    [999, "999"],
    [1000, "1K"],
    [12_249, "12.2K"],
    [99_949, "99.9K"],
    [517_400, "517K"],
    [999_499, "999K"],
    [999_500, "1M"],
    [1_240_000, "1.2M"],
    [123_456_789, "123M"],
  ])("%d is written %s", (count, text) => {
    expect(compact(count)).toBe(text);
  });
});

describe("cacheHit", () => {
  it.each([
    [{ input: 0, output: 5, cacheRead: 0, cacheWrite: 0 }, null],
    [{ input: 100, output: 5, cacheRead: 0, cacheWrite: 0 }, "0.0"],
    [{ input: 50, output: 5, cacheRead: 50, cacheWrite: 0 }, "50.0"],
    [{ input: 100, output: 5, cacheRead: 853, cacheWrite: 47 }, "85.3"],
    [{ input: 0, output: 5, cacheRead: 400, cacheWrite: 0 }, "100"],
    // 十万里只差一个也不写 100
    [{ input: 1, output: 5, cacheRead: 99_999, cacheWrite: 0 }, "99.9"],
    [{ input: 0, output: 5, cacheRead: 99_999, cacheWrite: 1 }, "99.9"],
  ])("%j is %j", (usage, text) => {
    expect(cacheHit(usage)).toBe(text);
  });
});
