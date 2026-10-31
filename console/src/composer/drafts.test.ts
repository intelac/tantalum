import { afterEach, describe, expect, it } from "vitest";
import { clearDraft, readDraft, saveDraft } from "./drafts";

afterEach(() => sessionStorage.clear());

describe("drafts", () => {
  it("keeps a draft per session", () => {
    saveDraft("a", "hello");
    saveDraft("b", "world");
    expect(readDraft("a")).toBe("hello");
    expect(readDraft("b")).toBe("world");
    expect(readDraft("c")).toBe("");
  });

  it("clears on empty text and on clearDraft", () => {
    saveDraft("a", "hello");
    saveDraft("a", "");
    expect(sessionStorage.getItem("tantalum.draft.a")).toBeNull();
    saveDraft("a", "again");
    clearDraft("a");
    expect(readDraft("a")).toBe("");
  });

  it("survives a reload within the tab but tolerates a missing storage", () => {
    saveDraft("a", "kept");
    expect(readDraft("a", window.sessionStorage)).toBe("kept");
    expect(readDraft("a", null)).toBe("");
    saveDraft("a", "x", null);
  });
});
