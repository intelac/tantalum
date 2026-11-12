import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import type { ParamValue, ParamView, PluginDefault, Plugins } from "../api/types";
import { groupDefaults, groupHooks } from "./groups";
import { ParamForm } from "./ParamForm";
import { PluginsDialog } from "./PluginsDialog";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const MAX_CHARS: ParamView = { key: "maxChars", type: "number", label: { en: "Max chars", zh: "最多字数" }, hint: { en: "Longer results are cut", zh: "超过就截断" }, owner: "user", min: 100, max: 10000, value: 500, source: "builtin", editable: true };
const TOOLS: ParamView = { key: "tools", type: "list", label: { en: "Tools", zh: "工具名单" }, hint: "", owner: "user", value: ["grep"], source: "admin", editable: true };
const STRICT: ParamView = { key: "strict", type: "toggle", label: { en: "Strict", zh: "严格模式" }, hint: "", owner: "user", value: false, source: "own", editable: true };
const TOKENS: ParamView = { key: "tokens", type: "number", label: { en: "Tokens", zh: "每天额度" }, hint: "", owner: "admin", min: 1, value: 1000, source: "builtin", editable: false };
const TOKEN: ParamView = { key: "token", type: "secret", label: { en: "Token", zh: "访问令牌" }, hint: "", owner: "user", editable: true, set: true };

// ---- 任务 31：分组 ----

describe("groups", () => {
  it("puts hook plugins into rules and output handling; a plugin without a group is a rule", () => {
    const hook = (name: string, group?: "rule" | "output") => ({ name, description: "", points: [], enabled: true, group });
    const groups = groupHooks([hook("guard", "rule"), hook("trim", "output"), hook("old")]);
    expect(groups.rule.map((plugin) => plugin.name)).toEqual(["guard", "old"]);
    expect(groups.output.map((plugin) => plugin.name)).toEqual(["trim"]);
  });

  it("orders the defaults as rules, tools, output handling and leaves empty groups out", () => {
    const item = (name: string, kind: PluginDefault["kind"], group: PluginDefault["group"]): PluginDefault => ({ kind, name, group, builtin: true, params: [] });
    const groups = groupDefaults([item("grep", "tool", "tool"), item("docs", "mcp", "mcp"), item("guard", "plugin", "rule")]);
    expect(groups.map((entry) => [entry.group, entry.items.map((each) => each.name)])).toEqual([
      ["rule", ["guard"]],
      ["tool", ["grep", "docs"]],
    ]);
  });
});

// ---- 任务 32：参数表单 ----

/** 像页面一样用表单：保存成功后把新值放回 `params`（真实页面是重新查询）。 */
function Harness({ initial, onSave, mode = "own" }: { initial: ParamView[]; onSave: (values: Record<string, ParamValue | null>) => Promise<void>; mode?: "own" | "default" | "assigned" }) {
  const [params, setParams] = useState(initial);
  return (
    <ParamForm
      name="guard"
      params={params}
      mode={mode}
      onSave={async (values) => {
        await onSave(values);
        setParams(params.map((param) => (param.type !== "secret" && values[param.key] != null ? { ...param, value: values[param.key] as ParamValue, source: "own" } : param)));
      }}
      onClearSecret={() => Promise.resolve()}
    />
  );
}

