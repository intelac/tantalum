import { describe, expect, it } from "vitest";
import type { Item, ToolStepItem } from "../api/types";
import { currentTodos, parseTodos, progressOf, summaryOf } from "./todos";

function step(id: string, run: string, todos: unknown, options: { error?: boolean; status?: ToolStepItem["status"]; name?: string } = {}): ToolStepItem {
  return {
    kind: "tool",
    id,
    session: "s1",
    run,
    at: "2026-11-13T00:00:00Z",
    status: options.status ?? "DONE",
    call: { kind: "toolCall", id, name: options.name ?? "todo_write", rawArguments: "{}", arguments: { todos } },
    result: { kind: "toolResult", toolCallId: id, text: "ok", error: options.error ?? false },
  };
}

const A = [
  { content: "Read", status: "completed" },
  { content: "Fix", status: "in_progress" },
  { content: "Test", status: "pending" },
];

describe("todos", () => {
  it("reads a list and refuses what is not one", () => {
    expect(parseTodos({ todos: A })).toEqual(A);
    expect(parseTodos({ todos: [{ content: "  x ", status: "pending" }] })).toEqual([{ content: "x", status: "pending" }]);
    expect(parseTodos({ todos: [{ content: "x", status: "done" }] })).toBeNull();
    expect(parseTodos({ todos: [{ content: " ", status: "pending" }] })).toBeNull();
    expect(parseTodos({ todos: "x" })).toBeNull();
    expect(parseTodos(null)).toBeNull();
  });

  it("takes the last successful write of the latest run", () => {
    const items: Item[] = [
      step("a", "r1", [{ content: "Old", status: "in_progress" }]),
      step("b", "r2", A),
      step("c", "r2", [{ content: "x", status: "in_progress" }, { content: "y", status: "in_progress" }], { error: true, status: "FAILED" }),
      step("d", "r2", [{ content: "z", status: "pending" }], { name: "read_file" }),
    ];
    expect(currentTodos(items, "r2")).toEqual(A);
    expect(currentTodos(items, "r1")).toEqual([{ content: "Old", status: "in_progress" }]);
    // 下一轮开始了、还没写：横条消失
    expect(currentTodos(items, "r3")).toBeNull();
    expect(currentTodos(items, null)).toBeNull();
    // 还在执行的那次不算
    expect(currentTodos([step("e", "r4", A, { status: "RUNNING" })], "r4")).toBeNull();
    // 写了一张空的：清掉
    expect(currentTodos([step("f", "r5", A), step("g", "r5", [])], "r5")).toBeNull();
  });

  it("counts the progress", () => {
    expect(progressOf(A as never)).toEqual({ done: 1, total: 3, current: "Fix" });
    expect(progressOf([{ content: "a", status: "completed" }])).toEqual({ done: 1, total: 1, current: null });
  });
});

describe("summaryOf", () => {
  const text = { progress: (d: number, n: number) => `${d}/${n}`, current: (x: string) => `now ${x}`, allDone: "all" } as never;
  it("says how far it got", () => {
    expect(summaryOf(A as never, text)).toBe("1/3 · now Fix");
    expect(summaryOf([{ content: "a", status: "completed" }], text)).toBe("1/1 · all");
    expect(summaryOf([{ content: "a", status: "pending" }], text)).toBe("0/1");
  });
});
