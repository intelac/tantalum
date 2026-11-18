// 任务在轨迹页里（subagent 需求 10.5）：放任务的工具调用能点进任务的轨迹；任务的结果、交代的内容是“任务”记录，不是用户输入。

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { RunHead, TaskNote, ToolCall, TraceRecord } from "../api/types";
import { tracePath } from "../app/router";
import { zh } from "../text/zh";
import { Inspector } from "./inspector/Inspector";
import { contentOf } from "./LedgerRow";
import { buildLedger } from "./rows";
import { searchText } from "./search";
import { laneOf } from "./segments";
import { turnTabs } from "./turns";

afterEach(() => {
  cleanup();
  window.history.pushState(null, "", "/");
});

const at = (seconds: number) => new Date(Date.UTC(2026, 10, 18, 0, 0, seconds)).toISOString();

const run: RunHead = { kind: "run", id: "run:s_9", pos: "9.0", run: "s_9", turn: 2, at: at(0), end: at(9), trigger: "TASK_RESULT", phase: "Ended", endReason: "COMPLETED" };
const result: TaskNote = {
  kind: "task", id: "k:9", pos: "9.1", run: "s_9", turn: 2, step: 1, at: at(0), end: at(0),
  side: "RESULT", task: "t7-0", session: "s-t7-0", agent: "reviewer", outcome: "FAILED", text: "could not read orders.py",
};
const brief: TaskNote = {
  kind: "task", id: "k:0", pos: "0.1", run: "s-t7-0_0", turn: 1, step: 1, at: at(0), end: at(0),
  side: "ASSIGNED", task: "t7-0", session: "s", text: "Check orders.py",
};
const call: ToolCall = {
  kind: "tool", id: "t:3.0", pos: "3.0", run: "s_1", turn: 1, step: 1, at: at(1), end: at(1), call: "c1", name: "task",
  rawArguments: '{"prompt":"Check orders.py"}', status: "DONE", result: "Started task t7-0", task: "t7-0", taskSession: "s-t7-0",
};

function inspect(record: TraceRecord) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <Inspector session="s" record={record} lookup={() => undefined} containerWidth={1280} overlay={false} onClose={vi.fn()} onOpen={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe("任务在轨迹页里", () => {
  it("结果这一行写明是哪个任务、什么结局，能搜到，排在请求之前", () => {
    expect(contentOf(result)).toBe(`${zh.tasks.id("t7-0")} · ${zh.trace.taskSide.RESULT} · ${zh.tasks.outcome.FAILED} · could not read orders.py`);
    expect(searchText(result)).toContain("could not read orders.py");
    expect(searchText(result)).toContain("s-t7-0");
    expect(laneOf(result)).toBe(0);
    expect(zh.trace.kind.task).toBe("任务");
  });

  it("结果开的那一轮写明不是人发起的，标题不拿任务的文字当用户的提问", () => {
    expect(contentOf(run)).toBe(zh.trace.trigger.TASK_RESULT);
    const tab = turnTabs([run, result])[0];
    expect(tab?.trigger).toBe("TASK_RESULT");
    expect(tab?.prompt).toBeUndefined();
    const rows = buildLedger([run, result], new Set(), new Set());
    const turn = rows.find((row) => row.type === "turn");
    expect(turn?.type === "turn" && turn.prompt).toBeFalsy();
  });

  it("结果的详情里能点进任务那个会话的轨迹", async () => {
    inspect(result);
    expect(screen.getAllByText(zh.tasks.outcome.FAILED).length).toBeGreaterThan(0);
    const link = screen.getByRole("link", { name: new RegExp(zh.trace.taskDetails.openTrace) });
    expect(link.getAttribute("href")).toBe(tracePath("s-t7-0"));
    await userEvent.click(link);
    expect(window.location.pathname).toBe(tracePath("s-t7-0"));
  });

  it("任务自己的轨迹里，交代的内容能点回放出它的会话", () => {
    inspect(brief);
    expect(screen.getByText("Check orders.py")).toBeTruthy();
    expect(screen.getByRole("link", { name: new RegExp(zh.trace.taskDetails.openTrace) }).getAttribute("href")).toBe(tracePath("s"));
  });

  it("放任务的那次工具调用带着任务的会话，能点过去", () => {
    inspect(call);
    expect(screen.getByRole("link", { name: new RegExp(zh.trace.taskDetails.openTrace) }).getAttribute("href")).toBe(tracePath("s-t7-0"));
  });
});
