import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { SessionSummary, UserItem } from "../api/types";
import { SESSIONS_KEY } from "../shell/useSessions";
import { ConversationStore } from "../store/ConversationStore";
import { UserBubble } from "./UserBubble";

afterEach(() => {
  cleanup();
  window.history.replaceState(null, "", "/");
});

function mount(extra: Partial<UserItem>, sessions: Partial<SessionSummary>[] = []) {
  const conversation = new ConversationStore();
  conversation.applyItem({ kind: "user", id: "9.0", session: "s", run: "s_9", at: "2026-11-17T00:00:09Z", text: "hello", queued: false, ...extra });
  const queries = new QueryClient();
  queries.setQueryData(SESSIONS_KEY, { sessions });
  return render(
    <QueryClientProvider client={queries}>
      <UserBubble store={conversation} id="9.0" />
    </QueryClientProvider>,
  );
}

/** 另一个会话送来的话不是用户说的：不是气泡，写明是哪个会话送来的，能点过去。 */
describe("SessionNote", () => {
  it("keeps what the user typed as a bubble", () => {
    const { container } = mount({});
    expect(container.querySelector("[data-from-session]")).toBeNull();
    expect(container.querySelector(".bg-bubble")?.textContent).toBe("hello");
  });

  it("shows handed-in work with the conversation it came from, and opens that conversation", () => {
    const { container } = mount({ text: "count the files", from: { session: "s-a", reply: false } }, [{ id: "s-a", title: "整理仓库" }]);
    expect(container.querySelector(".bg-bubble")).toBeNull();
    expect(container.querySelector("[data-from-session='s-a']")).not.toBeNull();
    const link = screen.getByRole("button", { name: "「整理仓库」交来的活" });
    expect(screen.getByText("count the files")).toBeDefined();
    fireEvent.click(link);
    expect(window.location.pathname).toContain("s-a");
  });

  it("names the main window, and an answer as an answer", () => {
    mount({ text: "42 files", from: { session: "main-2026-11-17", reply: true } });
    expect(screen.getByRole("button", { name: "「主窗口」的回话" })).toBeDefined();
  });

  it("says when the other conversation did not finish, and falls back when it is not in the list", () => {
    mount({ text: "cancelled", from: { session: "s-gone", reply: true, failed: true } });
    expect(screen.getByRole("button", { name: "「另一个会话」没做成" })).toBeDefined();
  });

  it("folds a long answer until asked", () => {
    const text = Array.from({ length: 30 }, (_, i) => `line ${i + 1}`).join("\n\n");
    mount({ text, from: { session: "s-a", reply: true } });
    expect(screen.queryByText("line 30")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "展开全部" }));
    expect(screen.getByText("line 30")).toBeDefined();
  });
});