describe("ParamForm", () => {
  it("draws each declared type and marks inherited and administrator-only values", () => {
    render(<Harness initial={[MAX_CHARS, TOOLS, STRICT, TOKENS, TOKEN]} onSave={vi.fn()} />);
    const form = within(screen.getByRole("form", { name: "guard 的参数" }));
    expect((form.getByLabelText("最多字数") as HTMLInputElement).value).toBe("500");
    expect(form.getByText("超过就截断 · 100 到 10,000")).toBeTruthy();
    expect((form.getByLabelText("工具名单") as HTMLTextAreaElement).value).toBe("grep");
    expect(form.getByRole("switch", { name: "严格模式" }).getAttribute("aria-checked")).toBe("false");
    // 自带的和管理员定的都标“默认”；自己填的不标
    expect(form.getAllByText("· 默认")).toHaveLength(2);
    // 管理员的参数：看得到，改不了
    expect((form.getByLabelText("每天额度") as HTMLInputElement).disabled).toBe(true);
    expect(form.getByText("· 管理员设置")).toBeTruthy();
    // 密码不回显，只说填过
    const secret = form.getByLabelText("访问令牌") as HTMLInputElement;
    expect([secret.type, secret.value, secret.autocomplete]).toEqual(["password", "", "off"]);
    expect(form.getByText("已设置")).toBeTruthy();
    expect((form.getByRole("button", { name: "保存 guard" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("saves only what changed, then starts clean with the secret box empty", async () => {
    const saved: Record<string, ParamValue | null>[] = [];
    render(<Harness initial={[MAX_CHARS, TOOLS, STRICT, TOKEN]} onSave={(values) => Promise.resolve(void saved.push(values))} />);
    await userEvent.clear(screen.getByLabelText("最多字数"));
    await userEvent.type(screen.getByLabelText("最多字数"), "200");
    await userEvent.type(screen.getByLabelText("工具名单"), "\n write_file \n");
    await userEvent.type(screen.getByLabelText("访问令牌"), "hunter2");
    expect(screen.getByText("有没保存的改动")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: "保存 guard" }));
    await waitFor(() => expect(saved).toEqual([{ maxChars: 200, tools: ["grep", "write_file"], token: "hunter2" }]));
    await waitFor(() => expect(screen.queryByText("有没保存的改动")).toBeNull());
    expect((screen.getByLabelText("访问令牌") as HTMLInputElement).value).toBe("");
    expect((screen.getByLabelText("最多字数") as HTMLInputElement).value).toBe("200");
  });

  it("writes the server's refusal under the field it is about and keeps what was typed", async () => {
    const refuse = () => Promise.reject(new ApiError(400, "param/invalid", { key: "maxChars", reason: "range" }, "out of range"));
    render(<Harness initial={[MAX_CHARS, TOOLS]} onSave={refuse} />);
    await userEvent.clear(screen.getByLabelText("最多字数"));
    await userEvent.type(screen.getByLabelText("最多字数"), "5");
    await userEvent.click(screen.getByRole("button", { name: "保存 guard" }));
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("超出了允许的范围");
    expect(alert.parentElement?.contains(screen.getByLabelText("最多字数"))).toBe(true);
    expect((screen.getByLabelText("最多字数") as HTMLInputElement).value).toBe("5");
    expect(screen.getByText("有没保存的改动")).toBeTruthy();
  });

  it("does not send a number box that holds no number", async () => {
    const save = vi.fn(() => Promise.resolve());
    render(<Harness initial={[MAX_CHARS]} onSave={save} />);
    await userEvent.clear(screen.getByLabelText("最多字数"));
    await userEvent.click(screen.getByRole("button", { name: "保存 guard" }));
    expect((await screen.findByRole("alert")).textContent).toBe("要填一个数");
    expect(save).not.toHaveBeenCalled();
  });

  it("lets an administrator clear one value back to the default", async () => {
    const saved: Record<string, ParamValue | null>[] = [];
    const mine: ParamView = { ...TOKENS, value: 50, source: "assigned" };
    render(<Harness mode="assigned" initial={[mine, MAX_CHARS]} onSave={(values) => Promise.resolve(void saved.push(values))} />);
    // 管理员的两种表单里每一项都能改
    expect((screen.getByLabelText("每天额度") as HTMLInputElement).disabled).toBe(false);
    await userEvent.click(screen.getByRole("button", { name: "每天额度用默认" }));
    expect(screen.getByText("· 保存后回到默认")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: "保存 guard" }));
    await waitFor(() => expect(saved).toEqual([{ tokens: null }]));
  });
});

// ---- 任务 30、33：锁、我改的、恢复默认、插件默认值 ----

const PLUGINS: Plugins = {
  midSessionChanges: true,
  tools: [{ name: "read_attachment", description: "", enabled: true, byDefault: true, changed: false }],
  mcp: [],
  web: { switches: { web: true, "web-fetch": true }, admin: { "web-fetch": true }, blockPrivateNetwork: false },
  plugins: [
    { name: "guard", description: "", points: ["beforeToolCall"], group: "rule", enabled: true, byDefault: true, changed: false, lock: { source: "admin", reason: "合规要求" }, params: [] },
    { name: "approval", description: "", points: ["beforeToolCall"], group: "rule", enabled: true, byDefault: false, changed: true, params: [MAX_CHARS] },
    { name: "trim", description: "", points: ["afterToolExecution"], group: "output", enabled: false, byDefault: false, changed: false, params: [MAX_CHARS] },
  ],
};

const DEFAULTS: { items: PluginDefault[] } = {
  items: [
    { kind: "plugin", name: "guard", group: "rule", builtin: false, byAdmin: true, lock: { source: "admin", reason: "合规要求" }, params: [{ ...MAX_CHARS, editable: true }] },
    { kind: "plugin", name: "quota", group: "rule", builtin: true, lock: { source: "config", reason: "" }, params: [TOKENS] },
    { kind: "tool", name: "read_attachment", group: "tool", builtin: true, params: [] },
  ],
};

type Call = { method: string; path: string; body?: string };

/** 假后端：`answer` 先看，返回 undefined 的走默认（列表、默认值、本机工作区）。 */
function fakeBackend(answer: (call: Call) => { status: number; body: string } | undefined = () => undefined) {
  const calls: Call[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string, init?: RequestInit) => {
      const call = { method: init?.method ?? "GET", path: input, body: init?.body as string | undefined };
      const local = { switches: { "local-workspace": true, "fs-read": true, git: true, "file-edit": true, shell: true }, link: null };
      const fixed = input === "/api/local" ? local : input === "/api/admin/plugins" ? DEFAULTS : input === "/api/plugins" ? PLUGINS : {};
      if (input !== "/api/local") calls.push(call);
      const { status, body } = answer(call) ?? { status: 200, body: JSON.stringify(fixed) };
      return Promise.resolve({ ok: status < 300, status, text: () => Promise.resolve(body) });
    }),
  );
  return calls;
}

