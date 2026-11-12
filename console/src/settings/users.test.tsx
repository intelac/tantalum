import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SettingsDialog } from "./SettingsDialog";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const config = { provider: "claude", model: "claude-sonnet-5-5", cloud: true, contextWindow: 200000 };

interface Route {
  method?: string;
  path: string;
  status: number;
  body: string;
}

/** 按方法和路径回应的假后端；记录每次调用。 */
function fakeBackend(routes: Route[]) {
  const calls: { method: string; path: string; body?: string }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      calls.push({ method, path: input, body: init?.body as string | undefined });
      const route = routes.find((candidate) => (candidate.method ?? "GET") === method && candidate.path === input);
      const status = route?.status ?? 404;
      const body = route?.body ?? '{"error":"not found"}';
      return Promise.resolve({ ok: status >= 200 && status < 300, status, text: () => Promise.resolve(body) });
    }),
  );
  return calls;
}

const USERS = {
  users: [
    { name: "yi", admin: true, disabled: false, createdAt: new Date().toISOString() },
    { name: "alice", admin: false, disabled: false, createdAt: new Date(Date.now() - 86_400_000).toISOString() },
  ],
};

function open(routes: Route[], admin: boolean) {
  const calls = fakeBackend(routes);
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
      <SettingsDialog open onClose={vi.fn()} config={config} username="yi" admin={admin} onLogout={vi.fn()} />
    </QueryClientProvider>,
  );
  return calls;
}

