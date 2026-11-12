import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { HookDecision, ModelRequest, QuestionRecord, ToolCall, TraceRecord } from "../api/types";
import { zh } from "../text/zh";
import { approvalsOf, approvalText, isAskUser } from "./approval";
import { Inspector } from "./inspector/Inspector";
import { duration } from "./LedgerRow";
import { buildLedger, expansionFor, focusRows } from "./rows";
import { matchRecords } from "./search";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const at = (seconds: number) => new Date(Date.UTC(2026, 10, 12, 0, 0, 0, seconds * 1000)).toISOString();
const base = (id: string, pos: string) => ({ id, pos, run: "s_0", turn: 1, step: 1 });
const NONE = new Set<string>();
const T = zh.trace.approval;

const run: TraceRecord = { kind: "run", id: "run:s_0", pos: "0.0", run: "s_0", turn: 1, at: at(0), end: at(30), trigger: "PROMPT", phase: "Ended", endReason: "COMPLETED" };
const request: ModelRequest = { kind: "request", ...base("r:1", "1.0"), at: at(0), end: at(1), number: 1, purpose: "NORMAL", status: "DONE", attempts: [], maxAttempts: 3, toolCalls: 2 };

function tool(id: string, pos: string, name: string, status: ToolCall["status"] = "DONE"): ToolCall {
  return { kind: "tool", ...base(id, pos), at: at(5), end: at(5.2), call: id, name, rawArguments: "{}", status, request: "r:1", result: status === "BLOCKED" ? undefined : "ok" };
}

function hookAsk(id: string, pos: string, toolId: string): HookDecision {
  return { kind: "hook", ...base(id, pos), at: at(1), end: at(1), hook: "toolApproval", point: "BEFORE_TOOL_CALL", result: "ASK", systemChars: 0, tools: [], tool: toolId };
}

function question(id: string, pos: string, toolId: string | undefined, status: QuestionRecord["status"], extra: Partial<QuestionRecord> = {}): QuestionRecord {
  return {
    kind: "question", ...base(id, pos), at: at(1), end: status === "WAITING" ? undefined : at(4.4), question: id, askedBy: "toolApproval",
    title: { en: "Allow calling bash?", zh: "允许调用 bash 吗？" }, status, tool: toolId, ...extra,
  };
}

/** 一次要批准的调用：钩子决定、问了用户、工具，再加一个不用批准的调用。 */
const APPROVED: TraceRecord[] = [
  run,
  request,
  tool("t:2.0", "2.0", "read_file"),
  tool("t:2.1", "2.1", "bash"),
  hookAsk("h:3.0", "3.0", "t:2.1"),
  question("q:a", "3.1", "t:2.1", "ANSWERED", { choice: "once" }),
];

const recordRows = (records: readonly TraceRecord[]) => buildLedger(records, NONE, NONE).flatMap((row) => (row.type === "record" ? [row] : []));

