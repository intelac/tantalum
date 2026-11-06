import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { ModelChangeItem } from "../api/types";
import { MODELS } from "../composer/fixtures";
import { MODELS_KEY } from "../composer/useModels";
import { ConversationStore } from "../store/ConversationStore";
import { groupBlocks } from "./blocks";
import { ModelChangeNotice } from "./ModelChangeNotice";

afterEach(cleanup);

function store(extra: Partial<ModelChangeItem>): ConversationStore {
  const conversation = new ConversationStore();
  conversation.applyItem({ kind: "modelChange", id: "7.0", session: "s", run: "run-6", at: "2026-11-07T00:00:07Z", route: "claude",
    model: "claude-sonnet-5-5", effort: "high", ...extra });
  return conversation;
}

function mount(conversation: ConversationStore) {
  const queries = new QueryClient();
  queries.setQueryData(MODELS_KEY, MODELS);
  render(
    <QueryClientProvider client={queries}>
      <ModelChangeNotice store={conversation} id="7.0" />
    </QueryClientProvider>,
  );
}

/** model-picker 需求 8.4：换了模型的那一轮上面一行“换成 {显示名} · {档位}”。 */
describe("ModelChangeNotice", () => {
  it("names the new model by its display name and level", () => {
    mount(store({}));
    expect(screen.getByRole("note").textContent).toBe("换成 Claude Sonnet 5.5 · 高");
  });

  it("writes the model alone when it has no levels, and the id when the model is gone", () => {
    mount(store({ route: "local", model: "plain", effort: null }));
    expect(screen.getByRole("note").textContent).toBe("换成 plain");
    cleanup();
    mount(store({ route: "claude", model: "claude-opus-4-1", effort: "low" }));
    expect(screen.getByRole("note").textContent).toBe("换成 claude-opus-4-1 · 低");
  });

  it("sits above the run it belongs to and tells the store which model the session uses now", () => {
    const conversation = store({ route: "local", model: "qwen3.8", effort: "off" });
    const blocks = groupBlocks(["7.0"], (id) => conversation.getItem(id));
    expect(blocks).toEqual([{ kind: "run", run: "run-6", process: [], reply: [], notices: ["7.0"] }]);
    expect(conversation.getModel("s").model).toEqual({ route: "local", model: "qwen3.8", effort: "off" });
  });
});
