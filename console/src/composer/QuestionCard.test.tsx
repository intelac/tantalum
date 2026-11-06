import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Question } from "../api/types";
import { QuestionCards } from "./QuestionCard";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const APPROVAL: Question = {
  id: "approval-c1",
  title: "允许调用 bash 吗？",
  detail: '{"command":"mvn -o test"}',
  choices: [
    { id: "once", label: "允许这一次" },
    { id: "session", label: "这个会话里一直允许" },
    { id: "deny", label: "拒绝" },
  ],
};

/** 假后端：GET 回还没回答的问题；POST 记下回答，问题随之撤下；{@code gone} 时 POST 回 404。 */
function fakeBackend(gone = false) {
  const posts: { path: string; body: string }[] = [];
  let open: Question[] = [APPROVAL];
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string, init?: RequestInit) => {
      const respond = (status: number, body: unknown) =>
        Promise.resolve({ ok: status < 400, status, text: () => Promise.resolve(JSON.stringify(body)) });
      if ((init?.method ?? "GET") === "GET") return respond(200, { questions: open });
      posts.push({ path: input, body: init?.body as string });
      if (gone) return respond(404, { error: "这个问题已经不用回答了" });
      open = [];
      return respond(200, {});
    }),
  );
  return posts;
}

function renderCards() {
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queries}>
      <QuestionCards session="s1" />
    </QueryClientProvider>,
  );
}

describe("QuestionCards", () => {
  /** A4：工具审批在对话页上的三个选项；选“这个会话里一直允许”交回 session，问题随之消失。 */
  it("shows the approval with its three choices and sends the one picked", async () => {
    const posts = fakeBackend();
    renderCards();
    const card = await screen.findByRole("group", { name: "允许调用 bash 吗？" });
    expect(card.textContent).toContain('{"command":"mvn -o test"}');
    expect(screen.getAllByRole("button").map((button) => button.textContent)).toEqual(["允许这一次", "这个会话里一直允许", "拒绝"]);

    await userEvent.click(screen.getByRole("button", { name: "这个会话里一直允许" }));
    expect(posts).toEqual([{ path: "/api/sessions/s1/questions/approval-c1", body: '{"choice":"session"}' }]);
    await waitFor(() => expect(screen.queryByRole("group")).toBeNull());
  });

  it("says so when the question is no longer waiting", async () => {
    fakeBackend(true);
    renderCards();
    await userEvent.click(await screen.findByRole("button", { name: "拒绝" }));
    expect((await screen.findByRole("alert")).textContent).toBe("这个问题已经不用回答了");
  });
});
