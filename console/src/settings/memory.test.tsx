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
  body: string | (() => string);
}

/** 按方法和路径回应的假后端；记录每次调用。 */
function fakeBackend(routes: Route[]) {
  const calls: { method: string; path: string; body?: string }[] = [];
  const fetchMock = vi.fn((input: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    calls.push({ method, path: input, body: init?.body as string | undefined });
    const route = routes.find((candidate) => (candidate.method ?? "GET") === method && candidate.path === input);
    const status = route?.status ?? 404;
    const given = route?.body ?? '{"error":"no such memory"}';
    const body = typeof given === "string" ? given : given();
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

async function openMemoryPane(routes: Route[], tab: string | null = "档案") {
  const calls = fakeBackend(routes);
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
      <SettingsDialog open onClose={vi.fn()} config={config} username="yi" onLogout={vi.fn()} />
    </QueryClientProvider>,
  );
  await userEvent.click(screen.getByRole("button", { name: "记忆" }));
  // 记忆页改版后分四栏（memory-final-plan 第 16 节）：长期记忆在“档案”
  if (tab !== null) await userEvent.click(await screen.findByRole("tab", { name: tab }));
  return calls;
}

describe("MemoryPane", () => {
  it("lists entries newest first with type tags and the quota", async () => {
    await openMemoryPane([{ path: "/api/plugin-api/memory/entries", status: 200, body: JSON.stringify(LIST) }]);
    await screen.findByText("cobol-rules");
    const names = screen.getAllByRole("button").map((button) => button.textContent ?? "").filter((text) => text.includes("迁移") || text.includes("Java 25"));
    expect(names[0]).toContain("cobol-rules");
    expect(names[1]).toContain("java-version");
    expect(screen.getByText("项目")).not.toBeNull();
    expect(screen.getByText("已用 2/200 条")).not.toBeNull();
  });

  it("shows the empty state", async () => {
    await openMemoryPane([{ path: "/api/plugin-api/memory/entries", status: 200, body: '{"entries":[],"used":0,"max":200}' }]);
    await screen.findByText(/还没有记忆/);
  });

  it("opens an entry, renders its Markdown body and deletes it after confirmation", async () => {
    const calls = await openMemoryPane([
      { path: "/api/plugin-api/memory/entries", status: 200, body: JSON.stringify(LIST) },
      {
        path: "/api/plugin-api/memory/entries/java-version",
        status: 200,
        body: JSON.stringify({ ...LIST.entries[1], content: "## Rule\n\nAlways **Java 25**." }),
      },
      { method: "DELETE", path: "/api/plugin-api/memory/entries/java-version", status: 204, body: "" },
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
    await waitFor(() => expect(calls.some((call) => call.method === "DELETE" && call.path === "/api/plugin-api/memory/entries/java-version")).toBe(true));
    // 删除后回到列表（假后端的列表不变，这里只看视图切换）。
    await screen.findByText("COBOL 迁移约束");
  });

  it("adds an entry and shows the server's rejection reason verbatim", async () => {
    const calls = await openMemoryPane([
      { path: "/api/plugin-api/memory/entries", status: 200, body: '{"entries":[],"used":0,"max":200}' },
      { method: "POST", path: "/api/plugin-api/memory/entries", status: 400, body: '{"error":"memory must not contain credentials (API keys, tokens, passwords, private keys)"}' },
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

  it("says the memory plugin is off instead of showing the pane (memory-plugin 3.1)", async () => {
    await openMemoryPane(
      [
        { path: "/api/plugins", status: 200, body: JSON.stringify({ tools: [], plugins: [{ name: "memory", enabled: false, points: [], params: [] }] }) },
        { path: "/api/plugin-api/memory/entries", status: 200, body: JSON.stringify(LIST) },
      ],
      null,
    );
    expect(await screen.findByText("记忆插件没开。可以在插件页打开“记忆”。")).toBeTruthy();
    expect(screen.queryByText("COBOL 迁移约束")).toBeNull();
  });

  // memory-final-plan 第 5、7 节：有等确认的先打开“待确认”，确认后发到服务器；“现在”按种类列出工作记忆
  it("opens on the items to confirm, confirms one, and lists working memory by kind", async () => {
    const pending = { items: [{ id: "m1", kind: "claim", page: "team-meetings", text: "网页上说：周会改到周五下午。", source: "tool_web", created: new Date().toISOString() }] };
    const now = { items: [{ id: "t1", version: 2, kind: "task", title: "把发布说明翻译成日文", status: "active", source: "user_said", updated: new Date().toISOString(), stale: true }] };
    const calls = await openMemoryPane(
      [
        { path: "/api/plugin-api/memory/pending", status: 200, body: JSON.stringify(pending) },
        { path: "/api/plugin-api/memory/now", status: 200, body: JSON.stringify(now) },
        { method: "POST", path: "/api/plugin-api/memory/items/m1/confirm", status: 200, body: '{"done":true}' },
      ],
      null,
    );
    expect(await screen.findByText("网页上说：周会改到周五下午。")).toBeTruthy();
    expect(screen.getByText(/来自网页/)).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: "确认" }));
    await waitFor(() => expect(calls.some((call) => call.method === "POST" && call.path.endsWith("/items/m1/confirm"))).toBe(true));
    await userEvent.click(screen.getByRole("tab", { name: "现在" }));
    expect(await screen.findByText("把发布说明翻译成日文")).toBeTruthy();
    expect(screen.getByRole("region", { name: "在做的事" })).toBeTruthy();
    expect(screen.getByText(/可能过期/)).toBeTruthy();
  });

  // 按一句话忘掉分两步：先找（什么都不改、一行都不替你勾），你勾了哪几行就只发那几行
  it("forgets by a sentence only the lines the user ticks", async () => {
    let step = 0;
    const calls = await openMemoryPane([
      { path: "/api/plugin-api/memory/entries", status: 200, body: JSON.stringify(LIST) },
      {
        method: "POST",
        path: "/api/plugin-api/memory/forget",
        status: 200,
        body: () =>
          step++ === 0
            ? JSON.stringify({
                places: [
                  { id: "a@1#0", kind: "CLAIM", page: "car", text: "车牌号是 7Q2KD", approved: false },
                  { id: "b@2#1", kind: "CARD", text: "办停车证要用车牌 7Q2KD", approved: false },
                ],
              })
            : '{"removed":["车牌号是 7Q2KD"],"stale":[]}',
      },
    ]);
    await userEvent.type(await screen.findByRole("textbox", { name: /按一句话忘掉/ }), "我的车牌号");
    await userEvent.click(screen.getByRole("button", { name: "查找" }));
    const first = await screen.findByRole("checkbox", { name: /车牌号是 7Q2KD.*条目 car/ });
    expect(screen.getByRole("checkbox", { name: /办停车证.*每天的笔记/ })).toHaveProperty("checked", false);
    expect(first).toHaveProperty("checked", false);
    expect(screen.getByRole("button", { name: "忘掉选中的" })).toHaveProperty("disabled", true);
    await userEvent.click(first);
    await userEvent.click(screen.getByRole("button", { name: "忘掉选中的 1 行" }));
    expect(await screen.findByText("忘掉了 1 行。")).toBeTruthy();
    const posts = calls.filter((call) => call.path === "/api/plugin-api/memory/forget").map((call) => JSON.parse(call.body ?? "{}"));
    expect(posts).toEqual([{ find: "我的车牌号" }, { places: ["a@1#0"] }]);
  });
});
