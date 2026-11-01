import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
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
  const fetchMock = vi.fn((input: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    calls.push({ method, path: input, body: init?.body as string | undefined });
    const route = routes.find((candidate) => (candidate.method ?? "GET") === method && candidate.path === input);
    const status = route?.status ?? 404;
    const body = route?.body ?? '{"error":"no such memory"}';
    return Promise.resolve({ ok: status >= 200 && status < 300, status, text: () => Promise.resolve(body) });
  });
  vi.stubGlobal("fetch", fetchMock);
  return calls;
}

const LIST = {
  entries: [
    { name: "cobol-rules", type: "project", description: "COBOL 迁移约束", updatedAt: new Date().toISOString() },
    { name: "java-version", type: "user", description: "回答一律用 Java 25", updatedAt: new Date(Date.now() - 3_600_000).toISOString() },
  ],
  used: 2,
  max: 200,
};

async function openMemoryPane(routes: Route[]) {
  const calls = fakeBackend(routes);
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
      <SettingsDialog open onClose={vi.fn()} config={config} username="yi" onLogout={vi.fn()} />
    </QueryClientProvider>,
  );
  await userEvent.click(screen.getByRole("button", { name: "记忆" }));
  return calls;
}

describe("MemoryPane", () => {
  it("lists entries newest first with type tags and the quota", async () => {
    await openMemoryPane([{ path: "/api/memory", status: 200, body: JSON.stringify(LIST) }]);
    await screen.findByText("cobol-rules");
    const names = screen.getAllByRole("button").map((button) => button.textContent ?? "").filter((text) => text.includes("迁移") || text.includes("Java 25"));
    expect(names[0]).toContain("cobol-rules");
    expect(names[1]).toContain("java-version");
    expect(screen.getByText("项目")).not.toBeNull();
    expect(screen.getByText("已用 2/200 条")).not.toBeNull();
  });

  it("shows the empty state", async () => {
    await openMemoryPane([{ path: "/api/memory", status: 200, body: '{"entries":[],"used":0,"max":200}' }]);
    await screen.findByText(/还没有记忆/);
  });

  it("opens an entry, renders its Markdown body and deletes it after confirmation", async () => {
    const calls = await openMemoryPane([
      { path: "/api/memory", status: 200, body: JSON.stringify(LIST) },
      {
        path: "/api/memory/java-version",
        status: 200,
        body: JSON.stringify({ ...LIST.entries[1], content: "## Rule\n\nAlways **Java 25**." }),
      },
      { method: "DELETE", path: "/api/memory/java-version", status: 204, body: "" },
    ]);
    await userEvent.click(await screen.findByRole("button", { name: /java-version/ }));
    await screen.findByRole("heading", { name: "Rule" });
    expect(screen.getByText("Java 25").tagName).toBe("STRONG");

    await userEvent.click(screen.getByRole("button", { name: "删除" }));
    expect(screen.getByText("删除这条记忆？")).not.toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(calls.some((call) => call.method === "DELETE")).toBe(false);

    await userEvent.click(screen.getByRole("button", { name: "删除" }));
    await userEvent.click(screen.getByRole("button", { name: "确定" }));
    await waitFor(() => expect(calls.some((call) => call.method === "DELETE" && call.path === "/api/memory/java-version")).toBe(true));
    // 删除后回到列表（假后端的列表不变，这里只看视图切换）。
    await screen.findByText("COBOL 迁移约束");
  });

  it("adds an entry and shows the server's rejection reason verbatim", async () => {
    const calls = await openMemoryPane([
      { path: "/api/memory", status: 200, body: '{"entries":[],"used":0,"max":200}' },
      { method: "POST", path: "/api/memory", status: 400, body: '{"error":"memory must not contain credentials (API keys, tokens, passwords, private keys)"}' },
    ]);
    await userEvent.click(await screen.findByRole("button", { name: "新增记忆" }));
    await userEvent.type(screen.getByLabelText("名称"), "deploy-key");
    await userEvent.selectOptions(screen.getByLabelText("类型"), "reference");
    await userEvent.type(screen.getByLabelText("一句话描述"), "deploy key");
    await userEvent.type(screen.getByLabelText("正文"), "password=hunter2secret");
    await userEvent.click(screen.getByRole("button", { name: "保存" }));
    await screen.findByText(/must not contain credentials/);
    const post = calls.find((call) => call.method === "POST");
    expect(post).toBeDefined();
    expect(JSON.parse(post?.body ?? "{}")).toEqual({ name: "deploy-key", type: "reference", description: "deploy key", content: "password=hunter2secret" });
  });
});
