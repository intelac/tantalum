import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import type { RunStatusItem, ToolStepItem } from "../api/types";
import { explain } from "../conversation/RunStatusRow";
import { ConversationStore } from "../store/ConversationStore";
import { chainOf, summarizeArguments } from "./chain";
import { headerText, ProcessGroup } from "./ProcessGroup";
import { ThinkingBlock } from "./ThinkingBlock";
import { ToolChain } from "./ToolChain";
import { ToolRow } from "./ToolRow";

afterEach(cleanup);

function step(id: string, extra: Partial<ToolStepItem> = {}): ToolStepItem {
  return {
    kind: "tool",
    id,
    session: "s",
    run: "run-0",
    at: "2026-10-31T00:00:02Z",
    call: { kind: "toolCall", id: `c${id}`, name: "read_file", rawArguments: '{"path":"src/a.txt"}', arguments: { path: "src/a.txt" } },
    status: "DONE",
    group: "g2",
    startedAt: "2026-10-31T00:00:02Z",
    endedAt: "2026-10-31T00:00:05Z",
    result: { kind: "toolResult", toolCallId: `c${id}`, text: "file body", error: false },
    ...extra,
  };
}

describe("chain", () => {
  it("groups consecutive steps with the same group and keeps waiting ones alone", () => {
    const groups = chainOf([step("a"), step("b"), step("c", { group: "g5" }), step("d", { status: "WAITING", group: undefined, startedAt: undefined, endedAt: undefined, result: undefined })]);
    expect(groups.map((group) => group.map((s) => s.id))).toEqual([["a", "b"], ["c"], ["d"]]);
  });

  it("summarizes a single string argument directly", () => {
    expect(summarizeArguments(step("a"))).toBe("src/a.txt");
    expect(summarizeArguments(step("a", { call: { kind: "toolCall", id: "c", name: "grep", rawArguments: "{}", arguments: { pattern: "x", path: "y" } } }))).toBe('{"pattern":"x","path":"y"}');
    expect(summarizeArguments(step("a", { call: { kind: "toolCall", id: "c", name: "grep", rawArguments: "{oops" } }))).toBe("{oops");
  });
});

describe("ThinkingBlock", () => {
  it("renders nothing when empty, and a notice when redacted", () => {
    const { container } = render(<ThinkingBlock text="" />);
    expect(container.innerHTML).toBe("");
    render(<ThinkingBlock text="" redacted streaming />);
    expect(screen.getByText("思考内容已被供应商隐藏")).not.toBeNull();
  });

  it("is collapsed after settling and expands on click", async () => {
    render(<ThinkingBlock text="先看文件" />);
    const toggle = screen.getByRole("button");
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    await userEvent.click(toggle);
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(screen.getAllByText("先看文件").length).toBeGreaterThan(0);
  });
});

describe("ToolRow", () => {
  it("shows name, summary, status and duration, and expands to arguments and result", async () => {
    render(<ToolRow step={step("a")} />);
    const row = screen.getByRole("button");
    expect(row.textContent).toContain("read_file");
    expect(row.textContent).toContain("src/a.txt");
    expect(row.textContent).toContain("完成");
    expect(row.textContent).toContain("3 秒");
    await userEvent.click(row);
    expect(screen.getByText("参数")).not.toBeNull();
    expect(screen.getByText(/"path": "src\/a.txt"/)).not.toBeNull();
    expect(screen.getByText("file body")).not.toBeNull();
  });

  it("marks errors, blocked calls and synthesized results", async () => {
    render(<ToolRow step={step("f", { status: "FAILED", result: { kind: "toolResult", toolCallId: "cf", text: "no such file", error: true } })} />);
    expect(screen.getByRole("button").textContent).toContain("失败");
    await userEvent.click(screen.getByRole("button"));
    expect(screen.getByText("no such file").className).toContain("danger");
    cleanup();

    render(<ToolRow step={step("b", { status: "BLOCKED", startedAt: undefined, endedAt: undefined, result: { kind: "toolResult", toolCallId: "cb", text: "Tool call blocked: destructive", error: true } })} />);
    expect(screen.getByRole("button").textContent).toContain("已拦截");
    await userEvent.click(screen.getByRole("button"));
    expect(screen.getByText("Tool call blocked: destructive")).not.toBeNull();
    cleanup();

    render(<ToolRow step={step("u", { status: "FAILED", endedAt: "2026-10-31T00:00:03Z", result: { kind: "toolResult", toolCallId: "cu", text: "unknown", error: true, code: "TOOL_OUTCOME_UNKNOWN" } })} />);
    await userEvent.click(screen.getByRole("button"));
    expect(screen.getByText("TOOL_OUTCOME_UNKNOWN")).not.toBeNull();
    expect(screen.getByText("结果未知：工具可能已执行")).not.toBeNull();
  });

  it("shows waiting steps without a duration", () => {
    render(<ToolRow step={step("w", { status: "WAITING", group: undefined, startedAt: undefined, endedAt: undefined, result: undefined })} />);
    const row = screen.getByRole("button");
    expect(row.textContent).toContain("等待中");
    expect(row.textContent).not.toContain("秒");
  });
});

