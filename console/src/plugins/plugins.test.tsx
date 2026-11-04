import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Plugins } from "../api/types";
import { applyToggle } from "./usePlugins";
import { PluginsDialog } from "./PluginsDialog";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const PLUGINS: Plugins = {
  midSessionChanges: true,
  tools: [
    { name: "read_file", description: "Read a UTF-8 text file.", enabled: true },
    { name: "write_file", description: "Create a file.", enabled: false },
  ],
  mcp: [
    {
      name: "playwright",
      state: "READY",
      enabled: true,
      tools: [{ name: "mcp__playwright__browser_navigate", description: "Navigate to a URL", enabled: true }],
    },
  ],
  plugins: [
    { name: "dailyQuota", description: "每个用户每天的 token 用量上限，超过就停止。", points: ["beforeModelCall"], mandatory: true, enabled: true },
    { name: "citationCheck", description: "", points: ["beforeRunEnd", "work:citation"], mandatory: false, enabled: true },
  ],
};

/** 假后端：GET 回现在的列表，PUT 成功时记下新状态（和真实服务一样）；记录每次调用。 */
function fakeBackend(plugins: Plugins, putStatus = 200) {
  const calls: { method: string; path: string; body?: string }[] = [];
  let current = plugins;
  let down = false;
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      calls.push({ method, path: input, body: init?.body as string | undefined });
      if (method === "GET" && down) return Promise.resolve({ ok: false, status: 502, text: () => Promise.resolve("") });
      if (method === "GET") return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(current)) });
      if (putStatus === 502) down = true;
      if (putStatus >= 300) {
        const error = '{"error":"plugin dailyQuota is required and cannot be turned off"}';
        return Promise.resolve({ ok: false, status: putStatus, text: () => Promise.resolve(error) });
      }
      const [, , , kind, name] = input.split("/");
      const enabled = (JSON.parse(init?.body as string) as { enabled: boolean }).enabled;
      current = applyToggle(current, { kind: kind as "tool" | "mcp" | "plugin", name: decodeURIComponent(name!), enabled });
      return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("{}") });
    }),
  );
  return calls;
}

function open() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
      <PluginsDialog open onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe("PluginsDialog", () => {
  it("shows the three sections with Chinese names for built-in tools", async () => {
    fakeBackend(PLUGINS);
    open();
    const builtins = await screen.findByRole("region", { name: "内置工具" });
    expect(within(builtins).getByText("读文件")).toBeTruthy();
    expect(within(builtins).getByRole("switch", { name: "写文件" }).getAttribute("aria-checked")).toBe("false");
    expect(within(screen.getByRole("region", { name: "MCP 服务器" })).getByText("已连接")).toBeTruthy();
    const hooks = screen.getByRole("region", { name: "钩子插件" });
    expect(within(hooks).getByText("挂在：运行结束前、插件工作 citation")).toBeTruthy();
    expect(screen.getByText(/从下一次提问开始可用/)).toBeTruthy();
  });

  it("turns a tool on for the current user", async () => {
    const calls = fakeBackend(PLUGINS);
    open();
    await userEvent.click(await screen.findByRole("switch", { name: "写文件" }));
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true));
    expect(calls.find((call) => call.method === "PUT")).toEqual({ method: "PUT", path: "/api/plugins/tool/write_file", body: '{"enabled":true}' });
    expect(screen.getByRole("switch", { name: "写文件" }).getAttribute("aria-checked")).toBe("true");
  });

  it("puts a switch back and says why when saving fails", async () => {
    fakeBackend(PLUGINS, 400);
    open();
    const write = await screen.findByRole("switch", { name: "写文件" });
    await userEvent.click(write);
    expect((await screen.findByRole("alert")).textContent).toContain("没有保存");
    await waitFor(() => expect(screen.getByRole("switch", { name: "写文件" }).getAttribute("aria-checked")).toBe("false"));
  });

  /** 服务连不上时重新查询也失败，只有回滚能让开关回到原样，不让界面显示一个没保存的状态。 */
  it("puts a switch back even when the list cannot be fetched again", async () => {
    fakeBackend(PLUGINS, 502);
    open();
    await userEvent.click(await screen.findByRole("switch", { name: "写文件" }));
    expect(await screen.findByText(/没有保存/)).toBeTruthy();
    await waitFor(() => expect(screen.getByRole("switch", { name: "写文件" }).getAttribute("aria-checked")).toBe("false"));
  });

  it("does not let mandatory plugins be turned off", async () => {
    const calls = fakeBackend(PLUGINS);
    open();
    const quota = await screen.findByRole("switch", { name: "dailyQuota" });
    expect((quota as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("必须开启")).toBeTruthy();
    await userEvent.click(quota);
    expect(calls.some((call) => call.method === "PUT")).toBe(false);
  });

  it("lists a server's tools on request and grays them out when the server is off", async () => {
    fakeBackend({ ...PLUGINS, mcp: [{ ...PLUGINS.mcp[0]!, enabled: false }] });
    open();
    await userEvent.click(await screen.findByRole("button", { name: /1 个工具/ }));
    const tool = screen.getByRole("switch", { name: "mcp__playwright__browser_navigate" });
    expect((tool as HTMLButtonElement).disabled).toBe(true);
  });

  it("says tools turned on wait for a new session when the model cannot change them mid-session", async () => {
    fakeBackend({ ...PLUGINS, midSessionChanges: false });
    open();
    expect(await screen.findByText(/从新会话开始可用/)).toBeTruthy();
  });


  it("orders built-in tools by use, not by name", async () => {
    fakeBackend({ ...PLUGINS, tools: [{ name: "memory_read", description: "", enabled: true }, { name: "edit_file", description: "", enabled: true },
      { name: "custom_tool", description: "mine", enabled: true }, { name: "read_file", description: "", enabled: true }] });
    open();
    const builtins = await screen.findByRole("region", { name: "内置工具" });
    expect(within(builtins).getAllByRole("switch").map((item) => item.getAttribute("aria-label"))).toEqual(["读文件", "改文件", "读记忆", "custom_tool"]);
  });
});

describe("applyToggle", () => {
  it("changes a tool wherever it is listed and leaves the rest alone", () => {
    const next = applyToggle(PLUGINS, { kind: "tool", name: "mcp__playwright__browser_navigate", enabled: false });
    expect(next.mcp[0]!.tools[0]!.enabled).toBe(false);
    expect(next.mcp[0]!.enabled).toBe(true);
    expect(next.tools).toEqual(PLUGINS.tools);
    expect(applyToggle(PLUGINS, { kind: "mcp", name: "playwright", enabled: false }).mcp[0]!.enabled).toBe(false);
    expect(applyToggle(PLUGINS, { kind: "plugin", name: "citationCheck", enabled: false }).plugins[1]!.enabled).toBe(false);
  });
});
