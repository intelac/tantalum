import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { QuestionItem as QuestionItemData } from "../api/types";
import { ConversationStore } from "../store/ConversationStore";
import { QuestionItem } from "./QuestionItem";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const APPROVAL: QuestionItemData = {
  kind: "question",
  id: "q:approval-c1",
  session: "s1",
  run: "s1_1",
  at: "2026-11-07T09:10:00Z",
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
};

const ASK_USER: QuestionItemData = {
  ...APPROVAL,
  id: "q:ask-a1",
  askedBy: "ask_user",
  questionId: "ask-a1",
  title: "Delete the backups?",
  detail: "",
  choices: [
    { id: "delete", label: "delete" },
    { id: "keep", label: "keep" },
  ],
  freeText: true,
  deadline: new Date(Date.now() + 90_000).toISOString(),
};

/** 假后端：记下回答；{@code status} 不是 200 时按它回。 */
function fakeBackend(status = 200) {
  const posts: { path: string; body: string }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string, init?: RequestInit) => {
      posts.push({ path: input, body: init?.body as string });
      const body = status === 404 ? { error: "gone", code: "question/gone" } : {};
      return Promise.resolve({ ok: status < 400, status, text: () => Promise.resolve(JSON.stringify(body)) });
    }),
  );
  return posts;
}

function mount(item: QuestionItemData) {
  const store = new ConversationStore();
  store.applyItem(item);
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>
      <QuestionItem store={store} id={item.id} />
    </QueryClientProvider>,
  );
  return store;
}

describe("QuestionItem", () => {
  // 需求 7.1：等回答时标题、说明、按钮；点了交回选项，然后等条目更新（按钮禁用，不本地假设）
  it("shows the approval and sends the picked choice, then waits for the item to settle", async () => {
    const posts = fakeBackend();
    const store = mount(APPROVAL);
    const card = screen.getByRole("group", { name: "允许调用 bash 吗？" });
    expect(card.textContent).toContain('{"command":"mvn -o test"}');
    expect(screen.getAllByRole("button").map((button) => button.textContent)).toEqual(["允许这一次", "这个会话里一直允许", "拒绝"]);

    await userEvent.click(screen.getByRole("button", { name: "这个会话里一直允许" }));
    expect(posts).toEqual([{ path: "/api/sessions/s1/questions/approval-c1", body: '{"choice":"session"}' }]);
    await waitFor(() => expect((screen.getByRole("button", { name: "拒绝" }) as HTMLButtonElement).disabled).toBe(true));

    // 结算的条目来了：只读显示问题和回答
    store.applyItem({ ...APPROVAL, status: "ANSWERED", choice: "session", settledAt: "2026-11-07T09:11:00Z" }, 20);
    const note = await screen.findByRole("note", { name: "允许调用 bash 吗？" });
    expect(note.textContent).toContain("你的回答：这个会话里一直允许");
    expect(screen.queryByRole("button")).toBeNull();
  });

  // 需求 5.3、7.1：ask_user 允许自由文本；选项加文字一起交；期限倒计时
  it("sends the choice with the typed text and shows the countdown", async () => {
    const posts = fakeBackend();
    mount(ASK_USER);
    expect(screen.getByText(/还剩/).textContent).toMatch(/还剩 1 min|还剩 \d+/);
    await userEvent.type(screen.getByRole("textbox", { name: "写一句回答" }), "归档到 old/");
    await userEvent.click(screen.getByRole("button", { name: "keep" }));
    expect(posts).toEqual([{ path: "/api/sessions/s1/questions/ask-a1", body: '{"choice":"keep","text":"归档到 old/"}' }]);
  });

  // 只有文本的问题：有“回答”按钮，空的时候不能交
  it("a text-only question needs text before it can be sent", async () => {
    const posts = fakeBackend();
    mount({ ...ASK_USER, choices: [], deadline: undefined });
    const send = screen.getByRole("button", { name: "回答" });
    expect((send as HTMLButtonElement).disabled).toBe(true);
    await userEvent.type(screen.getByRole("textbox", { name: "写一句回答" }), "second floor");
    expect((send as HTMLButtonElement).disabled).toBe(false);
    await userEvent.click(send);
    expect(posts.map((post) => post.body)).toEqual(['{"text":"second floor"}']);
  });

  it("says so when the question is no longer waiting", async () => {
    fakeBackend(404);
    mount(APPROVAL);
    await userEvent.click(screen.getByRole("button", { name: "拒绝" }));
    expect((await screen.findByRole("alert")).textContent).toBe("这个问题已经不用回答了");
  });

  it("shows expired and cancelled questions read-only", () => {
    mount({ ...APPROVAL, id: "q:x", status: "EXPIRED" });
    expect(screen.getByRole("note").textContent).toContain("到期没有回答");
    cleanup();
    mount({ ...APPROVAL, id: "q:y", status: "CANCELLED" });
    expect(screen.getByRole("note").textContent).toContain("运行已取消，没有回答");
  });
});
