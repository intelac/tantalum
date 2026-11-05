import { describe, expect, it } from "vitest";
import { groupBlocks } from "./blocks";

const shapes: Record<string, { kind: "user" | "run" | "thinking" | "tool" | "text" | "compaction" | "toolChange"; run?: string }> = {
  "0.0": { kind: "user", run: "run-0" },
  "run:run-0": { kind: "run", run: "run-0" },
  "2.0": { kind: "thinking", run: "run-0" },
  "2.1": { kind: "text", run: "run-0" },
  "2.2": { kind: "tool", run: "run-0" },
  "4.0": { kind: "thinking", run: "run-0" },
  "4.1": { kind: "text", run: "run-0" },
  "4.2": { kind: "text", run: "run-0" },
  "5.0": { kind: "user" },
  "6.0": { kind: "user", run: "run-6" },
  "run:run-6": { kind: "run", run: "run-6" },
  "8.0": { kind: "text", run: "run-6" },
};

describe("groupBlocks", () => {
  it("puts trailing text in the reply and everything else in the process", () => {
    const order = ["0.0", "run:run-0", "2.0", "2.1", "2.2", "4.0", "4.1", "4.2", "5.0", "6.0", "run:run-6", "8.0"];
    expect(groupBlocks(order, (id) => shapes[id])).toEqual([
      { kind: "user", id: "0.0" },
      { kind: "run", run: "run-0", process: ["2.0", "2.1", "2.2", "4.0"], reply: ["4.1", "4.2"] },
      { kind: "user", id: "5.0" },
      { kind: "user", id: "6.0" },
      { kind: "run", run: "run-6", process: [], reply: ["8.0"] },
    ]);
  });

  it("moves text into the process once a later step arrives", () => {
    const before = groupBlocks(["0.0", "run:run-0", "2.1"], (id) => shapes[id]);
    expect(before[1]).toMatchObject({ reply: ["2.1"], process: [] });
    const after = groupBlocks(["0.0", "run:run-0", "2.1", "2.2"], (id) => shapes[id]);
    expect(after[1]).toMatchObject({ reply: [], process: ["2.1", "2.2"] });
  });

  it("puts a compaction mark in its own block in order", () => {
    const withMark: typeof shapes = { ...shapes, "3.0": { kind: "compaction", run: "run-0" } };
    const blocks = groupBlocks(["0.0", "run:run-0", "2.0", "3.0", "4.1"], (id) => withMark[id]);
    expect(blocks).toEqual([
      { kind: "user", id: "0.0" },
      { kind: "run", run: "run-0", process: ["2.0"], reply: ["4.1"] },
      { kind: "compaction", id: "3.0" },
    ]);
  });

  it("puts a tool change at the top of its run, not in the process", () => {
    const withChange: typeof shapes = { ...shapes, "1.0": { kind: "toolChange", run: "run-0" } };
    const blocks = groupBlocks(["0.0", "run:run-0", "1.0", "2.0", "4.1"], (id) => withChange[id]);
    expect(blocks[1]).toEqual({ kind: "run", run: "run-0", process: ["2.0"], reply: ["4.1"], notices: ["1.0"] });
  });

  it("skips unknown ids", () => {
    expect(groupBlocks(["nope"], () => undefined)).toEqual([]);
  });
});