describe("UsersPane", () => {
  it("is absent for a non-administrator", () => {
    open([], false);
    expect(screen.queryByRole("button", { name: "运维" })).toBeNull();
    expect(screen.getByRole("button", { name: "账号" })).not.toBeNull();
  });

  it("lists accounts and keeps an administrator from disabling or demoting themselves", async () => {
    open([{ path: "/api/users", status: 200, body: JSON.stringify(USERS) }], true);
    await userEvent.click(screen.getByRole("button", { name: "运维" }));
    await screen.findByText("alice");
    const mine = within(screen.getByRole("group", { name: "yi 的操作" }));
    expect((mine.getByRole("button", { name: "停用" }) as HTMLButtonElement).disabled).toBe(true);
    expect((mine.getByRole("button", { name: "取消管理员" }) as HTMLButtonElement).disabled).toBe(true);
    const hers = within(screen.getByRole("group", { name: "alice 的操作" }));
    expect((hers.getByRole("button", { name: "停用" }) as HTMLButtonElement).disabled).toBe(false);
    expect((hers.getByRole("button", { name: "设为管理员" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("creates a user and says so", async () => {
    const calls = open(
      [
        { path: "/api/users", status: 200, body: JSON.stringify(USERS) },
        { method: "POST", path: "/api/users", status: 200, body: JSON.stringify({ name: "bob", admin: false, disabled: false, createdAt: new Date().toISOString() }) },
      ],
      true,
    );
    await userEvent.click(screen.getByRole("button", { name: "运维" }));
    await screen.findByText("alice");
    await userEvent.click(screen.getByRole("button", { name: "新建用户" }));
    await userEvent.type(screen.getByLabelText("名字"), "bob");
    await userEvent.type(screen.getByLabelText("初始密码"), "bob-password");
    await userEvent.click(screen.getByRole("button", { name: "创建" }));
    await screen.findByText("已创建 bob。");
    const post = calls.find((call) => call.method === "POST");
    expect(JSON.parse(post?.body ?? "{}")).toEqual({ name: "bob", password: "bob-password", admin: false });
  });

  it("translates the last-administrator refusal", async () => {
    open(
      [
        { path: "/api/users", status: 200, body: JSON.stringify(USERS) },
        { method: "PUT", path: "/api/users/alice", status: 409, body: '{"error":"last admin","code":"user/last-admin"}' },
      ],
      true,
    );
    await userEvent.click(screen.getByRole("button", { name: "运维" }));
    await screen.findByText("alice");
    await userEvent.click(within(screen.getByRole("group", { name: "alice 的操作" })).getByRole("button", { name: "停用" }));
    await screen.findByText("这是最后一个管理员，不能停用或取消");
  });
});

// plugin-settings 任务 34：今天用量，按人指定管理员的参数。
describe("OpsPane usage and assigned settings", () => {
  const usage = { input: 1200, output: 300, cacheRead: 5000, cacheWrite: 0, total: 1500 };
  const users = { users: [{ ...USERS.users[0]!, usageToday: usage }, USERS.users[1]!] };
  const tokens = { key: "tokens", type: "number", label: { en: "Tokens", zh: "每天额度" }, hint: "", owner: "admin", min: 1, value: 1000, source: "builtin", editable: false };
  const defaults = { items: [{ kind: "plugin", name: "quota", group: "rule", builtin: true, params: [tokens] }] };
  const assigned = { user: "alice", items: [{ kind: "plugin", name: "quota", params: [tokens] }] };

  it("shows today's usage with its breakdown, and no settings button when nothing is administrator-only", async () => {
    open([{ path: "/api/users", status: 200, body: JSON.stringify(users) }], true);
    await userEvent.click(screen.getByRole("button", { name: "运维" }));
    const total = await screen.findByText("1,500");
    expect(total.getAttribute("title")).toBe("读入 1,200，输出 300，读缓存 5,000，写缓存 0（按 UTC 的一天算）");
    expect(screen.queryByRole("button", { name: "参数" })).toBeNull();
  });

  it("assigns an administrator-only setting to one person", async () => {
    const calls = open(
      [
        { path: "/api/users", status: 200, body: JSON.stringify(users) },
        { path: "/api/admin/plugins", status: 200, body: JSON.stringify(defaults) },
        { path: "/api/admin/users/alice/params", status: 200, body: JSON.stringify(assigned) },
        { method: "PUT", path: "/api/admin/users/alice/params/plugin/quota", status: 200, body: JSON.stringify(assigned) },
      ],
      true,
    );
    await userEvent.click(screen.getByRole("button", { name: "运维" }));
    await screen.findByText("alice");
    await userEvent.click(await within(screen.getByRole("group", { name: "alice 的操作" })).findByRole("button", { name: "参数" }));
    const form = within(await screen.findByRole("form", { name: "quota 的参数" }));
    expect(form.getByText("· 默认")).toBeTruthy();
    await userEvent.clear(form.getByLabelText("每天额度"));
    await userEvent.type(form.getByLabelText("每天额度"), "50");
    await userEvent.click(form.getByRole("button", { name: "保存 quota" }));
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true));
    expect(calls.find((call) => call.method === "PUT")?.body).toBe('{"values":{"tokens":50}}');
  });
});

// qagent-align 需求 3.8、4、5：运维表格。
describe("OpsPane table", () => {
  const day = new Date(Date.now() - 3 * 86_400_000).toISOString();
  const usage = (total: number) => ({ input: total, output: 0, cacheRead: 0, cacheWrite: 0, total });
  const source = { plugin: "dailyQuota", key: "tokens", value: 1000, min: 1 };
  const LIST = {
    users: [
      { name: "yi", admin: true, disabled: false, createdAt: day, usageToday: usage(120), sessions: 12, running: 1, limit: { tokens: 1000, own: false } },
      { name: "alice", admin: false, disabled: false, createdAt: day, usageToday: usage(850), sessions: 3, running: 0, limit: { tokens: 1000, own: false } },
      { name: "bob", admin: false, disabled: true, createdAt: day, usageToday: usage(700), sessions: 0, running: 0, limit: { tokens: 500, own: true } },
      { name: "carol", admin: false, disabled: false, createdAt: day, usageToday: usage(40), sessions: 1, running: 0, limit: { off: true } },
    ],
    dailyLimit: source,
    weighted: false,
    weights: [],
  };

  async function openOps(routes: Route[]) {
    const calls = open(routes, true);
    await userEvent.click(screen.getByRole("button", { name: "运维" }));
    await screen.findByText("alice");
    return calls;
  }

  function rowOf(name: string) {
    return within(screen.getByText(name, { selector: "span.font-mono" }).closest("tr")!);
  }

  it("has a column for usage, limit, sessions and running, and keeps the role and state with the name", async () => {
    await openOps([{ path: "/api/users", status: 200, body: JSON.stringify(LIST) }]);
    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["用户", "今日用量", "每日上限", "会话", "进行中", "操作"]);
    const yi = rowOf("yi");
    expect(yi.getByText("管理员")).toBeTruthy();
    expect(yi.getAllByRole("cell").map((cell) => cell.textContent)[3]).toBe("12");
    expect(yi.getAllByRole("cell").map((cell) => cell.textContent)[4]).toBe("1");
    expect(rowOf("bob").getByText("已停用")).toBeTruthy();
    expect(rowOf("alice").queryByText("已停用")).toBeNull();
    // 创建时间挪到了名字的提示里
    expect(rowOf("alice").getAllByRole("cell")[0]!.getAttribute("title")).toMatch(/^创建于 /);
    // 原来的操作都还在
    const actions = within(screen.getByRole("group", { name: "alice 的操作" }));
    expect(actions.getByRole("button", { name: "停用" })).toBeTruthy();
    expect(actions.getByRole("button", { name: "设为管理员" })).toBeTruthy();
    expect(actions.getByRole("button", { name: "重置密码" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "新建用户" })).toBeTruthy();
    expect(screen.queryByText("折算系数")).toBeNull();
  });

  it("shows how far each person is from the limit: plain under 80%, warning from 80%, danger at 100%", async () => {
    await openOps([{ path: "/api/users", status: 200, body: JSON.stringify(LIST) }]);
    const bar = (name: string) => rowOf(name).getByRole("progressbar");
    expect(rowOf("yi").getByText("12%")).toBeTruthy();
    expect(bar("yi").getAttribute("data-level")).toBe("ok");
    expect(rowOf("alice").getByText("85%")).toBeTruthy();
    expect(bar("alice").getAttribute("data-level")).toBe("warn");
    expect(rowOf("bob").getByText("140%")).toBeTruthy();
    expect(bar("bob").getAttribute("data-level")).toBe("over");
    expect(bar("bob").getAttribute("aria-valuenow")).toBe("100");
    // 上限很小时百分比会大得没意义：到 999% 为止
    // 四项原始数字在提示里
    expect(bar("alice").parentElement!.getAttribute("title")).toBe("读入 850，输出 0，读缓存 0，写缓存 0（按 UTC 的一天算）");
    // 没有上限的人：只有数字，没有百分比和进度条
    expect(rowOf("carol").queryByRole("progressbar")).toBeNull();
    expect(rowOf("carol").getByText("没有上限").getAttribute("title")).toMatch(/没有打开每日额度的插件/);
  });

  it("stops counting the percent at 999", async () => {
    const tiny = { ...LIST, users: [{ ...LIST.users[1]!, usageToday: usage(64_349), limit: { tokens: 1, own: true } }] };
    await openOps([{ path: "/api/users", status: 200, body: JSON.stringify(tiny) }]);
    expect(rowOf("alice").getByText("超过 999%")).toBeTruthy();
    expect(rowOf("alice").getByText("64,349")).toBeTruthy();
  });

  it("marks a limit as default or set for the person, and sets one in place with Enter", async () => {
    let list = LIST;
    const calls = fakeBackendWith(() => list);
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
        <SettingsDialog open onClose={vi.fn()} config={config} username="yi" admin onLogout={vi.fn()} />
      </QueryClientProvider>,
    );
    await userEvent.click(screen.getByRole("button", { name: "运维" }));
    await screen.findByText("alice");
    expect(rowOf("alice").getByText("默认")).toBeTruthy();
    expect(rowOf("bob").getByText("单独设置", { selector: "span" })).toBeTruthy();
    expect(rowOf("alice").queryByRole("button", { name: "把 alice 的每日上限恢复默认" })).toBeNull();

    await userEvent.click(rowOf("alice").getByRole("button", { name: "给 alice 单独设置每日上限" }));
    const field = rowOf("alice").getByRole("textbox", { name: "alice 的每日上限" }) as HTMLInputElement;
    expect(field.value).toBe("1000");
    // 不合规矩：就地说明，不发请求，框还在
    await userEvent.clear(field);
    await userEvent.type(field, "0{Enter}");
    expect(rowOf("alice").getByRole("alert").textContent).toBe("不能小于 1");
    expect(calls.some((call) => call.method === "PUT")).toBe(false);
    await userEvent.clear(field);
    await userEvent.type(field, "abc{Enter}");
    expect(rowOf("alice").getByRole("alert").textContent).toBe("要填一个整数");

    list = { ...LIST, users: LIST.users.map((user) => (user.name === "alice" ? { ...user, limit: { tokens: 300, own: true } } : user)) };
    await userEvent.clear(field);
    await userEvent.type(field, "300{Enter}");
    await waitFor(() => expect(calls.find((call) => call.method === "PUT")?.path).toBe("/api/admin/users/alice/params/plugin/dailyQuota"));
    expect(calls.find((call) => call.method === "PUT")?.body).toBe('{"values":{"tokens":300}}');
    await waitFor(() => expect(rowOf("alice").queryByRole("textbox")).toBeNull());
    expect(await rowOf("alice").findByText("300")).toBeTruthy();
    expect(rowOf("alice").getByText("单独设置", { selector: "span" })).toBeTruthy();
  });

  it("cancels with Escape without closing the dialog, and restores the default on request", async () => {
    let list = LIST;
    const calls = fakeBackendWith(() => list);
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
        <SettingsDialog open onClose={vi.fn()} config={config} username="yi" admin onLogout={vi.fn()} />
      </QueryClientProvider>,
    );
    await userEvent.click(screen.getByRole("button", { name: "运维" }));
    await screen.findByText("bob");
    await userEvent.click(rowOf("bob").getByRole("button", { name: "修改 bob 的每日上限" }));
    await userEvent.type(rowOf("bob").getByRole("textbox", { name: "bob 的每日上限" }), "9{Escape}");
    expect(rowOf("bob").queryByRole("textbox")).toBeNull();
    expect(screen.getByText("alice")).toBeTruthy();
    expect(calls.some((call) => call.method === "PUT")).toBe(false);

    list = { ...LIST, users: LIST.users.map((user) => (user.name === "bob" ? { ...user, limit: { tokens: 1000, own: false } } : user)) };
    await userEvent.click(rowOf("bob").getByRole("button", { name: "把 bob 的每日上限恢复默认" }));
    await waitFor(() => expect(calls.find((call) => call.method === "PUT")?.body).toBe('{"values":{"tokens":null}}'));
    await waitFor(() => expect(rowOf("bob").getByText("默认")).toBeTruthy());
  });

  it("changes the default limit above the table, which is the plugin default", async () => {
    let list = LIST;
    const calls = fakeBackendWith(() => list);
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
        <SettingsDialog open onClose={vi.fn()} config={config} username="yi" admin onLogout={vi.fn()} />
      </QueryClientProvider>,
    );
    await userEvent.click(screen.getByRole("button", { name: "运维" }));
    await screen.findByText("alice");
    expect(screen.getByText("默认每日上限")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: "修改默认每日上限" }));
    const field = screen.getByRole("textbox", { name: "默认每日上限" });
    list = { ...LIST, dailyLimit: { ...source, value: 7000 } };
    await userEvent.clear(field);
    await userEvent.type(field, "7000{Enter}");
    await waitFor(() => expect(calls.find((call) => call.method === "PUT")?.path).toBe("/api/admin/plugins/plugin/dailyQuota"));
    expect(calls.find((call) => call.method === "PUT")?.body).toBe('{"params":{"tokens":7000}}');
    expect(await screen.findByText("7,000")).toBeTruthy();
  });

  it("shows a refusal from the server under the field and keeps it open", async () => {
    const calls = open(
      [
        { path: "/api/users", status: 200, body: JSON.stringify(LIST) },
        { method: "PUT", path: "/api/admin/users/alice/params/plugin/dailyQuota", status: 400, body: '{"error":"bad","code":"param/invalid","params":{"key":"tokens","reason":"range"}}' },
      ],
      true,
    );
    await userEvent.click(screen.getByRole("button", { name: "运维" }));
    await screen.findByText("alice");
    await userEvent.click(rowOf("alice").getByRole("button", { name: "给 alice 单独设置每日上限" }));
    await userEvent.type(rowOf("alice").getByRole("textbox", { name: "alice 的每日上限" }), "{Enter}");
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true));
    expect(await rowOf("alice").findByRole("alert")).toBeTruthy();
    expect(rowOf("alice").getByRole("textbox", { name: "alice 的每日上限" })).toBeTruthy();
  });

  it("has no limit column and no default line when no plugin reports a limit", async () => {
    const plain = { users: LIST.users.map(({ limit: _limit, ...user }) => user) };
    await openOps([{ path: "/api/users", status: 200, body: JSON.stringify(plain) }]);
    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["用户", "今日用量", "会话", "进行中", "操作"]);
    expect(screen.queryByText("默认每日上限")).toBeNull();
    expect(screen.queryByRole("progressbar")).toBeNull();
  });

  it("shows the limit but no way to change it when the plugin does not say which setting it is", async () => {
    const { dailyLimit: _source, ...list } = LIST;
    await openOps([{ path: "/api/users", status: 200, body: JSON.stringify(list) }]);
    expect(rowOf("alice").getByText("1,000")).toBeTruthy();
    expect(rowOf("alice").queryByRole("button", { name: /每日上限/ })).toBeNull();
  });

  it("says weighted and lists the weights only when the server weighs tokens", async () => {
    const weights = [
      { route: "claude", input: 1, output: 5, cacheRead: 0.1, cacheWrite: 1.25 },
      { route: "claude", model: "claude-opus-5-5", input: 5, output: 25, cacheRead: 0.5, cacheWrite: 6.25 },
    ];
    await openOps([{ path: "/api/users", status: 200, body: JSON.stringify({ ...LIST, weighted: true, weights }) }]);
    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toContain("今日用量（折算）");
    const table = within(screen.getByRole("region", { name: "折算系数" }));
    expect(table.getByText("整条路线")).toBeTruthy();
    expect(table.getByText("claude-opus-5-5")).toBeTruthy();
    expect(table.getByText("6.25")).toBeTruthy();
  });

  it("refreshes while it is open and stops when the dialog goes away", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const calls = await openOps([{ path: "/api/users", status: 200, body: JSON.stringify(LIST) }]);
    const count = () => calls.filter((call) => call.method === "GET" && call.path === "/api/users").length;
    const before = count();
    await vi.advanceTimersByTimeAsync(10_500);
    expect(count()).toBe(before + 1);
    cleanup();
    await vi.advanceTimersByTimeAsync(21_000);
    expect(count()).toBe(before + 1);
    vi.useRealTimers();
  });
});

