import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ModelChoice } from "../api/types";
import { ConversationStore } from "../store/ConversationStore";
import { ComposerDock } from "./ComposerDock";
import { MODELS } from "./fixtures";

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

let mounted: ConversationStore;

/** 假后端：模型列表、最近一轮的用量（输入 65536）；别的都不通。 */
function backend(pending: unknown[] = []) {
  vi.stubGlobal(
    "fetch",
    vi.fn((path: string) => {
      const ok = (body: unknown) => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(body)) });
      if (path === "/api/models") return ok(MODELS);
      if (path === "/api/plugin-api/memory/pending") return ok({ items: pending });
      if (path.endsWith("/context")) return ok({ used: 65_536, window: 131_072, limit: 120_000, parts: { system: 536, tools: 5000, messages: 60_000 } });
      return Promise.resolve({ ok: false, status: 502, text: () => Promise.resolve("") });
    }),
  );
}

function mount(model: ModelChoice) {
  const store = new ConversationStore();
  mounted = store;
  store.loadLatest("s", {
    seq: 3,
    start: 0,
    hasMore: false,
    model,
    toolChangesInHistory: false,
    items: [{ kind: "text", id: "2.0", session: "s", run: "r1", at: "2026-11-07T00:00:02Z", text: "ok" }],
  } as never);
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ComposerDock store={store} session="s" config={{ provider: "claude", model: "claude-sonnet-5-5", cloud: true, contextWindow: 1_000_000 }} />
    </QueryClientProvider>,
  );
}

/** model-picker 需求 8.3、3.1：选择器显示会话现在的模型；状态栏的占比按这个模型的窗口算。 */
describe("ComposerDock", () => {
  it("shows the session's model and measures the context against its window", async () => {
    backend();
    mount({ route: "local", model: "qwen3.8", effort: "on" });
    expect(await screen.findByText("qwen3.8 · 开")).not.toBeNull();
    expect(await screen.findByText("上下文 50%")).not.toBeNull();
  });

  it("uses the million-token window for a Claude session", async () => {
    backend();
    mount({ route: "claude", model: "claude-sonnet-5-5", effort: "medium" });
    expect(await screen.findByText("Claude Sonnet 5.5 · 中")).not.toBeNull();
    expect(await screen.findByText("上下文 7%")).not.toBeNull();
  });

  // input-area 需求 2：等批准时卡片顶替输入框，输入框只是藏起来，写了一半的话还在
  it("swaps the composer for the approval card and keeps the draft", async () => {
    backend();
    mount({ route: "local", model: "qwen3.8", effort: "on" });
    const box = screen.getByRole("textbox") as HTMLTextAreaElement;
    await userEvent.type(box, "half a sentence");
    const base = { session: "s", run: "r2", at: "2026-11-07T00:00:05Z" };
    const question = { ...base, kind: "question", id: "q:a", askedBy: "toolApproval", questionId: "a", title: "Allow bash?", detail: "", choices: [{ id: "once", label: "Allow once" }], freeText: false, status: "OPEN", toolCallId: "c1" };
    act(() => {
      mounted.applyItem({ ...base, kind: "tool", id: "5.0", call: { kind: "toolCall", id: "c1", name: "bash", rawArguments: "{}", arguments: { command: "ls" } }, status: "WAITING" } as never, 5);
      mounted.applyItem(question as never, 6);
    });
    const card = await screen.findByRole("group", { name: "Allow bash?" });
    expect(card.textContent).toContain("ls");
    expect(box.closest("[hidden]")).not.toBeNull();

    act(() => mounted.applyItem({ ...question, status: "ANSWERED", choice: "once" } as never, 7));
    expect(screen.queryByRole("group", { name: "Allow bash?" })).toBeNull();
    expect(box.closest("[hidden]")).toBeNull();
    expect(box.value).toBe("half a sentence");
  });

  // input-area 需求 1：排队的消息列在输入框上方；被移除、或者轮到了，就从条里消失
  it("lists what is queued and drops what was removed or has started", async () => {
    backend();
    mount({ route: "local", model: "qwen3.8", effort: "on" });
    const base = { session: "s", at: "2026-11-07T00:00:05Z", kind: "user" };
    act(() => {
      mounted.applyItem({ ...base, id: "5.0", text: "甲", queued: true } as never, 5);
      mounted.applyItem({ ...base, id: "6.0", text: "乙", queued: true } as never, 6);
    });
    await userEvent.click(await screen.findByRole("button", { name: "展开排队的消息" }));
    expect(screen.getAllByRole("listitem").map((row) => row.textContent)).toEqual(["甲", "乙"]);

    act(() => mounted.applyItem({ ...base, id: "5.0", text: "甲", queued: false, removed: true } as never, 7));
    expect(screen.getAllByRole("listitem").map((row) => row.textContent)).toEqual(["乙"]);

    act(() => mounted.applyItem({ ...base, id: "6.0", text: "乙", queued: false, run: "r2" } as never, 8));
    expect(screen.queryByRole("group", { name: "排队的消息" })).toBeNull();
  });

  // memory-final-plan 第 11 节：还没发第一句时能拨“不进记忆”，随第一次提问发出
  it("sends off-the-record with the first prompt of a new session", async () => {
    backend();
    mount({ route: "local", model: "qwen3.8", effort: "on" });
    const toggle = await screen.findByRole("button", { name: "不进记忆" });
    expect(toggle.getAttribute("aria-pressed")).toBe("false");
    await userEvent.click(toggle);
    expect(toggle.getAttribute("aria-pressed")).toBe("true");
    await userEvent.type(screen.getByRole("textbox"), "我在找新工作{Enter}");
    const fetched = vi.mocked(fetch).mock.calls.find(([path]) => String(path).endsWith("/submissions"));
    expect(fetched).toBeDefined();
    expect(JSON.parse(String((fetched?.[1] as RequestInit).body))).toMatchObject({ text: "我在找新工作", offRecord: true });
  });

  // memory-final-plan 第 7 节：这个会话里等你确认的记忆出现在输入框上方，别的会话的不出现
  it("shows this session's memories waiting for confirmation above the composer", async () => {
    const at = new Date().toISOString();
    backend([
      { id: "m1", kind: "claim", page: "preferences", text: "命令都不用问", source: "assistant_inferred", session: "s", created: at },
      { id: "m2", kind: "task", text: "别的会话的", source: "tool_web", session: "other", created: at },
    ]);
    mount({ route: "local", model: "qwen3.8", effort: "on" });
    const strip = await screen.findByRole("region", { name: "记住了，要你确认：" });
    expect(strip.textContent).toContain("命令都不用问");
    expect(strip.textContent).not.toContain("别的会话的");
    expect(screen.getByRole("button", { name: "确认" })).not.toBeNull();
  });
});