/** trace-rounds 需求 3：等批准的工具调用合成一行。 */
describe("approvals folded into the tool row", () => {
  it("the hook decision and the question disappear as rows; the tool row carries them", () => {
    const rows = recordRows(APPROVED);
    expect(rows.map((row) => row.record.id)).toEqual(["r:1", "t:2.0", "t:2.1"]);
    const bash = rows.find((row) => row.record.id === "t:2.1");
    expect(bash?.approval?.question.id).toBe("q:a");
    expect(bash?.approval?.hook?.id).toBe("h:3.0");
    expect(rows.find((row) => row.record.id === "t:2.0")?.approval).toBeUndefined();
    const folding = approvalsOf(APPROVED);
    expect([...folding.into]).toEqual([["q:a", "t:2.1"], ["h:3.0", "t:2.1"]]);
  });

  it("an ask_user call carries its own question", () => {
    const records = [run, request, tool("t:2.0", "2.0", "ask_user"), question("q:a", "2.1", "t:2.0", "ANSWERED", { askedBy: "ask_user", choice: "keep", text: "归档到 old/" })];
    const rows = recordRows(records);
    expect(rows.map((row) => row.record.id)).toEqual(["r:1", "t:2.0"]);
    const approval = rows[1]?.approval;
    expect(approval !== undefined && isAskUser(approval)).toBe(true);
  });

  it("is left as separate rows when the question names no call, or the call is not loaded, or the server is older", () => {
    const elsewhere = [run, request, tool("t:2.0", "2.0", "bash"), question("q:a", "3.1", undefined, "ANSWERED")];
    expect(recordRows(elsewhere).map((row) => row.record.id)).toEqual(["r:1", "t:2.0", "q:a"]);
    const missing = [run, request, hookAsk("h:3.0", "3.0", "t:9.9"), question("q:a", "3.1", "t:9.9", "ANSWERED")];
    expect(recordRows(missing).map((row) => row.record.id)).toEqual(["r:1", "h:3.0", "q:a"]);
    expect(approvalsOf(missing).into.size).toBe(0);
    // 别的钩子决定（拦截、补丁）不并
    const stop: HookDecision = { ...hookAsk("h:3.0", "3.0", "t:2.1"), result: "STOP", reason: "no" };
    expect(recordRows([run, request, tool("t:2.1", "2.1", "bash", "BLOCKED"), stop]).map((row) => row.record.id)).toEqual(["r:1", "t:2.1", "h:3.0"]);
  });

  it("when a call was asked about twice only the last question is folded", () => {
    const twice = [...APPROVED.slice(0, 5), question("q:a", "3.1", "t:2.1", "EXPIRED"), hookAsk("h:4.0", "4.0", "t:2.1"), question("q:b", "4.1", "t:2.1", "ANSWERED")];
    const rows = recordRows(twice);
    expect(rows.map((row) => row.record.id)).toEqual(["r:1", "t:2.0", "t:2.1", "h:3.0", "q:a"]);
    expect(rows.find((row) => row.record.id === "t:2.1")?.approval?.question.id).toBe("q:b");
    expect(rows.find((row) => row.record.id === "t:2.1")?.approval?.hook?.id).toBe("h:4.0");
  });

  it("collapsing the calls of a request still counts the same tool calls", () => {
    const rows = buildLedger(APPROVED, NONE, new Set(["r:1"]));
    const summary = rows.find((row) => row.type === "summary");
    expect(summary?.type === "summary" && summary.summary.toolCalls).toBe(2);
    expect(summary?.type === "summary" && summary.hidden).toEqual(["t:2.0", "t:2.1"]);
  });

  it("selecting a time range or locating a folded record lands on the tool row", () => {
    const folding = approvalsOf(APPROVED);
    const rows = buildLedger(APPROVED, NONE, NONE);
    // 框选到等人的那一段：页面先把被并掉的 id 换成工具的 id
    const ids = new Set(["q:a"].map((id) => folding.into.get(id) ?? id));
    expect(focusRows(rows, ids).flatMap((row) => (row.type === "record" ? [row.record.id] : []))).toEqual(["t:2.1"]);
    expect(expansionFor(APPROVED, ["t:2.1"]).calls).toEqual(new Set(["r:1"]));
  });

  it("search still finds the words of a folded record", () => {
    const folding = approvalsOf(APPROVED);
    const hits = [...matchRecords(APPROVED, "allow calling bash")].map((id) => folding.into.get(id) ?? id);
    expect(hits).toEqual(["t:2.1"]);
  });
});

describe("the time column of a folded tool row", () => {
  const approval = { question: question("q", "3.1", "t:2.1", "ANSWERED"), hook: hookAsk("h", "3.0", "t:2.1") };

  it("is the tool's own run, without the time spent waiting for a person", () => {
    // 发出 0 秒，问 1 秒，答 4.4 秒，开始执行 4.5 秒，结束 5 秒
    const ran: ToolCall = { ...tool("t:2.1", "2.1", "bash"), at: at(0), issuedAt: at(0), startedAt: at(4.5), end: at(5) };
    expect(duration(ran, approval)).toBe("500 毫秒");
    expect(duration(ran)).toBe("5.00 秒");
    // 没记下开始执行的时刻：把等人的 3.4 秒减掉
    const unknown: ToolCall = { ...ran, startedAt: undefined };
    expect(duration(unknown, approval)).toBe("1.60 秒");
    // 被拒绝的调用没有执行过
    const denied: ToolCall = { ...tool("t:2.1", "2.1", "bash", "BLOCKED"), at: at(1), startedAt: undefined, end: at(4.4) };
    expect(duration(denied, approval)).toBe("");
  });

  it("is empty while still waiting for the person; the row says how long", () => {
    const waiting: ToolCall = { ...tool("t:2.1", "2.1", "bash", "WAITING"), end: undefined };
    expect(duration(waiting, { question: question("q", "3.1", "t:2.1", "WAITING") })).toBe("");
    expect(duration(waiting)).toBe("进行中");
  });

  it("the timeline still gets the question as a record of its own", () => {
    const unfolded = buildLedger(APPROVED, NONE, NONE, false).flatMap((row) => (row.type === "record" ? [row.record.id] : []));
    expect(unfolded).toContain("q:a");
    expect(unfolded).toContain("h:3.0");
  });
});

