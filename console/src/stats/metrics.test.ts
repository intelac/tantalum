import { describe, expect, it } from "vitest";
import pageJson from "../../../tantalum-shell/src/test/resources/wire/trace.json?raw";
import type { TracePage } from "../api/types";
import { buildStats, overallTotals, unionLength } from "./metrics";

// 后端 WireFormatTest 的场景：第 1 轮有 429 重试和三个工具（两个并行、一个被拦），第 3 轮有自动压缩，s_21 是手动压缩。
const RECORDS = (JSON.parse(pageJson) as TracePage).records;
const STATS = buildStats(RECORDS);

describe("buildStats", () => {
  it("每轮一行，按运行先后；手动压缩的运行没有轮号，只有一个压缩组", () => {
    expect(STATS.map((turn) => [turn.run, turn.turn, turn.head?.trigger])).toEqual([
      ["s_0", 1, "PROMPT"],
      ["s_10", 2, "PROMPT"],
      ["s_13", 3, "PROMPT"],
      ["s_18", 4, "PROMPT"],
      ["s_21", undefined, "MANUAL_COMPACTION"],
      ["s_23", 5, "PROMPT"],
    ]);
    expect(STATS[4]?.steps.map((step) => step.compaction)).toEqual([true]);
    expect(STATS[0]?.prompt?.text.startsWith("q1")).toBe(true);
  });

  it("一步的指标：上下文、缓存命中、首 token 取最后一次尝试，模型用时是各尝试之和，工具用时是区间的并", () => {
    const step = STATS[0]?.steps[0];
    expect(step).toMatchObject({ context: 400, output: 40, cacheRead: 100, cacheRate: 0.25 });
    expect(step?.ttft).toBe(600);
    // 第一次 2.1 → 3.0 秒失败，第二次 5.1 → 6.0 秒。
    expect(step?.model).toBe(1_800);
    // 并行的 6.6 → 7.0 和 7.6 → 8.0 秒；被拦下的没有开始时刻，不计。
    expect(step?.tool).toBe(800);
    expect(step?.speed).toBeCloseTo(40 / 0.3);
    expect(step?.tools).toEqual([
      { name: "read_file", count: 1 },
      { name: "github/search", count: 1 },
      { name: "rm", count: 1 },
    ]);
    expect(step?.continued).toBe(true);
  });

  it("没有记录开始时刻和首 token 的旧事件，对应指标为空（显示“未记录”）", () => {
    const step = STATS[0]?.steps[1];
    expect(step?.context).toBe(700);
    expect(step?.ttft).toBeUndefined();
    expect(step?.speed).toBeUndefined();
    expect(step?.model).toBe(1_000);
  });

  it("一轮的合计：上下文取最后一步，缓存命中率按 token 加权，压缩组不计入", () => {
    const first = STATS[0];
    expect(first?.context).toBe(700);
    expect(first?.cacheRate).toBeCloseTo(100 / (400 + 700));
    expect(first?.output).toBe(50);
    expect(first?.model).toBe(2_800);
    const third = STATS[2];
    expect(third?.steps.map((step) => step.compaction)).toEqual([true, false]);
    // 只算第 1 步的请求：压缩的摘要请求用量 50 不计。
    expect(third?.output).toBeUndefined();
    expect(third?.context).toBeUndefined();
  });

  it("上下文增量：相对同轮上一步、上一轮", () => {
    expect(STATS[0]?.steps[1]?.contextDelta).toBe(300);
    expect(STATS[1]?.contextDelta).toBe(790 - 700);
  });

  it("合计行：全部已加载的轮次，不含压缩", () => {
    const total = overallTotals(STATS);
    expect(total.output).toBe(40 + 10 + 10);
    expect(total.cacheRate).toBeCloseTo(100 / (400 + 700 + 790));
  });
});

describe("unionLength", () => {
  it("重叠的区间只算一次", () => {
    expect(unionLength([[0, 10], [5, 15], [20, 25]])).toBe(20);
    expect(unionLength([])).toBe(0);
  });
});
