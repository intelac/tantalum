import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import pageJson from "../../../../tantalum-shell/src/test/resources/wire/trace.json?raw";
import type { ModelRequest, ToolCall, TraceCall, TracePage, TraceRecord } from "../../api/types";
import { clampWidth, defaultWidth, DETAILS_MAX_WIDTH, DETAILS_MIN_WIDTH, DETAILS_RESIZE_STEP, Inspector } from "./Inspector";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

// 后端 WireFormatTest 的整页：每种记录都有，含重试、并行工具、被拦的工具、插件工作和手动压缩。
const RECORDS = (JSON.parse(pageJson) as TracePage).records;
const byId = (id: string) => RECORDS.find((record) => record.id === id) as TraceRecord;

const CALL: TraceCall = {
  call: "s_5-0",
  system: "sys\nstyle: be brief",
  segments: [
    { chars: 3, source: { type: "context" } },
    { chars: 15, source: { type: "plugin", name: "style" } },
  ],
  tools: [{ spec: { name: "read_file", description: "Read a file", schema: { type: "object" } }, source: { type: "context" } }],
  messages: [{ role: "USER", content: [{ kind: "text", text: "q1" }], interrupted: false }],
  output: { toolsAllowed: true, maxTokens: 1024 },
  previous: { system: "sys", tools: [] },
};

function stubCalls(call: object = CALL) {
  const paths: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string) => {
      paths.push(input);
      return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(call)) });
    }),
  );
  return paths;
}

function open(record: TraceRecord, props: Partial<Parameters<typeof Inspector>[0]> = {}) {
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onClose = vi.fn();
  const onOpen = vi.fn();
  render(
    <QueryClientProvider client={queries}>
      <Inspector session="s" record={record} lookup={byId} containerWidth={1280} overlay={false} onClose={onClose} onOpen={onOpen} {...props} />
    </QueryClientProvider>,
  );
  return { onClose, onOpen };
}

function tabNames(): string[] {
  return within(screen.getByRole("tablist")).getAllByRole("tab").map((tab) => tab.textContent ?? "");
}