/** 假后端：GET /api/users 回 `list()` 现在的值，别的 GET 404，写操作都成功；记下每次调用。 */
function fakeBackendWith(list: () => unknown) {
  const calls: { method: string; path: string; body?: string }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      calls.push({ method, path: input, body: init?.body as string | undefined });
      const found = method !== "GET" || input === "/api/users";
      const body = method === "GET" && input === "/api/users" ? JSON.stringify(list()) : "{}";
      return Promise.resolve({ ok: found, status: found ? 200 : 404, text: () => Promise.resolve(found ? body : '{"error":"not found"}') });
    }),
  );
  return calls;
}

describe("PasswordForm", () => {
  async function openAccount(routes: Route[]) {
    const calls = open(routes, false);
    await userEvent.click(screen.getByRole("button", { name: "账号" }));
    return calls;
  }

  it("changes the password and reports success", async () => {
    const calls = await openAccount([{ method: "PUT", path: "/api/auth/password", status: 200, body: '{"changed":true}' }]);
    await userEvent.type(screen.getByLabelText("当前密码"), "old");
    await userEvent.type(screen.getByLabelText("新密码"), "new-one");
    await userEvent.type(screen.getByLabelText("再输一次新密码"), "new-one");
    await userEvent.click(screen.getByRole("button", { name: "修改密码" }));
    await screen.findByRole("status");
    expect(screen.getByRole("status").textContent).toContain("密码已修改");
    expect(JSON.parse(calls.find((call) => call.method === "PUT")?.body ?? "{}")).toEqual({ current: "old", next: "new-one" });
    expect((screen.getByLabelText("当前密码") as HTMLInputElement).value).toBe("");
  });

  it("refuses locally when the two new passwords differ, and translates a wrong current password", async () => {
    const calls = await openAccount([{ method: "PUT", path: "/api/auth/password", status: 401, body: '{"error":"invalid_credentials","code":"auth/invalid-credentials"}' }]);
    await userEvent.type(screen.getByLabelText("当前密码"), "old");
    await userEvent.type(screen.getByLabelText("新密码"), "a");
    await userEvent.type(screen.getByLabelText("再输一次新密码"), "b");
    await userEvent.click(screen.getByRole("button", { name: "修改密码" }));
    await screen.findByText("两次输入的新密码不一样");
    expect(calls.filter((call) => call.method === "PUT")).toHaveLength(0);
    await userEvent.clear(screen.getByLabelText("再输一次新密码"));
    await userEvent.type(screen.getByLabelText("再输一次新密码"), "a");
    await userEvent.click(screen.getByRole("button", { name: "修改密码" }));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toBe("用户名或密码错误"));
    // 密码框不回显
    for (const input of screen.getAllByLabelText(/密码/)) expect((input as HTMLInputElement).type).toBe("password");
  });
});
