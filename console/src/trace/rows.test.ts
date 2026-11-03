import { describe, expect, it } from "vitest";
import pageJson from "../../../tantalum-shell/src/test/resources/wire/trace.json?raw";
import type { TracePage, TraceRecord } from "../api/types";
import { between, formatLocal, formatMs, formatSpeed, formatTokens, formatUnix, parseIsoDuration } from "./format";
import { buildLedger, expansionFor, type Row } from "./rows";
import { matchRecords, searchText } from "./search";

// 后端 WireFormatTest 的场景：插件补丁、拦工具、要工作，并行工具，429 重试和重试等待期间的插话，自动压缩，手动压缩，重启中断。
const RECORDS = (JSON.parse(pageJson) as TracePage).records;
const NONE = new Set<string>();

function describeRow(row: Row): string {
  switch (row.type) {
    case "turn":
      return row.turn === undefined ? "== 轮次之间" : `== 第 ${row.turn} 轮`;
    case "group":
      return row.step !== undefined ? `-- 第 ${row.step} 步` : `-- 压缩 ${row.compaction}`;
    case "record":
      return row.record.id + (row.collapsed ? " (收起)" : "");
    case "summary":
      return `… ${row.of} ${row.summary.steps} 步 ${row.summary.toolCalls} 工具 ${row.names.join(",")}`;
  }
}

describe("buildLedger", () => {
  it("按轮、步分行：请求前的记录排在请求行之前，压缩组在它之后那一步的请求之前", () => {
    const rows = buildLedger(RECORDS, NONE, NONE).map(describeRow);
    expect(rows).toEqual([
      "== 第 1 轮",
      "-- 第 1 步",
      "u:0", "h:1.0", "h:1.1", "w:s_1-0", "u:2", "h:2.0", "s:2",
      // 重试等待期间的插话（4.0）和第二次尝试前的钩子（5.0）都在这次请求的第二次尝试之前，所以排在请求行前面。
      "u:4", "h:5.0", "r:s_2-0",
      "t:6.1", "t:6.2", "t:6.3", "h:6.0",
      "-- 第 2 步",
      "h:8.0", "r:s_8-0",
      "== 第 2 轮",
      "-- 第 1 步",
      "u:10", "c:11", "h:11.0", "r:s_11-0",
      "== 第 3 轮",
      "-- 压缩 k14",
      "r:s_14-0", "k:15",
      "-- 第 1 步",
      "u:13", "h:14.0", "h:16.0", "s:16", "r:s_16-0",
      "== 第 4 轮",
      "-- 第 1 步",
      "u:18", "h:19.0", "r:s_19-0",
      "== 轮次之间",
      "-- 压缩 k21",
      "s:21", "r:s_21-0", "k:22",
      "== 第 5 轮",
      "-- 第 1 步",
      "u:23", "h:24.0", "s:24", "r:s_24-0", "t:25.0",
    ]);
  });

  it("每一轮的标题带运行记录和摘要（有请求的步数、工具调用数）", () => {
    const turns = buildLedger(RECORDS, NONE, NONE).filter((row) => row.type === "turn");
    expect(turns.map((row) => [row.run, row.head?.trigger, row.summary])).toEqual([
      ["s_0", "PROMPT", { steps: 2, toolCalls: 3 }],
      ["s_10", "PROMPT", { steps: 1, toolCalls: 0 }],
      ["s_13", "PROMPT", { steps: 2, toolCalls: 0 }],
      ["s_18", "PROMPT", { steps: 1, toolCalls: 0 }],
      ["s_21", "MANUAL_COMPACTION", { steps: 1, toolCalls: 0 }],
      ["s_23", "PROMPT", { steps: 1, toolCalls: 1 }],
    ]);
  });

  it("收起一轮：留标题、system 行、第一条内容和一行摘要", () => {
    const rows = buildLedger(RECORDS, new Set(["s_0"]), NONE);
    const first = rows.slice(0, rows.findIndex((row) => row.type === "turn" && row.run === "s_10"));
    expect(first.map(describeRow)).toEqual(["== 第 1 轮", "u:0", "s:2", "… turn 2 步 3 工具 "]);
    const summary = first.at(-1);
    expect(summary?.type === "summary" && summary.hidden).toContain("t:6.3");
    expect(first[0]?.type === "turn" && first[0].collapsed).toBe(true);
  });

  it("收起一次调用：工具调用、拦它们的钩子换成一行摘要，摘要列出工具名", () => {
    const rows = buildLedger(RECORDS, NONE, new Set(["r:s_2-0"])).map(describeRow);
    const at = rows.indexOf("r:s_2-0 (收起)");
    expect(rows.slice(at, at + 3)).toEqual(["r:s_2-0 (收起)", "… calls 0 步 3 工具 read_file,github/search,rm", "-- 第 2 步"]);
    expect(rows).not.toContain("h:6.0");
  });

  it("没有工具调用的请求收起后不变", () => {
    expect(buildLedger(RECORDS, NONE, new Set(["r:s_8-0"])).map(describeRow)).toEqual(buildLedger(RECORDS, NONE, NONE).map(describeRow));
  });

  it("要看到的记录：所在的轮，以及工具、拦工具的钩子所属的调用", () => {
    expect(expansionFor(RECORDS, ["h:6.0", "u:10"])).toEqual({ turns: new Set(["s_0", "s_10"]), calls: new Set(["r:s_2-0"]) });
    expect(expansionFor(RECORDS, ["t:25.0"])).toEqual({ turns: new Set(["s_23"]), calls: new Set(["r:s_24-0"]) });
    expect(expansionFor(RECORDS, ["missing"])).toEqual({ turns: new Set(), calls: new Set() });
  });
});

