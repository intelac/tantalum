import { describe, expect, it } from "vitest";
import pageJson from "../../../tantalum-shell/src/test/resources/wire/trace.json?raw";
import type { TracePage, TraceRecord } from "../api/types";
import { buildStats, overallTotals } from "../stats/metrics";
import { totalsOf } from "./totals";
import { recordsOfTurn, turnTabs } from "./turns";

// 后端 WireFormatTest 的场景：五轮，其中第 4、5 轮之间有一次手动压缩；最后一轮重启时中断。
const RECORDS = (JSON.parse(pageJson) as TracePage).records;

const at = (seconds: number) => new Date(Date.UTC(2026, 10, 12, 0, 0, seconds)).toISOString();

function run(id: string, turn: number | undefined, from: number, to?: number, endReason?: "COMPLETED" | "FAILED"): TraceRecord {
  return { kind: "run", id: `run:${id}`, pos: `${from}.0`, run: id, turn, at: at(from), end: to === undefined ? undefined : at(to), trigger: turn === undefined ? "MANUAL_COMPACTION" : "PROMPT", phase: to === undefined ? "AwaitingModel" : "Ended", endReason };
}

function user(id: string, runId: string, turn: number, text: string, extra: Partial<Extract<TraceRecord, { kind: "user" }>> = {}): TraceRecord {
  return { kind: "user", id, pos: id.slice(2), run: runId, turn, step: 1, at: at(0), end: at(0), mode: "PROMPT", text, source: { type: "user" }, ...extra };
}

function request(id: string, runId: string, turn: number | undefined, usage?: [number, number, number, number], purpose: "NORMAL" | "SUMMARY" = "NORMAL"): TraceRecord {
  return {
    kind: "request", id, pos: id.slice(2), run: runId, turn, step: 1, at: at(1), end: at(2), number: 1, purpose, status: "DONE", attempts: [], maxAttempts: 3, toolCalls: 0,
    usage: usage === undefined ? undefined : { input: usage[0], output: usage[1], cacheRead: usage[2], cacheWrite: usage[3] },
  };
}

function tool(id: string, runId: string, turn: number, status: "DONE" | "BLOCKED" | "NOT_SENT" = "DONE"): TraceRecord {
  return { kind: "tool", id, pos: id.slice(2), run: runId, turn, step: 1, at: at(2), end: at(3), call: id, name: "read_file", rawArguments: "{}", status };
}

function question(id: string, runId: string, turn: number, status: "WAITING" | "ANSWERED"): TraceRecord {
  return { kind: "question", id, pos: id.slice(2), run: runId, turn, step: 1, at: at(2), end: status === "WAITING" ? undefined : at(5), question: id, askedBy: "toolApproval", title: { en: "Run it?" }, status };
}

describe("turnTabs（trace-rounds 需求 1）", () => {
  it("每一轮一个标签，按先后；手动压缩没有标签", () => {
    const tabs = turnTabs(RECORDS);
    expect(tabs.map((tab) => tab.turn)).toEqual([1, 2, 3, 4, 5]);
    expect(tabs.map((tab) => tab.run)).toEqual(["s_0", "s_10", "s_13", "s_18", "s_23"]);
    expect(RECORDS.some((record) => record.turn === undefined)).toBe(true);
  });

  it("提示是这一轮人写的那句，压成一行；插件续写和插件发的不算", () => {
    const records = [
      run("a_1", 1, 0, 10, "COMPLETED"),
      user("u:1", "a_1", 1, "先看一下\n  这个仓库"),
      user("u:2", "a_1", 1, "后面的插话", { mode: "STEER" }),
      run("a_20", 2, 20, 30, "COMPLETED"),
      user("u:20", "a_20", 2, "继续检查引用", { mode: "CONTINUE", source: { type: "plugin", name: "citationCheck" } }),
    ];
    const tabs = turnTabs(records);
    expect(tabs[0]?.prompt).toBe("先看一下 这个仓库");
    expect(tabs[1]?.prompt).toBeUndefined();
    expect(tabs[1]?.trigger).toBe("PROMPT");
  });

  it("状态：进行中、在等回答、失败、已结束", () => {
    const records = [
      run("a_1", 1, 0, 10, "COMPLETED"),
      run("a_20", 2, 20, 30, "FAILED"),
      run("a_40", 3, 40),
      run("a_60", 4, 60),
      question("q:61", "a_60", 4, "WAITING"),
      run("a_80", 5, 80, 90, "COMPLETED"),
      question("q:81", "a_80", 5, "ANSWERED"),
      // 这一轮的运行头不在这一页里：不乱标“进行中”
      user("u:100", "a_100", 6, "只带到一半"),
    ];
    expect(turnTabs(records).map((tab) => tab.state)).toEqual(["done", "failed", "running", "waiting", "done", "done"]);
  });

  it("recordsOfTurn 只留这一轮；全部或这一轮不在时原样返回", () => {
    expect(recordsOfTurn(RECORDS, null)).toBe(RECORDS);
    const second = recordsOfTurn(RECORDS, "s_10");
    expect(second.length).toBeGreaterThan(0);
    expect(second.every((record) => record.run === "s_10")).toBe(true);
    expect(recordsOfTurn(RECORDS, "no-such-run")).toBe(RECORDS);
  });
});