describe("approvalText", () => {
  const bash = tool("t:2.1", "2.1", "bash");
  const now = Date.parse(at(13));

  it.each([
    ["插件问的，还在等", { question: question("q", "3.1", "t:2.1", "WAITING"), hook: hookAsk("h", "3.0", "t:2.1") }, bash, "等批准 12.0 秒"],
    ["插件问的，批准了", { question: question("q", "3.1", "t:2.1", "ANSWERED", { choice: "once" }), hook: hookAsk("h", "3.0", "t:2.1") }, bash, "等了 3.40 秒 · 已批准"],
    ["插件问的，拒绝了", { question: question("q", "3.1", "t:2.1", "ANSWERED", { choice: "deny" }), hook: hookAsk("h", "3.0", "t:2.1") }, tool("t:2.1", "2.1", "bash", "BLOCKED"), "等了 3.40 秒 · 已拒绝"],
    ["过期", { question: question("q", "3.1", "t:2.1", "EXPIRED"), hook: hookAsk("h", "3.0", "t:2.1") }, tool("t:2.1", "2.1", "bash", "BLOCKED"), "等了 3.40 秒 · 没等到回答"],
    ["取消", { question: question("q", "3.1", "t:2.1", "CANCELLED"), hook: hookAsk("h", "3.0", "t:2.1") }, bash, "等了 3.40 秒 · 已取消"],
    ["ask_user 还在等", { question: question("q", "3.1", "t:2.1", "WAITING", { askedBy: "ask_user" }) }, tool("t:2.1", "2.1", "ask_user", "RUNNING"), "等回答 12.0 秒"],
    ["ask_user 答了", { question: question("q", "3.1", "t:2.1", "ANSWERED", { askedBy: "ask_user", choice: "keep", text: "归档" }) }, tool("t:2.1", "2.1", "ask_user"), "等了 3.40 秒 · 答了 keep · 归档"],
  ] as const)("%s", (_, approval, record, expected) => {
    expect(approvalText(approval, record, now, T)).toBe(expected);
  });
});

describe("the approval in the inspector", () => {
  function open(records: TraceRecord[], id: string, onOpen = vi.fn()) {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => undefined)));
    const folding = approvalsOf(records);
    const byId = (wanted: string) => records.find((record) => record.id === wanted);
    render(
      <QueryClientProvider client={new QueryClient()}>
        <Inspector
          session="s"
          record={byId(id) as TraceRecord}
          lookup={byId}
          containerWidth={1280}
          overlay={false}
          onClose={vi.fn()}
          onOpen={onOpen}
          approvalOf={(toolId) => folding.byTool.get(toolId)}
        />
      </QueryClientProvider>,
    );
    return onOpen;
  }

  it("has a tab that says who asked, what, the choice and how long, and opens the two original records", async () => {
    const onOpen = open(APPROVED, "t:2.1");
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["概述", "批准", "参数", "结果", "Schema", "计时"]);
    await userEvent.click(screen.getByRole("tab", { name: "批准" }));
    const panel = screen.getByRole("tabpanel");
    expect(panel.textContent).toContain("toolApproval");
    expect(panel.textContent).toContain("允许调用 bash 吗？");
    expect(panel.textContent).toContain("等了 3.40 秒 · 已批准");
    expect(panel.textContent).toContain("once");
    expect(panel.textContent).toContain("等人的这段时间不算进工具的用时。");
    await userEvent.click(screen.getByRole("button", { name: "看提问记录" }));
    await userEvent.click(screen.getByRole("button", { name: "看钩子决定" }));
    expect(onOpen.mock.calls.map((call) => call[0])).toEqual(["q:a", "h:3.0"]);
  });

  it("a call without an approval has no such tab; ask_user calls it a question", () => {
    open(APPROVED, "t:2.0");
    expect(screen.queryByRole("tab", { name: "批准" })).toBeNull();
    cleanup();
    const asked = [run, request, tool("t:2.0", "2.0", "ask_user"), question("q:a", "2.1", "t:2.0", "ANSWERED", { askedBy: "ask_user", choice: "keep" })];
    open(asked, "t:2.0");
    expect(screen.getByRole("tab", { name: "提问" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "看钩子决定" })).toBeNull();
  });

  it("the original question record can still be shown on its own", () => {
    open(APPROVED, "q:a");
    expect(screen.getByRole("complementary", { name: "事件详情" }).textContent).toContain("toolApproval");
  });
});