describe("search", () => {
  it("覆盖工具参数和结果、钩子理由、工作的输入和结果、插话、上下文", () => {
    expect([...matchRecords(RECORDS, "destructive")]).toEqual(expect.arrayContaining(["t:6.3"]));
    expect([...matchRecords(RECORDS, "SOURCE: A.MD")]).toEqual(["w:s_1-0"]);
    expect([...matchRecords(RECORDS, "keep it short")]).toEqual(["u:4"]);
    expect([...matchRecords(RECORDS, "mcp__github")]).toEqual(["t:6.2"]);
    expect([...matchRecords(RECORDS, "a\nb")]).toEqual(["c:11"]);
  });

  it("空白的搜索词不匹配；可搜索文字按记录对象缓存", () => {
    expect(matchRecords(RECORDS, "   ").size).toBe(0);
    const record = RECORDS[0] as TraceRecord;
    expect(searchText(record)).toBe(searchText(record));
  });

  it("收起的内容也能搜到，摘要行列出被藏起来的命中", () => {
    const matches = matchRecords(RECORDS, "no match");
    const rows = buildLedger(RECORDS, NONE, new Set(["r:s_2-0"]));
    const summary = rows.find((row) => row.type === "summary");
    expect(summary?.type === "summary" && summary.hidden.some((id) => matches.has(id))).toBe(true);
  });
});

describe("format", () => {
  it("时长：1 秒以下毫秒，10 秒以下两位小数，再长一位", () => {
    expect(formatMs(999.4)).toBe("999 毫秒");
    expect(formatMs(1234)).toBe("1.23 秒");
    expect(formatMs(12_345)).toBe("12.3 秒");
    expect(between("2026-10-31T00:00:02Z", "2026-10-31T00:00:02.600Z")).toBe(600);
    expect(between(undefined, "2026-10-31T00:00:02Z")).toBeUndefined();
  });

  it("时刻在本地时间和 Unix 时间戳之间切换", () => {
    const iso = "2026-10-31T00:00:05.700Z";
    expect(formatUnix(iso)).toBe("1793404805.700");
    const local = new Date(iso);
    expect(formatLocal(iso)).toBe(
      `${local.getFullYear()}-${String(local.getMonth() + 1).padStart(2, "0")}-${String(local.getDate()).padStart(2, "0")} ` +
        `${String(local.getHours()).padStart(2, "0")}:${String(local.getMinutes()).padStart(2, "0")}:05.700`,
    );
  });

  it("token、速度、ISO 时长", () => {
    expect(formatTokens(999)).toBe("999");
    expect(formatTokens(1_500)).toBe("1.5k");
    expect(formatTokens(25_000)).toBe("25k");
    expect(formatTokens(2_500_000)).toBe("2.5M");
    expect(formatSpeed(40, 2_000)).toBe("20.0 tok/s");
    expect(formatSpeed(40, 0)).toBeUndefined();
    expect(parseIsoDuration("PT2S")).toBe(2_000);
    expect(parseIsoDuration("PT1M30.5S")).toBe(90_500);
    expect(parseIsoDuration("P1D")).toBeUndefined();
  });
});
