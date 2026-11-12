import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { ToolStepItem } from "../api/types";
import { ToolRow } from "../process/ToolRow";
import { PlanBar } from "./PlanBar";
import type { TodoItem } from "./todos";

const TODOS: TodoItem[] = [
  { content: "读失败的测试", status: "completed" },
  { content: "修 apply_discount", status: "in_progress" },
  { content: "跑全部测试", status: "pending" },
];

function step(extra: Partial<ToolStepItem> = {}): ToolStepItem {
  const args = { todos: TODOS };
  return {
    kind: "tool",
    id: "a",
    session: "s",
    run: "run-0",
    at: "2026-11-13T00:00:02Z",
    call: { kind: "toolCall", id: "ca", name: "todo_write", rawArguments: JSON.stringify(args), arguments: args },
    status: "DONE",
    result: { kind: "toolResult", toolCallId: "ca", text: "Todo list updated: 1 of 3 completed; in progress: 修 apply_discount.", error: false },
    ...extra,
  };
}

beforeEach(() => localStorage.clear());
afterEach(cleanup);

describe("plan bar", () => {
  it("shows the progress and the whole list, and remembers being collapsed", async () => {
    render(<PlanBar todos={TODOS} />);
    const bar = screen.getByRole("region", { name: "计划" });
    expect(bar.textContent).toContain("1/3 已完成 · 正在：修 apply_discount");
    expect(screen.getByText("读失败的测试").className).toContain("line-through");
    expect(screen.getByText("修 apply_discount").className).toContain("font-medium");
    await userEvent.click(screen.getByRole("button", { name: "收起计划" }));
    expect(screen.queryByText("跑全部测试")).toBeNull();
    expect(localStorage.getItem("tantalum.plan.collapsed")).toBe("1");
    cleanup();
    render(<PlanBar todos={TODOS} />);
    expect(screen.queryByText("跑全部测试")).toBeNull();
    expect(screen.getByRole("button", { name: "展开计划" })).not.toBeNull();
  });

  it("says all done when every item is completed", () => {
    render(<PlanBar todos={[{ content: "a", status: "completed" }]} />);
    expect(screen.getByRole("region", { name: "计划" }).textContent).toContain("1/1 已完成 · 全部完成");
  });
});

describe("todo_write row", () => {
  it("summarizes the list and expands to it instead of raw JSON", async () => {
    render(<ToolRow step={step()} />);
    const row = screen.getAllByRole("button")[0] as HTMLElement;
    expect(row.textContent).toContain("1/3 已完成 · 正在：修 apply_discount");
    expect(row.textContent).not.toContain("{");
    await userEvent.click(row);
    expect(screen.getByText("跑全部测试")).not.toBeNull();
    expect(screen.queryByText("参数")).toBeNull();
  });

  it("shows a refused list as an error, the usual way", async () => {
    render(
      <ToolRow
        step={step({
          status: "FAILED",
          result: { kind: "toolResult", toolCallId: "ca", text: "Invalid todos: at most one item may be in_progress (got 2).", error: true },
        })}
      />,
    );
    await userEvent.click(screen.getAllByRole("button")[0] as HTMLElement);
    expect(screen.getByText("Invalid todos: at most one item may be in_progress (got 2).")).not.toBeNull();
    expect(screen.getByText("参数")).not.toBeNull();
  });
});
