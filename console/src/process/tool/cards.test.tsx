import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import type { ToolStepItem } from "../../api/types";
import { summarizeArguments } from "../chain";
import { ToolRow } from "../ToolRow";
import bashExit from "./fixtures/bash-exit.txt?raw";
import bashTruncated from "./fixtures/bash-truncated.txt?raw";
import grepCapped from "./fixtures/grep-capped.txt?raw";
import grepMatches from "./fixtures/grep-matches.txt?raw";

afterEach(cleanup);

function step(name: string, args: Record<string, unknown>, extra: Partial<ToolStepItem> = {}): ToolStepItem {
  return {
    kind: "tool",
    id: "a",
    session: "s",
    run: "run-0",
    at: "2026-11-11T00:00:02Z",
    call: { kind: "toolCall", id: "ca", name, rawArguments: JSON.stringify(args), arguments: args },
    status: "DONE",
    startedAt: "2026-11-11T00:00:02Z",
    endedAt: "2026-11-11T00:00:05Z",
    ...extra,
  };
}

function result(text: string, error = false, code?: string): ToolStepItem["result"] {
  return { kind: "toolResult", toolCallId: "ca", text, error, ...(code === undefined ? {} : { code }) };
}

async function expand(): Promise<void> {
  await userEvent.click(screen.getAllByRole("button")[0] as HTMLElement);
}

describe("terminal card", () => {
  it("shows the command, where it ran, the output and the exit code", async () => {
    const failed = step("bash", { command: "npm test\nnpm run lint", workdir: "web", timeout: 30 }, { status: "FAILED", result: result(bashExit, true) });
    expect(summarizeArguments(failed)).toBe("npm test npm run lint");
    render(<ToolRow step={failed} />);
    // 折叠着就看得到退出码
    expect(screen.getAllByRole("button")[0]?.textContent).toContain("退出码 2");
    await expand();
    const card = within(screen.getByRole("region", { name: "终端" }));
    expect(card.getByText("npm test")).not.toBeNull();
    expect(card.getByText("npm run lint")).not.toBeNull();
    expect(card.getByText("目录 web · 限时 30 秒")).not.toBeNull();
    expect(card.getByTestId("terminal-output").textContent).toBe("boom");
    expect(card.getByText("退出码 2")).not.toBeNull();
    // 说明已经变成标记，不再留在输出里；普通的参数和结果两块不出现
    expect(screen.queryByText("参数")).toBeNull();
    expect(screen.queryByText("结果")).toBeNull();
  });

  it("says so when there is no output, when only the tail is kept and while it runs", async () => {
    const { rerender } = render(<ToolRow step={step("bash", { command: "true" }, { result: result("(no output)") })} />);
    await expand();
    expect(screen.getByText("没有输出")).not.toBeNull();
    rerender(<ToolRow step={step("bash", { command: "yes" }, { result: result(bashTruncated) })} />);
    expect(screen.getByTestId("terminal-output").textContent).toBe("tail");
    expect(screen.getByText("只显示最后 2000 行，共 4321 行")).not.toBeNull();
    rerender(<ToolRow step={step("bash", { command: "sleep 9" }, { status: "RUNNING", endedAt: undefined })} />);
    expect(within(screen.getByRole("region", { name: "终端" })).getByText("运行中…")).not.toBeNull();
  });

  it("falls back to the plain view for a synthesized result, a blocked call or unusable arguments", async () => {
    const { rerender } = render(<ToolRow step={step("bash", { command: "x" }, { status: "FAILED", result: result("lost", true, "TOOL_OUTCOME_UNKNOWN") })} />);
    await expand();
    expect(screen.queryByRole("region", { name: "终端" })).toBeNull();
    expect(screen.getByText("参数")).not.toBeNull();
    rerender(<ToolRow step={step("bash", { command: "rm -rf /" }, { status: "BLOCKED", result: result("blocked by policy", true) })} />);
    expect(screen.queryByRole("region", { name: "终端" })).toBeNull();
    rerender(<ToolRow step={step("bash", { cmd: "x" }, { status: "FAILED", result: result("command is required", true) })} />);
    expect(screen.queryByRole("region", { name: "终端" })).toBeNull();
  });
});

describe("search card", () => {
  it("counts matches and files and lists line numbers with text", async () => {
    render(<ToolRow step={step("grep", { pattern: "COMPUTE", path: "sub" }, { result: result(grepMatches) })} />);
    await expand();
    const card = within(screen.getByRole("region", { name: "搜索结果" }));
    expect(card.getByText("2 处匹配 · 1 个文件")).not.toBeNull();
    expect(card.getByText("在 sub")).not.toBeNull();
    expect(card.getByRole("button", { name: /sub\/a\.txt/ })).not.toBeNull();
    expect(card.getByText("COMPUTE: again 12:34")).not.toBeNull();
    // 文件可以折起来
    await userEvent.click(card.getByRole("button", { name: /sub\/a\.txt/ }));
    expect(card.queryByText("COMPUTE: again 12:34")).toBeNull();
  });

  it("shows eight lines first and the rest on request, and says when the search stopped early", async () => {
    render(<ToolRow step={step("grep", { pattern: "hit" }, { result: result(grepCapped) })} />);
    await expand();
    const card = within(screen.getByRole("region", { name: "搜索结果" }));
    expect(card.getByText("匹配太多，只列出前面这些")).not.toBeNull();
    // 文件标题也算一行：标题加 7 行匹配
    expect(card.getAllByRole("listitem")).toHaveLength(7);
    await userEvent.click(card.getByRole("button", { name: "还有 193 行" }));
    expect(card.getAllByRole("listitem")).toHaveLength(200);
    expect(card.queryByRole("button", { name: /还有/ })).toBeNull();
  });

  it("says no matches, and falls back to the plain view for errors and text it cannot split", async () => {
    const { rerender } = render(<ToolRow step={step("grep", { pattern: "NOPE" }, { result: result("No matches.") })} />);
    await expand();
    expect(within(screen.getByRole("region", { name: "搜索结果" })).getByText("没有结果")).not.toBeNull();
    rerender(<ToolRow step={step("grep", { pattern: "(" }, { status: "FAILED", result: result("Invalid pattern: Unclosed group", true) })} />);
    expect(screen.queryByRole("region", { name: "搜索结果" })).toBeNull();
    expect(screen.getByText("Invalid pattern: Unclosed group")).not.toBeNull();
    rerender(<ToolRow step={step("grep", { pattern: "x" }, { result: result("something else entirely") })} />);
    expect(screen.queryByRole("region", { name: "搜索结果" })).toBeNull();
  });
});