describe("检查器的页签", () => {
  it.each(RECORDS.map((record) => [record.kind, record.id] as const))("%s 记录 %s 都能打开", (_kind, id) => {
    stubCalls();
    open(byId(id));
    expect(screen.getByRole("complementary", { name: "事件详情" })).toBeTruthy();
    expect(tabNames().length).toBeGreaterThan(0);
  });

  it("请求：概述、系统提示词、工具、消息、选项、用量、计时", () => {
    stubCalls();
    open(byId("r:s_2-0"));
    expect(tabNames()).toEqual(["概述", "系统提示词", "工具", "消息", "选项", "用量", "计时"]);
  });

  it("工具：概述、参数、结果、Schema、计时；system：差异、系统提示词、工具；钩子：概述、来源", () => {
    stubCalls();
    open(byId("t:6.1"));
    expect(tabNames()).toEqual(["概述", "参数", "结果", "Schema", "计时"]);
    cleanup();
    open(byId("s:2"));
    expect(tabNames()).toEqual(["差异", "系统提示词", "工具"]);
    cleanup();
    open(byId("h:1.1"));
    expect(tabNames()).toEqual(["概述", "来源"]);
  });

  it("重试过的请求列出每次尝试、失败原因和等待时间", () => {
    stubCalls();
    open(byId("r:s_2-0"));
    expect(screen.getByText("第 1 次")).toBeTruthy();
    // 一次在“上次尝试错误”，一次在尝试列表里。
    expect(screen.getAllByText("RATE_LIMITED")).toHaveLength(2);
    expect(screen.getByText("HTTP 429: slow down")).toBeTruthy();
    expect(screen.getByText(/重试延迟 2\.00 秒/)).toBeTruthy();
  });

  it("系统提示词按需取请求详情，分段标出来源", async () => {
    const paths = stubCalls();
    open(byId("r:s_2-0"));
    expect(paths).toEqual([]);
    await userEvent.click(screen.getByRole("tab", { name: "系统提示词" }));
    expect(await screen.findByText(/style: be brief/)).toBeTruthy();
    expect(screen.getByText("插件 · style · 15 字")).toBeTruthy();
    expect(paths).toEqual(["/api/sessions/s/trace/calls/s_5-0"]);
  });

  it("还原的请求和当时的指纹对不上时，请求详情顶部提示", async () => {
    stubCalls({ ...CALL, fingerprintMismatch: true });
    open(byId("r:s_2-0"));
    await userEvent.click(screen.getByRole("tab", { name: "消息" }));
    expect((await screen.findByRole("alert")).textContent).toContain("与当时发出的请求不完全一致");
    cleanup();
    stubCalls();
    open(byId("r:s_2-0"));
    await userEvent.click(screen.getByRole("tab", { name: "消息" }));
    await screen.findByText(/输出/);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("system 的差异页签按行比较上一次请求", async () => {
    stubCalls();
    open(byId("s:2"));
    expect(await screen.findByText("+style: be brief")).toBeTruthy();
  });

  it("没有记录选项时页签仍在，显示“未记录选项”", async () => {
    stubCalls();
    const request = { ...(byId("r:s_8-0") as ModelRequest), options: undefined };
    open(request);
    await userEvent.click(screen.getByRole("tab", { name: "选项" }));
    expect(screen.getByText("未记录选项")).toBeTruthy();
  });

  it("出错的工具原样显示结果文字，只有空白的也保留；没有文字才显示错误码", async () => {
    stubCalls();
    const blank: ToolCall = { ...(byId("t:6.2") as ToolCall), result: "  \n " };
    open(blank);
    await userEvent.click(screen.getByRole("tab", { name: "结果" }));
    const pre = document.querySelector("[role=tabpanel] pre");
    expect(pre?.textContent).toBe("  \n ");
    cleanup();
    open({ ...(byId("t:6.2") as ToolCall), result: undefined, code: "INVALID_ARGUMENTS" });
    await userEvent.click(screen.getByRole("tab", { name: "结果" }));
    expect(within(screen.getByRole("tabpanel")).getByText("INVALID_ARGUMENTS")).toBeTruthy();
  });

  it("钩子要求的工作、被拦的工具可以直接打开", async () => {
    stubCalls();
    const first = open(byId("h:1.1"));
    await userEvent.click(screen.getByRole("button", { name: "打开 w:s_1-0" }));
    expect(first.onOpen).toHaveBeenCalledWith("w:s_1-0");
    cleanup();
    const second = open(byId("h:6.0"));
    await userEvent.click(screen.getByRole("button", { name: "打开 t:6.3" }));
    expect(second.onOpen).toHaveBeenCalledWith("t:6.3");
  });
});

describe("检查器的外壳", () => {
  it("默认宽度 clamp(320px, 38%, 440px)，拖动范围 320–720 px，并给记录表留 280 px", () => {
    expect(defaultWidth(600)).toBe(DETAILS_MIN_WIDTH);
    expect(defaultWidth(1000)).toBe(380);
    expect(defaultWidth(2000)).toBe(440);
    expect(clampWidth(100, 1280)).toBe(DETAILS_MIN_WIDTH);
    expect(clampWidth(5000, 2000)).toBe(DETAILS_MAX_WIDTH);
    expect(clampWidth(700, 900)).toBe(620);
  });

  it("把手：左右键每次 16 px，记住宽度；双击恢复默认", () => {
    stubCalls();
    open(byId("u:0"));
    const handle = screen.getByRole("separator", { name: "调整事件详情宽度" });
    expect(handle.getAttribute("aria-valuenow")).toBe("440");
    fireEvent.keyDown(handle, { key: "ArrowRight" });
    expect(handle.getAttribute("aria-valuenow")).toBe(String(440 - DETAILS_RESIZE_STEP));
    fireEvent.keyDown(handle, { key: "ArrowLeft" });
    fireEvent.keyDown(handle, { key: "ArrowLeft" });
    expect(handle.getAttribute("aria-valuenow")).toBe(String(440 + DETAILS_RESIZE_STEP));
    expect(window.localStorage.getItem("tantalum.trace.inspectorWidth")).toBe(String(440 + DETAILS_RESIZE_STEP));
    fireEvent.doubleClick(handle);
    expect(handle.getAttribute("aria-valuenow")).toBe("440");
  });

  it("Esc 和关闭按钮都关闭", async () => {
    stubCalls();
    const { onClose } = open(byId("u:0"));
    fireEvent.keyDown(document, { key: "Escape" });
    await userEvent.click(screen.getByRole("button", { name: "关闭详情" }));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("窄屏时盖在记录表上，没有拖宽把手", () => {
    stubCalls();
    open(byId("u:0"), { overlay: true, containerWidth: 390 });
    expect(screen.queryByRole("separator")).toBeNull();
    expect(screen.getByRole("complementary").className).toContain("absolute inset-0");
  });
});
