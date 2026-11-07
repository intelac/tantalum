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
    expect(screen.queryByRole("button", { name: "用户" })).toBeNull();
    expect(screen.getByRole("button", { name: "账号" })).not.toBeNull();
  });

  it("lists accounts and keeps an administrator from disabling or demoting themselves", async () => {
    open([{ path: "/api/users", status: 200, body: JSON.stringify(USERS) }], true);
    await userEvent.click(screen.getByRole("button", { name: "用户" }));
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
    await userEvent.click(screen.getByRole("button", { name: "用户" }));
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
    await userEvent.click(screen.getByRole("button", { name: "用户" }));
    await screen.findByText("alice");
    await userEvent.click(within(screen.getByRole("group", { name: "alice 的操作" })).getByRole("button", { name: "停用" }));
    await screen.findByText("这是最后一个管理员，不能停用或取消");
  });
});

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
