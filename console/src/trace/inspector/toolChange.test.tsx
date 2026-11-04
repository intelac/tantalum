import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import pageJson from "../../../../tantalum-shell/src/test/resources/wire/trace.json?raw";
import type { ContextChange, ToolCall, TracePage, TraceRecord } from "../../api/types";
import { matchRecords } from "../search";
import { Inspector } from "./Inspector";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const RECORDS = (JSON.parse(pageJson) as TracePage).records;
const TOOL = RECORDS.find((record) => record.kind === "tool") as ToolCall;
const CONTEXT = RECORDS.find((record) => record.kind === "context") as ContextChange;

function open(record: TraceRecord) {
  vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("{}") })));
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <Inspector session="s" record={record} lookup={() => undefined} containerWidth={1280} overlay={false} onClose={vi.fn()} onOpen={vi.fn()} />
    </QueryClientProvider>,
  );
}

function tabNames(): string[] {
  return within(screen.getByRole("tablist")).getAllByRole("tab").map((tab) => tab.textContent ?? "");
}

/** core-tools 需求 6 第 4 条、C14：轨迹页上的改文件和工具变化。 */
describe("轨迹页的文件工具和工具变化", () => {
  it("edit_file 有差异页签，按行显示原文和新文字", async () => {
    const edit: ToolCall = {
      ...TOOL,
      name: "edit_file",
      rawArguments: JSON.stringify({ path: "a.py", old_string: "return x * d", new_string: "return x * (1 - d)" }),
      arguments: { path: "a.py", old_string: "return x * d", new_string: "return x * (1 - d)" },
    };
    open(edit);
    expect(tabNames()).toEqual(["概述", "差异", "参数", "结果", "Schema", "计时"]);
    await userEvent.click(screen.getByRole("tab", { name: "差异" }));
    expect(screen.getByText("-return x * d")).toBeTruthy();
    expect(screen.getByText("+return x * (1 - d)")).toBeTruthy();
  });

  it("其他工具没有差异页签", () => {
    open(TOOL);
    expect(tabNames()).not.toContain("差异");
  });

  it("只有工具变化的上下文记录：工具页签列出新增和移除", async () => {
    open({ ...CONTEXT, sections: [], toolsAdded: ["write_file"], toolsRemoved: ["grep"] });
    expect(tabNames()).toEqual(["工具", "来源"]);
    expect(screen.getByText("新增工具")).toBeTruthy();
    expect(screen.getByText("write_file")).toBeTruthy();
    expect(screen.getByText("grep")).toBeTruthy();
  });

  it("可变内容和工具都变了：两个页签都在", () => {
    open({ ...CONTEXT, toolsAdded: ["write_file"] });
    expect(tabNames()).toEqual(["上下文", "工具", "来源"]);
  });

  it("搜索能找到变化的工具名", () => {
    const record = { ...CONTEXT, id: "c:x", sections: [], toolsAdded: ["write_file"] };
    expect(matchRecords([record], "write_file").has("c:x")).toBe(true);
  });
});
