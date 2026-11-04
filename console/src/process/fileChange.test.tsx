import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import type { ToolStepItem } from "../api/types";
import { summarizeArguments } from "./chain";
import { readRange } from "./FileChange";
import { ToolRow } from "./ToolRow";

afterEach(cleanup);

function step(name: string, args: Record<string, unknown>, result = "ok"): ToolStepItem {
  return {
    kind: "tool",
    id: "x",
    session: "s",
    run: "run-0",
    at: "2026-11-04T00:00:02Z",
    call: { kind: "toolCall", id: "c", name, rawArguments: JSON.stringify(args), arguments: args },
    status: "DONE",
    startedAt: "2026-11-04T00:00:02Z",
    endedAt: "2026-11-04T00:00:03Z",
    result: { kind: "toolResult", toolCallId: "c", text: result, error: false },
  };
}

/** core-tools 需求 6：从调用参数和结果文字算出，不另存。 */
describe("file tools in the conversation", () => {
  it("shows an edit as the difference between the old and new text", async () => {
    render(<ToolRow step={step("edit_file", { path: "pricing.py", old_string: "return subtotal * discount", new_string: "return subtotal * (1 - discount)" })} />);
    expect(screen.getByText("pricing.py")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { expanded: false }));
    expect(screen.getByText("-return subtotal * discount")).toBeTruthy();
    expect(screen.getByText("+return subtotal * (1 - discount)")).toBeTruthy();
    expect(screen.queryByText(/^@@/)).toBeNull();
    expect(screen.queryByText("参数")).toBeNull();
  });

  it("marks replace-all edits", async () => {
    render(<ToolRow step={step("edit_file", { path: "a.txt", old_string: "x", new_string: "y", replace_all: true })} />);
    await userEvent.click(screen.getByRole("button", { expanded: false }));
    expect(screen.getByText("全部替换")).toBeTruthy();
  });

  it("previews the first 20 lines of a write with its size and expands to all of it", async () => {
    const content = Array.from({ length: 30 }, (_, i) => `line ${i + 1}`).join("\n") + "\n";
    render(<ToolRow step={step("write_file", { path: "notes/a.md", content })} />);
    expect(screen.getByText("notes/a.md")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { expanded: false }));
    expect(screen.getByText(`${new TextEncoder().encode(content).length} 字节`)).toBeTruthy();
    const preview = () => document.querySelector("pre")?.textContent ?? "";
    expect(preview()).toContain("line 20");
    expect(preview()).not.toContain("line 21");
    await userEvent.click(screen.getByRole("button", { name: "展开全部 30 行" }));
    expect(preview()).toContain("line 30");
    await userEvent.click(screen.getByRole("button", { name: "收起" }));
    expect(preview()).not.toContain("line 21");
  });

  it("counts bytes, not characters", async () => {
    render(<ToolRow step={step("write_file", { path: "zh.md", content: "你好" })} />);
    await userEvent.click(screen.getByRole("button", { expanded: false }));
    expect(screen.getByText("6 字节")).toBeTruthy();
  });

  it("says which lines a read covered", () => {
    expect(readRange(step("read_file", { path: "big.log", offset: 41000, limit: 501 }, "...\n\n[Showing lines 41000-41500 of 60000. Use offset=41501 to continue.]")))
      .toBe("第 41000–41500 行，共 60000 行");
    expect(readRange(step("read_file", { path: "big.log", offset: "59990" }, "tail"))).toBe("从第 59990 行");
    expect(readRange(step("read_file", { path: "a.txt", limit: 10 }, "short"))).toBe("最多 10 行");
    expect(readRange(step("read_file", { path: "a.txt" }, "whole"))).toBeNull();
    expect(readRange(step("grep", { pattern: "x" }))).toBeNull();
  });

  it("puts the read range next to the path", () => {
    render(<ToolRow step={step("read_file", { path: "big.log", offset: 10 }, "x")} />);
    expect(screen.getByText("从第 10 行")).toBeTruthy();
  });

  it("summarizes writes and edits by their path", () => {
    expect(summarizeArguments(step("edit_file", { path: "a.txt", old_string: "x", new_string: "y" }))).toBe("a.txt");
    expect(summarizeArguments(step("write_file", { path: "b.txt", content: "z" }))).toBe("b.txt");
  });

  it("falls back to the raw arguments when an edit is missing its text", async () => {
    render(<ToolRow step={step("edit_file", { path: "a.txt" })} />);
    await userEvent.click(screen.getByRole("button", { expanded: false }));
    expect(screen.getByText("参数")).toBeTruthy();
  });
});
