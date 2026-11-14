import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import pluginsJson from "../../../tantalum-shell/src/test/resources/wire/plugins.json?raw";
import type { Plugins } from "../api/types";
import { currentText, lookup } from "../text";
import { applyToggle } from "./usePlugins";
import { PluginsDialog } from "./PluginsDialog";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const PLUGINS: Plugins = {
  midSessionChanges: true,
  // 文件工具移到了本机工作区的卡片（local-workspace），内置工具只剩服务器自己的
  tools: [
    { name: "read_attachment", description: "Read a file the user attached.", enabled: true },
    { name: "memory_write", description: "Save a long-term memory.", enabled: false },
  ],
  web: { switches: { web: true, "web-fetch": true }, admin: { "web-fetch": true }, blockPrivateNetwork: false },
  plugins: [
    { name: "dailyQuota", description: "每个用户每天的 token 用量上限，超过就停止。", points: ["beforeModelCall"], lock: { source: "config", reason: "" }, enabled: true },
    { name: "citationCheck", description: "", points: ["beforeRunEnd", "work:citation"], enabled: true },
  ],
};

/**
 * 假后端：GET 回现在的列表，PUT 成功时记下新状态（和真实服务一样）；记录每次调用。
 * 本机工作区的卡片另取 /api/local，这里总是“没连上电脑”，它的用例在 LocalWorkspaceCard.test.tsx。
 */
function fakeBackend(plugins: Plugins, putStatus = 200) {
  const calls: { method: string; path: string; body?: string }[] = [];
  let current = plugins;
  let down = false;
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (input === "/api/local") {
        const local = { switches: { "local-workspace": true, "fs-read": true, git: true, "file-edit": true, shell: true }, link: null };
        return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(local)) });
      }
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
      current = applyToggle(current, { kind: kind as "tool" | "plugin" | "web", name: decodeURIComponent(name!), enabled });
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
    expect(within(builtins).getByText("读附件")).toBeTruthy();
    expect(within(builtins).getByRole("switch", { name: "写记忆" }).getAttribute("aria-checked")).toBe("false");
    expect(screen.getByRole("region", { name: "本机工作区" })).toBeTruthy();
    expect(screen.queryByRole("region", { name: "MCP 服务器" })).toBeNull();
    const hooks = screen.getByRole("region", { name: "规则" });
    expect(within(hooks).getByText("挂在：运行结束前、插件工作 citation")).toBeTruthy();
    expect(screen.getByText(/只影响你自己，从你的下一次提问开始生效/)).toBeTruthy();
  });

  it("moves everything that is locked into a group of its own", async () => {
    fakeBackend({
      ...PLUGINS,
      tools: [...PLUGINS.tools, { name: "ask_user", description: "Ask the user.", enabled: true, lock: { source: "admin", reason: "合规要求" } }],
      plugins: [...PLUGINS.plugins, { name: "redact", description: "", points: ["afterTool"], group: "output", enabled: true, lock: { source: "code", reason: "" } }],
    });
    open();
    const locked = await screen.findByRole("group", { name: "只有管理员能改" });
    // 规则插件、内置工具、输出插件，按这个顺序
    expect(within(locked).getAllByRole("switch").map((item) => item.getAttribute("aria-label"))).toEqual(["dailyQuota", "问用户", "redact"]);
    expect(within(locked).getAllByRole("switch").every((item) => (item as HTMLButtonElement).disabled)).toBe(true);
    expect(within(locked).getByText(/合规要求/)).toBeTruthy();
    // 原来的组里不再有它们
    expect(within(screen.getByRole("group", { name: "规则" })).queryByRole("switch", { name: "dailyQuota" })).toBeNull();
    expect(within(screen.getByRole("group", { name: "规则" })).getByRole("switch", { name: "citationCheck" })).toBeTruthy();
    const tools = screen.getByRole("group", { name: "工具" });
    expect(within(tools).queryByRole("switch", { name: "问用户" })).toBeNull();
    expect(within(tools).getByRole("switch", { name: "读附件" })).toBeTruthy();
    // 输出插件只有锁住的那一个：“工具输出处理”这一组空了，不显示
    expect(screen.queryByRole("group", { name: "工具输出处理" })).toBeNull();
    // 四组的顺序
    expect(screen.getAllByRole("group").map((group) => group.getAttribute("aria-label")).filter((name) => ["规则", "工具", "工具输出处理", "只有管理员能改"].includes(name ?? ""))).toEqual(["规则", "工具", "只有管理员能改"]);
  });

  it("shows no locked group when nothing is locked", async () => {
    fakeBackend({ ...PLUGINS, plugins: PLUGINS.plugins.filter((plugin) => plugin.lock === undefined) });
    open();
    await screen.findByRole("region", { name: "内置工具" });
    expect(screen.queryByRole("group", { name: "只有管理员能改" })).toBeNull();
  });

  it("turns a tool on for the current user", async () => {
    const calls = fakeBackend(PLUGINS);
    open();
    await userEvent.click(await screen.findByRole("switch", { name: "写记忆" }));
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true));
    expect(calls.find((call) => call.method === "PUT")).toEqual({ method: "PUT", path: "/api/plugins/tool/memory_write", body: '{"enabled":true}' });
    expect(screen.getByRole("switch", { name: "写记忆" }).getAttribute("aria-checked")).toBe("true");
  });

  /** web-tools 5.1：联网一块在本机工作区和内置工具之间，开关写到 /api/plugins/web/<名字>。 */
  it("shows the web card between the local workspace and the built-in tools and saves its switches", async () => {
    const calls = fakeBackend(PLUGINS);
    open();
    const web = await screen.findByRole("region", { name: "联网" });
    const order = screen.getAllByRole("region").map((region) => region.getAttribute("aria-label"));
    expect(order.indexOf("联网")).toBe(order.indexOf("内置工具") - 1);
    expect(order.indexOf("本机工作区")).toBeLessThan(order.indexOf("联网"));
    await userEvent.click(within(web).getByRole("switch", { name: "抓网页" }));
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true));
    expect(calls.find((call) => call.method === "PUT")).toEqual({ method: "PUT", path: "/api/plugins/web/web-fetch", body: '{"enabled":false}' });
    expect(within(web).getByRole("switch", { name: "抓网页" }).getAttribute("aria-checked")).toBe("false");
  });

  it("puts a switch back and says why when saving fails", async () => {
    fakeBackend(PLUGINS, 400);
    open();
    const write = await screen.findByRole("switch", { name: "写记忆" });
    await userEvent.click(write);
    expect((await screen.findByRole("alert")).textContent).toContain("没有保存");
    await waitFor(() => expect(screen.getByRole("switch", { name: "写记忆" }).getAttribute("aria-checked")).toBe("false"));
  });

  /** 服务连不上时重新查询也失败，只有回滚能让开关回到原样，不让界面显示一个没保存的状态。 */
  it("puts a switch back even when the list cannot be fetched again", async () => {
    fakeBackend(PLUGINS, 502);
    open();
    await userEvent.click(await screen.findByRole("switch", { name: "写记忆" }));
    expect(await screen.findByText(/没有保存/)).toBeTruthy();
    await waitFor(() => expect(screen.getByRole("switch", { name: "写记忆" }).getAttribute("aria-checked")).toBe("false"));
  });

  it("does not let mandatory plugins be turned off", async () => {
    const calls = fakeBackend(PLUGINS);
    open();
    const quota = await screen.findByRole("switch", { name: "dailyQuota" });
    expect((quota as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("锁定：服务器配置里设为必须开启")).toBeTruthy();
    await userEvent.click(quota);
    expect(calls.some((call) => call.method === "PUT")).toBe(false);
  });

  it("says tools turned on wait for a new session when the model cannot change them mid-session", async () => {
    fakeBackend({ ...PLUGINS, midSessionChanges: false });
    open();
    expect(await screen.findByText(/从新会话开始可用/)).toBeTruthy();
  });


  it("orders built-in tools by use, not by name", async () => {
    fakeBackend({ ...PLUGINS, tools: [{ name: "memory_read", description: "", enabled: true }, { name: "memory_delete", description: "", enabled: true },
      { name: "custom_tool", description: "mine", enabled: true }, { name: "read_attachment", description: "", enabled: true }] });
    open();
    const builtins = await screen.findByRole("region", { name: "内置工具" });
    expect(within(builtins).getAllByRole("switch").map((item) => item.getAttribute("aria-label"))).toEqual(["读附件", "读记忆", "删记忆", "custom_tool"]);
  });
});

