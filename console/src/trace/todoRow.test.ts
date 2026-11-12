import { describe, expect, it } from "vitest";
import type { ToolCall } from "../api/types";
import { toolCells } from "./LedgerRow";

const TODOS = { todos: [{ content: "Read", status: "completed" }, { content: "Fix", status: "in_progress" }] };

function tool(status: ToolCall["status"], rawArguments: string, result = "ok"): ToolCall {
  return { kind: "tool", id: "t:1", pos: "1.1", run: "s_0", turn: 1, step: 1, at: "2026-11-13T00:00:00Z", call: "c1", name: "todo_write", rawArguments, status, request: "r:1", result };
}

describe("todo_write on the trace page", () => {
  it("shows the progress instead of the raw list when it was written", () => {
    expect(toolCells(tool("DONE", JSON.stringify(TODOS))).call).toBe("todo_write 1/2 已完成 · 正在：Fix");
  });

  it("keeps the raw arguments when it was refused or cannot be read", () => {
    const refused = JSON.stringify({ todos: [{ content: "a", status: "in_progress" }, { content: "b", status: "in_progress" }] });
    expect(toolCells(tool("ERROR", refused, "Invalid todos")).call).toContain('"todos"');
    expect(toolCells(tool("DONE", "{oops")).call).toBe("todo_write {oops");
  });
});