describe("totalsOf（trace-rounds 需求 2）", () => {
  it("请求数含压缩的摘要请求；工具按记录数，被拦下和没发出的也算；token 是所有请求读入和输出之和", () => {
    const records = [
      run("a_1", 1, 0, 10, "COMPLETED"),
      request("r:1", "a_1", 1, [100, 20, 300, 40]),
      request("r:2", "a_1", 1, [10, 5, 0, 0], "SUMMARY"),
      request("r:3", "a_1", 1),
      tool("t:1", "a_1", 1),
      tool("t:2", "a_1", 1, "BLOCKED"),
      tool("t:3", "a_1", 1, "NOT_SENT"),
      question("q:1", "a_1", 1, "ANSWERED"),
    ];
    expect(totalsOf(records)).toEqual({ requests: 3, tools: 3, running: false, elapsed: 10_000, input: 450, output: 25 });
  });

  it("耗时是各轮时长之和；还在跑的轮不算进来，标进行中", () => {
    const records = [run("a_1", 1, 0, 10, "COMPLETED"), run("a_20", 2, 20, 25, "COMPLETED"), run("a_40", 3, 40)];
    expect(totalsOf(records)).toEqual({ requests: 0, tools: 0, running: true, elapsed: 15_000 });
    expect(totalsOf([run("a_40", 3, 40)])).toEqual({ requests: 0, tools: 0, running: true });
  });

  it("没有运行头、没有用量时不编数", () => {
    expect(totalsOf([tool("t:1", "a_1", 1)])).toEqual({ requests: 0, tools: 1, running: false });
    expect(totalsOf([])).toEqual({ requests: 0, tools: 0, running: false });
  });

  it("和统计页签的差别只在摘要请求：统计页签的合计不算它，这里算", () => {
    const all = totalsOf(RECORDS);
    const requests = RECORDS.filter((record) => record.kind === "request");
    const summaries = requests.filter((record) => record.kind === "request" && record.purpose === "SUMMARY");
    expect(all.requests).toBe(requests.length);
    expect(summaries.length).toBeGreaterThan(0);
    const summaryOutput = summaries.reduce((sum, record) => sum + (record.kind === "request" ? (record.usage?.output ?? 0) : 0), 0);
    expect(all.output).toBe((overallTotals(buildStats(RECORDS)).output ?? 0) + summaryOutput);
    expect(all.tools).toBe(RECORDS.filter((record) => record.kind === "tool").length);
  });

  it("一轮的合计就是这一轮的记录的合计", () => {
    const second = totalsOf(recordsOfTurn(RECORDS, "s_10"));
    expect(second.requests).toBe(RECORDS.filter((record) => record.run === "s_10" && record.kind === "request").length);
    expect(second.running).toBe(false);
  });
});
