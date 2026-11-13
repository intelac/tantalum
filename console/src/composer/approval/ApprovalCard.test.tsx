import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, within, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { QuestionItem, ToolStepItem } from "../../api/types";
import { ApprovalCard } from "./ApprovalCard";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const BASE = { session: "s1", run: "s1_1", at: "2026-11-11T09:10:00Z" };

const QUESTION: QuestionItem = {
  ...BASE,
  kind: "question",
  id: "q:approval-c1",
  askedBy: "toolApproval",
  questionId: "approval-c1",
  title: { en: "Allow calling bash?", zh: "允许调用 bash 吗？" },
  detail: '{"command":"mvn -o test"}',
  choices: [
    { id: "once", label: { en: "Allow once", zh: "允许这一次" } },
    { id: "session", label: { en: "Always allow in this session", zh: "这个会话里一直允许" } },
    { id: "deny", label: { en: "Deny", zh: "拒绝" } },
  ],
  freeText: false,
  status: "OPEN",
  toolCallId: "c1",
};

function step(name: string, args: unknown): ToolStepItem {
  return { ...BASE, kind: "tool", id: "t1", call: { kind: "toolCall", id: "c1", name, rawArguments: JSON.stringify(args), arguments: args }, status: "WAITING" };
}

/** 假后端：记下回答；{@code status} 不是 200 时按它回。 */
function fakeBackend(status = 200) {
  const posts: { path: string; body: string }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string, init?: RequestInit) => {
      posts.push({ path: input, body: init?.body as string });
      const body = status === 404 ? { error: "gone", code: "question/gone" } : status >= 400 ? { error: "boom" } : {};
      return Promise.resolve({ ok: status < 400, status, text: () => Promise.resolve(JSON.stringify(body)) });
    }),
  );
  return posts;
}

function mount(question: QuestionItem = QUESTION, called: ToolStepItem | null = step("bash", { command: "mvn -o test" })) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>
      <input aria-label="elsewhere" />
      <ApprovalCard pending={called === null ? { question } : { question, step: called }} />
    </QueryClientProvider>,
  );
  return screen.getByRole("group", { name: "允许调用 bash 吗？" });
}

function buttons(): HTMLButtonElement[] {
  return screen.getAllByRole("button") as HTMLButtonElement[];
}

// input-area 需求 2
describe("ApprovalCard", () => {
  it("shows what is waiting, the tool and its command, and takes the focus", () => {
    const card = mount();
    expect(card.textContent).toContain("等待批准");
    expect(card.textContent).toContain("bash");
    expect(card.textContent).toContain("mvn -o test");
    expect(card.textContent).not.toContain('{"command"');
    expect(buttons().map((button) => button.textContent)).toEqual(["允许这一次", "这个会话里一直允许", "拒绝"]);
    expect(document.activeElement).toBe(card);
  });

  it.each([
    ["write_file", { path: "src/a.ts", content: "secret body" }, "src/a.ts", "secret body"],
    ["edit_file", { path: "src/b.ts", edits: [] }, "src/b.ts", "edits"],
    ["read_file", { path: "src/c.ts" }, "src/c.ts", "{"],
  ])("%s shows only the path", (name, args, shown, hidden) => {
    const card = mount(QUESTION, step(name, args));
    expect(card.textContent).toContain(shown);
    expect(card.textContent).not.toContain(hidden);
  });

  it("shows the arguments of any other tool, and the plugin's detail when the call is not loaded", () => {
    expect(mount(QUESTION, step("jira__create", { title: "Bug" })).textContent).toContain('"title": "Bug"');
    cleanup();
    expect(mount(QUESTION, null).textContent).toContain('{"command":"mvn -o test"}');
  });

  // QAgent 问题 71：浏览器操作的元素说明单独一行写在参数上面
  it("puts a browser action's element description above its arguments", () => {
    const card = mount(QUESTION, step("browser_click", { ref: "e6", element: "提交按钮" }));
    expect(within(card).getByText("提交按钮").tagName).toBe("P");
    expect(card.textContent?.indexOf("提交按钮")).toBeLessThan(card.textContent?.indexOf('"ref"') ?? 0);
    expect(card.textContent).toContain('"ref": "e6"');
  });

  it("a click sends that choice and disables every button until the question settles", async () => {
    const posts = fakeBackend();
    mount();
    await userEvent.click(screen.getByRole("button", { name: "这个会话里一直允许" }));
    expect(posts).toEqual([{ path: "/api/sessions/s1/questions/approval-c1", body: '{"choice":"session"}' }]);
    await waitFor(() => expect(buttons().every((button) => button.disabled)).toBe(true));
    // 已经交上了：再按回车不交第二次
    fireEvent.keyDown(document.body, { key: "Enter" });
    expect(posts).toHaveLength(1);
  });

  it("Enter picks the first choice and Escape picks deny", async () => {
    const posts = fakeBackend();
    const card = mount();
    fireEvent.keyDown(card, { key: "Enter" });
    await waitFor(() => expect(posts.map((post) => post.body)).toEqual(['{"choice":"once"}']));
    cleanup();
    posts.length = 0;
    fireEvent.keyDown(mount(), { key: "Escape" });
    await waitFor(() => expect(posts.map((post) => post.body)).toEqual(['{"choice":"deny"}']));
  });

  it("Escape does nothing when no choice is called deny", () => {
    const posts = fakeBackend();
    const card = mount({ ...QUESTION, choices: [{ id: "yes", label: "Yes" }, { id: "no", label: "No" }] });
    fireEvent.keyDown(card, { key: "Escape" });
    expect(posts).toEqual([]);
    expect(card.textContent).not.toContain("Esc");
  });

  it("leaves keys alone on a button, in an input, with a modifier, while repeating or composing", () => {
    const posts = fakeBackend();
    const card = mount();
    fireEvent.keyDown(screen.getByRole("button", { name: "拒绝" }), { key: "Enter" });
    fireEvent.keyDown(screen.getByRole("textbox", { name: "elsewhere" }), { key: "Escape" });
    fireEvent.keyDown(card, { key: "Enter", metaKey: true });
    fireEvent.keyDown(card, { key: "Enter", shiftKey: true });
    fireEvent.keyDown(card, { key: "Enter", repeat: true });
    fireEvent.keyDown(card, { key: "Enter", isComposing: true });
    fireEvent.keyDown(card, { key: "a" });
    expect(posts).toEqual([]);
  });

  it("leaves keys alone while a dialog is open", () => {
    const posts = fakeBackend();
    const card = mount();
    const dialog = document.createElement("div");
    dialog.setAttribute("role", "dialog");
    document.body.append(dialog);
    fireEvent.keyDown(card, { key: "Escape" });
    dialog.remove();
    expect(posts).toEqual([]);
  });

  it("enables the buttons again and says why when the answer did not go through", async () => {
    fakeBackend(500);
    mount();
    await userEvent.click(screen.getByRole("button", { name: "允许这一次" }));
    expect((await screen.findByRole("alert")).textContent).toContain("没有交上");
    expect(buttons().every((button) => !button.disabled)).toBe(true);
    cleanup();
    fakeBackend(404);
    mount();
    await userEvent.click(screen.getByRole("button", { name: "拒绝" }));
    expect((await screen.findByRole("alert")).textContent).toBe("这个问题已经不用回答了");
  });

  it("counts down to the deadline", () => {
    const card = mount({ ...QUESTION, deadline: new Date(Date.now() + 90_000).toISOString() });
    expect(card.textContent).toMatch(/还剩/);
  });
});
