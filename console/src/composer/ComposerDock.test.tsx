import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
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

/** 假后端：模型列表、最近一轮的用量（输入 65536）；别的都不通。 */
function backend() {
  vi.stubGlobal(
    "fetch",
    vi.fn((path: string) => {
      const ok = (body: unknown) => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(body)) });
      if (path === "/api/models") return ok(MODELS);
      if (path.endsWith("/turns")) return ok({ turns: [{ number: 1 }] });
      if (path.includes("/turns/")) return ok({ reply: { usage: { input: 65_536, output: 10, cacheRead: 0, cacheWrite: 0 } } });
      return Promise.resolve({ ok: false, status: 502, text: () => Promise.resolve("") });
    }),
  );
}

function mount(model: ModelChoice) {
  const store = new ConversationStore();
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
});