function open(admin: boolean) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
      <PluginsDialog open onClose={vi.fn()} admin={admin} />
    </QueryClientProvider>,
  );
}

describe("PluginsDialog with settings", () => {
  it("shows the three groups, a lock with its reason, and the form only under a plugin that is on", async () => {
    fakeBackend();
    open(false);
    const rules = within(await screen.findByRole("group", { name: "规则" }));
    expect(screen.getByRole("group", { name: "工具" })).toBeTruthy();
    expect(within(screen.getByRole("group", { name: "工具输出处理" })).getByRole("switch", { name: "trim" })).toBeTruthy();
    expect((rules.getByRole("switch", { name: "guard" }) as HTMLButtonElement).disabled).toBe(true);
    expect(rules.getByText("锁定：合规要求")).toBeTruthy();
    // 普通用户没有页签，也没有去默认值的按钮
    expect(screen.queryByRole("tab")).toBeNull();
    expect(screen.queryByRole("button", { name: "去插件默认值改" })).toBeNull();
    expect(screen.getByRole("form", { name: "approval 的参数" })).toBeTruthy();
    expect(screen.queryByRole("form", { name: "trim 的参数" })).toBeNull();
  });

  it("marks a switch I changed and puts it back to the default on request", async () => {
    let reset = false;
    const calls = fakeBackend((call) => {
      if (call.method === "DELETE") reset = true;
      if (call.method === "GET" && call.path === "/api/plugins" && reset) {
        const plugins = PLUGINS.plugins.map((plugin) => (plugin.name === "approval" ? { ...plugin, enabled: false, changed: false } : plugin));
        return { status: 200, body: JSON.stringify({ ...PLUGINS, plugins }) };
      }
      return undefined;
    });
    open(false);
    expect(await screen.findByText("我改的")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: "approval 恢复默认" }));
    await waitFor(() => expect(screen.queryByText("我改的")).toBeNull());
    expect(calls.find((call) => call.method === "DELETE")?.path).toBe("/api/plugins/plugin/approval");
    expect(screen.getByRole("switch", { name: "approval" }).getAttribute("aria-checked")).toBe("false");
  });

  it("puts the switch back and says who locked it when the server refuses", async () => {
    const refusal = { error: "locked", code: "plugin/locked", params: { name: "trim", source: "admin", reason: "先别开" } };
    fakeBackend((call) => (call.method === "PUT" ? { status: 403, body: JSON.stringify(refusal) } : undefined));
    open(false);
    await userEvent.click(await screen.findByRole("switch", { name: "trim" }));
    expect((await screen.findByRole("alert")).textContent).toContain("trim 被管理员锁定了，不能改：先别开");
    await waitFor(() => expect(screen.getByRole("switch", { name: "trim" }).getAttribute("aria-checked")).toBe("false"));
  });

  it("saves my own settings to the plugin's own address", async () => {
    const calls = fakeBackend();
    open(false);
    const form = within(await screen.findByRole("form", { name: "approval 的参数" }));
    await userEvent.clear(form.getByLabelText("最多字数"));
    await userEvent.type(form.getByLabelText("最多字数"), "300");
    await userEvent.click(form.getByRole("button", { name: "保存 approval" }));
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true));
    expect(calls.find((call) => call.method === "PUT")).toEqual({ method: "PUT", path: "/api/plugins/plugin/approval/params", body: '{"values":{"maxChars":300}}' });
  });

  it("takes an administrator from a locked row to that plugin's defaults", async () => {
    fakeBackend();
    open(true);
    await userEvent.click(await screen.findByRole("button", { name: "去插件默认值改" }));
    expect(screen.getByRole("tab", { name: "插件默认值" }).getAttribute("aria-selected")).toBe("true");
    const guard = await screen.findByRole("form", { name: "guard" });
    expect(guard.parentElement?.className).toContain("border-fg");
    expect((within(guard).getByLabelText("锁定的原因") as HTMLInputElement).value).toBe("合规要求");
  });

  it("saves a default, a lock with its reason and a default setting in one request", async () => {
    const calls = fakeBackend();
    open(true);
    await userEvent.click(await screen.findByRole("tab", { name: "插件默认值" }));
    const tool = within(await screen.findByRole("form", { name: "读附件" }));
    expect(tool.getByText("自带的默认：开")).toBeTruthy();
    await userEvent.click(tool.getByRole("switch", { name: "读附件 默认开启" }));
    await userEvent.click(tool.getByRole("switch", { name: "锁定 读附件" }));
    await userEvent.type(tool.getByLabelText("锁定的原因"), "先停用");
    await userEvent.click(tool.getByRole("button", { name: "保存 读附件" }));
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true));
    const put = calls.find((call) => call.method === "PUT");
    expect(put?.path).toBe("/api/admin/plugins/tool/read_attachment");
    expect(JSON.parse(put?.body ?? "{}")).toEqual({ enabled: false, locked: true, reason: "先停用" });
    // 存完两份都重新查：我的插件里的默认和锁跟着变
    await waitFor(() => expect(calls.filter((call) => call.method === "GET" && call.path === "/api/plugins").length).toBeGreaterThan(1));
  });

  it("clears the administrator's default back to the built-in one", async () => {
    const calls = fakeBackend();
    open(true);
    await userEvent.click(await screen.findByRole("tab", { name: "插件默认值" }));
    const guard = within(await screen.findByRole("form", { name: "guard" }));
    expect(guard.getByRole("switch", { name: "guard 默认开启" }).getAttribute("aria-checked")).toBe("true");
    await userEvent.click(guard.getByRole("button", { name: "用自带的默认" }));
    expect(guard.getByRole("switch", { name: "guard 默认开启" }).getAttribute("aria-checked")).toBe("false");
    await userEvent.click(guard.getByRole("button", { name: "保存 guard" }));
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true));
    expect(JSON.parse(calls.find((call) => call.method === "PUT")?.body ?? "{}")).toEqual({ enabled: null });
  });

  it("keeps the switches of a plugin locked in configuration read-only but saves its settings", async () => {
    const calls = fakeBackend();
    open(true);
    await userEvent.click(await screen.findByRole("tab", { name: "插件默认值" }));
    const quota = within(await screen.findByRole("form", { name: "quota" }));
    expect(quota.getByText(/tantalum\.hooks\.required/)).toBeTruthy();
    expect((quota.getByRole("switch", { name: "quota 默认开启" }) as HTMLButtonElement).disabled).toBe(true);
    expect((quota.getByRole("switch", { name: "锁定 quota" }) as HTMLButtonElement).disabled).toBe(true);
    await userEvent.clear(quota.getByLabelText("每天额度"));
    await userEvent.type(quota.getByLabelText("每天额度"), "800");
    await userEvent.click(quota.getByRole("button", { name: "保存 quota" }));
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true));
    expect(JSON.parse(calls.find((call) => call.method === "PUT")?.body ?? "{}")).toEqual({ params: { tokens: 800 } });
  });
});