// console-i18n 任务 4：用服务器真实的 /api/plugins（PluginApiTest 写出的 wire/plugins.json）渲染，每个内置工具都是字典里的名字和说明，
// 给模型看的英文说明不出现。
describe("built-in tools from the real server", () => {
  const real = JSON.parse(pluginsJson) as Plugins;

  it("every built-in tool has a dictionary entry", () => {
    expect(real.tools.length).toBeGreaterThan(0);
    for (const tool of real.tools) expect(lookup(currentText().plugins.tools, tool.name), tool.name).toBeDefined();
  });

  it("shows dictionary names and details, never the model-facing description", async () => {
    fakeBackend(real);
    open();
    const builtins = await screen.findByRole("region", { name: "内置工具" });
    for (const tool of real.tools) {
      const [label, detail] = lookup(currentText().plugins.tools, tool.name)!;
      expect(within(builtins).getByRole("switch", { name: label })).toBeTruthy();
      expect(within(builtins).getByText(detail)).toBeTruthy();
      expect(document.body.textContent).not.toContain(tool.description);
    }
  });
});

describe("applyToggle", () => {
  it("changes only the switch it names and leaves the rest alone", () => {
    const next = applyToggle(PLUGINS, { kind: "tool", name: "read_attachment", enabled: false });
    expect(next.tools[0]!.enabled).toBe(false);
    expect(next.tools[1]).toEqual(PLUGINS.tools[1]);
    expect(next.plugins).toEqual(PLUGINS.plugins);
    expect(applyToggle(PLUGINS, { kind: "web", name: "web-fetch", enabled: false }).web.switches["web-fetch"]).toBe(false);
    expect(applyToggle(PLUGINS, { kind: "plugin", name: "citationCheck", enabled: false }).plugins[1]!.enabled).toBe(false);
  });
});