describe("ToolChain", () => {
  it("renders parallel steps as a group and others as single rows", () => {
    const store = new ConversationStore();
    store.applyItem(step("a"));
    store.applyItem(step("b"));
    store.applyItem(step("c", { group: "g7" }));
    render(<ToolChain store={store} ids={["a", "b", "c"]} />);
    expect(screen.getByRole("group", { name: "2 个并行调用" })).not.toBeNull();
    expect(screen.getAllByRole("button")).toHaveLength(3);
  });
});

describe("ProcessGroup", () => {
  const status: RunStatusItem = { kind: "run", id: "run:run-0", session: "s", run: "run-0", at: "2026-10-31T00:00:00Z", updatedAt: "2026-10-31T00:00:32Z", phase: "Ended", endReason: "COMPLETED" };

  it("is expanded while running and collapses with the elapsed time when done", async () => {
    const store = new ConversationStore();
    store.applyItem({ ...status, phase: "AwaitingTools", endReason: undefined, updatedAt: "2026-10-31T00:00:02Z" });
    store.applyItem(step("a"));
    render(<ProcessGroup store={store} run="run-0" statusId="run:run-0" ids={["a"]} />);
    const header = screen.getAllByRole("button")[0];
    expect(header?.textContent).toContain("正在处理");
    expect(header?.getAttribute("aria-expanded")).toBe("true");

    store.applyItem(status);
    expect((await screen.findByText("已完成，用时 32 秒")).closest("button")?.getAttribute("aria-expanded")).toBe("false");
    await userEvent.click(screen.getByText("已完成，用时 32 秒"));
    expect(screen.getByText("read_file")).not.toBeNull();
  });

  it("shows live reasoning while streaming", () => {
    const store = new ConversationStore();
    store.applyItem({ ...status, phase: "AwaitingModel", endReason: undefined });
    store.applyChunk("run-0", "reasoning", "想一想");
    render(<ProcessGroup store={store} run="run-0" statusId="run:run-0" ids={[]} />);
    expect(screen.getByText("想一想")).not.toBeNull();
    expect(headerText(undefined, 0)).toBe("正在处理…");
    expect(headerText({ ...status, endReason: "FAILED" }, 0)).toBe("已失败，用时 32 秒");
  });
});

describe("explain", () => {
  const base: RunStatusItem = { kind: "run", id: "run:run-0", session: "s", run: "run-0", at: "t", updatedAt: "t", phase: "Ended" };
  it("has a sentence per failure reason", () => {
    expect(explain({ ...base, endReason: "FAILED", failure: "CONTEXT_OVERFLOW" })).toBe("上下文超长，模型放不下这段对话。请开一个新会话继续，记忆会带过去");
    expect(explain({ ...base, endReason: "FAILED", failure: "REFUSAL" })).toBe("模型拒绝回答");
    expect(explain({ ...base, endReason: "FAILED", failure: "RATE_LIMITED" })).toBe("触发限流，稍后再试");
    expect(explain({ ...base, endReason: "FAILED", detail: "output ring full" })).toBe("命令丢失，运行失败");
    expect(explain({ ...base, endReason: "CANCELLED" })).toBe("已取消");
    expect(explain({ ...base, endReason: "STOPPED", detail: "policy" })).toBe("被钩子停止：policy");
    expect(explain({ ...base, endReason: "COMPLETED" })).toBeNull();
    expect(explain({ ...base })).toBeNull();
  });
});
