import { describe, expect, it } from "vitest";
import type { ToolStepItem } from "../api/types";
import { doneKinds, kindOf, MAX_DETAIL, runningDetail, runningStep } from "./activity";

function step(name: string, args: unknown = {}, status: ToolStepItem["status"] = "DONE"): ToolStepItem {
  return { kind: "tool", id: `${name}-${Math.random()}`, session: "s", run: "run-0", at: "2026-11-11T00:00:00Z", call: { kind: "toolCall", id: "c", name, rawArguments: JSON.stringify(args), arguments: args }, status };
}

describe("kindOf", () => {
  it.each([
    ["read_file", "read"],
    ["read_attachment", "read"],
    ["list_dir", "read"],
    ["grep", "search"],
    ["find_files", "search"],
    ["write_file", "write"],
    ["edit_file", "edit"],
    ["bash", "command"],
    ["git_log", "git"],
    ["git_blame", "git"],
    ["web_fetch", "web"],
    ["browser_click", "web"],
    ["memory_write", "memory"],
    ["ask_user", "question"],
    ["jira__search", "tool"],
    ["constructor", "tool"],
    ["", "tool"],
  ])("%s is %s", (name, kind) => {
    expect(kindOf(name)).toBe(kind);
  });
});

describe("doneKinds", () => {
  it("has nothing to say when no tool ran", () => {
    expect(doneKinds([])).toEqual({ kinds: [], more: false });
  });

  it("orders by count, then by first appearance", () => {
    const steps = [step("bash"), step("read_file"), step("grep"), step("read_file"), step("list_dir")];
    expect(doneKinds(steps)).toEqual({ kinds: ["read", "command", "search"], more: false });
  });

  it("keeps three kinds and says there are more", () => {
    const steps = [step("bash"), step("read_file"), step("grep"), step("edit_file"), step("edit_file")];
    expect(doneKinds(steps)).toEqual({ kinds: ["edit", "command", "read"], more: true });
  });
});

describe("runningStep", () => {
  it("is the latest step still running", () => {
    const first = step("bash", {}, "RUNNING");
    const second = step("grep", {}, "RUNNING");
    expect(runningStep([first, step("read_file"), second, step("list_dir", {}, "WAITING")])).toBe(second);
    expect(runningStep([step("read_file")])).toBeUndefined();
  });
});

describe("runningDetail", () => {
  it.each([
    [{ command: "npm  test\n  --watch" }, "npm test --watch"],
    [{ path: "src/a.ts", pattern: "TODO" }, "TODO"],
    [{ url: "https://example.com/a" }, "https://example.com/a"],
    [{ path: "src/a.ts" }, "src/a.ts"],
    [{ command: "   ", path: "src/a.ts" }, "src/a.ts"],
    [{ command: 42 }, ""],
    [{ other: "x" }, ""],
    ["not an object", ""],
    [null, ""],
  ])("%j gives %j", (args, detail) => {
    expect(runningDetail(step("bash", args, "RUNNING"))).toBe(detail);
  });

  it("cuts long details by characters, not code units", () => {
    const detail = runningDetail(step("bash", { command: "😀".repeat(MAX_DETAIL + 5) }, "RUNNING"));
    expect(Array.from(detail)).toHaveLength(MAX_DETAIL);
    expect(detail.endsWith("…")).toBe(true);
  });
});
