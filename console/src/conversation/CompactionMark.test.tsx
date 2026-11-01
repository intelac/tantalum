import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import type { CompactionItem, TextItem } from "../api/types";
import { ConversationStore } from "../store/ConversationStore";
import { AssistantText } from "./AssistantText";
import { CompactionMark } from "./CompactionMark";

afterEach(cleanup);

function mark(extra: Partial<CompactionItem> = {}): CompactionItem {
  return {
    kind: "compaction",
    id: "9.0",
    session: "s",
    run: "run-5",
    at: "2026-11-01T00:00:09Z",
    trigger: "AUTO",
    summary: "## Goal\n\nKeep counting.",
    estimateBefore: 14200,
    estimateAfter: 4100,
    ...extra,
  };
}

function storeWith(item: CompactionItem | TextItem): ConversationStore {
  const store = new ConversationStore();
  store.applyItem(item);
  return store;
}

describe("CompactionMark", () => {
  it("shows the size change and reveals the summary on demand", async () => {
    render(<CompactionMark store={storeWith(mark())} id="9.0" />);
    const toggle = screen.getByRole("button", { name: /上下文已压缩 14.2K → 4.1K tok/ });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByText("Keep counting.")).toBeNull();
    await userEvent.click(toggle);
    expect(screen.getByRole("heading", { name: "Goal" })).toBeDefined();
    expect(screen.getByText("Keep counting.")).toBeDefined();
  });

  it("labels manual and overflow compactions", () => {
    render(<CompactionMark store={storeWith(mark({ trigger: "MANUAL" }))} id="9.0" />);
    expect(screen.getByRole("button", { name: /· 手动/ })).toBeDefined();
    cleanup();
    render(<CompactionMark store={storeWith(mark({ trigger: "OVERFLOW" }))} id="9.0" />);
    expect(screen.getByRole("button", { name: /· 超长恢复/ })).toBeDefined();
  });

  it("shows only the reason when the compaction was not adopted", () => {
    render(<CompactionMark store={storeWith(mark({ summary: undefined, failure: "summary is empty", estimateBefore: 0, estimateAfter: 0 }))} id="9.0" />);
    expect(screen.getByRole("note").textContent).toBe("压缩未采用：summary is empty");
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("renders nothing for other items", () => {
    const { container } = render(<CompactionMark store={new ConversationStore()} id="nope" />);
    expect(container.textContent).toBe("");
  });
});

describe("AssistantText truncated", () => {
  it("marks a reply that hit the output limit", () => {
    const text: TextItem = { kind: "text", id: "3.0", session: "s", run: "run-1", at: "2026-11-01T00:00:03Z", text: "half of", interrupted: false, truncated: true };
    render(<AssistantText store={storeWith(text)} id="3.0" last={false} />);
    expect(screen.getByRole("note").textContent).toBe("回复被截断：达到输出上限");
  });
});
