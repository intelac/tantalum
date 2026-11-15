import { describe, expect, it } from "vitest";
import type { RunNode } from "../api/types";
import { axis, bar, defaultCollapsed, flatten, pathTo, repeats } from "./tree";

const T = (seconds: number) => new Date(Date.parse("2026-11-15T00:00:00Z") + seconds * 1000).toISOString();

function tool(id: string, name: string, state: RunNode["state"] = "ok"): RunNode {
  return { id, kind: "tool", name, state, start: T(1), end: T(2) };
}

function step(n: number, ...children: RunNode[]): RunNode {
  return { id: `r/s${n}`, kind: "step", name: `step ${n}`, state: children.some((c) => c.state === "error") ? "error" : "ok", children };
}

const request: RunNode = {
  id: "m1",
  kind: "request",
  name: "local/qwen",
  state: "ok",
  start: T(0),
  end: T(10),
  children: [
    { id: "m1#1", kind: "attempt", state: "error", start: T(0), end: T(2) },
    { id: "m1#2", kind: "attempt", state: "ok", start: T(4), end: T(10), firstToken: T(6) },
  ],
};

const root: RunNode = {
  id: "r",
  kind: "run",
  state: "error",
  start: T(0),
  end: T(20),
  children: [
    { id: "u", kind: "input", name: "PROMPT", state: "ok", start: T(0), end: T(0) },
    step(1, request, tool("t1", "read_file"), tool("t2", "bash", "error")),
    step(2, { id: "m2", kind: "request", state: "ok", children: [{ id: "m2#1", kind: "attempt", state: "ok" }] }),
  ],
};

describe("flatten", () => {
  it("lists rows depth first, hides collapsed children, and keeps only error paths when asked", () => {
    const collapsed = defaultCollapsed(root);
    expect([...collapsed]).toEqual(["m2"]);
    expect(flatten(root, collapsed, false).map((row) => `${row.depth}:${row.node.id}`)).toEqual([
      "0:u",
      "0:r/s1",
      "1:m1",
      "2:m1#1",
      "2:m1#2",
      "1:t1",
      "1:t2",
      "0:r/s2",
      "1:m2",
    ]);
    expect(flatten(root, collapsed, true).map((row) => row.node.id)).toEqual(["r/s1", "m1", "m1#1", "t2"]);
    expect(flatten(root, new Set(["r/s1"]), false).map((row) => row.node.id)).toEqual(["u", "r/s1", "r/s2", "m2", "m2#1"]);
  });

  it("finds the path to a node", () => {
    expect(pathTo(root, "t2").map((node) => node.id)).toEqual(["r", "r/s1", "t2"]);
    expect(pathTo(root, "nope")).toEqual([]);
  });
});

describe("repeats", () => {
  it("flags five or more consecutive calls of the same tool across steps", () => {
    const looping: RunNode = {
      id: "r",
      kind: "run",
      state: "ok",
      children: [
        step(1, tool("a", "read_file"), tool("b1", "list_dir")),
        ...Array.from({ length: 18 }, (_, i) => step(i + 2, tool(`b${i + 2}`, "list_dir"))),
        step(20),
      ],
    };
    expect(repeats(looping)).toEqual([{ name: "list_dir", count: 19, fromStep: 1, toStep: 19, first: "b1" }]);
    expect(repeats(root)).toEqual([]);
  });

  it("breaks a streak when another tool comes between", () => {
    const broken: RunNode = {
      id: "r",
      kind: "run",
      state: "ok",
      children: [step(1, tool("1", "x"), tool("2", "x"), tool("3", "y"), tool("4", "x"), tool("5", "x"), tool("6", "x"))],
    };
    expect(repeats(broken, 3)).toEqual([{ name: "x", count: 3, fromStep: 1, toStep: 1, first: "4" }]);
  });
});

describe("bar", () => {
  it("places nodes on the run axis and splits a request at its last attempt's first token", () => {
    const span = axis(root, 0) as { start: number; end: number };
    expect(bar(request, span, 0)).toEqual({ left: 0, width: 0.5, split: 0.6 });
    expect(bar(tool("t", "x"), span, 0)).toEqual({ left: 0.05, width: 0.05 });
    expect(bar({ id: "z", kind: "tool", state: "ok" }, span, 0)).toBeUndefined();
  });

  it("draws open nodes up to now and keeps very short bars visible", () => {
    const open: RunNode = { id: "r", kind: "run", state: "running", start: T(0) };
    const span = axis(open, Date.parse(T(100))) as { start: number; end: number };
    expect(span.end - span.start).toBe(100_000);
    expect(bar({ id: "x", kind: "tool", state: "running", start: T(50) }, span, Date.parse(T(100)))).toEqual({ left: 0.5, width: 0.5 });
    expect(bar({ id: "y", kind: "hook", state: "ok", start: T(10), end: T(10) }, span, 0)?.width).toBe(0.004);
  });
});
