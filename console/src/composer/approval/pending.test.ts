import { describe, expect, it } from "vitest";
import type { Item, QuestionItem, ToolStepItem } from "../../api/types";
import { pendingApproval } from "./pending";

const BASE = { session: "s", run: "r", at: "2026-11-11T00:00:00Z" };

function question(id: string, more: Partial<QuestionItem> = {}): QuestionItem {
  return { ...BASE, kind: "question", id: `q:${id}`, askedBy: "toolApproval", questionId: id, title: id, detail: "", choices: [], freeText: false, status: "OPEN", ...more };
}

function step(callId: string): ToolStepItem {
  return { ...BASE, kind: "tool", id: `t-${callId}`, call: { kind: "toolCall", id: callId, name: "bash", rawArguments: "{}", arguments: {} }, status: "WAITING" };
}

describe("pendingApproval", () => {
  it("is nothing when no question is open", () => {
    expect(pendingApproval([])).toBeNull();
    expect(pendingApproval([step("c1"), question("a", { toolCallId: "c1", status: "ANSWERED" })])).toBeNull();
  });

  it("does not count questions that are not about a tool call", () => {
    expect(pendingApproval([question("ask", { askedBy: "ask_user" })])).toBeNull();
  });

  it("is the latest open question together with the call it is about", () => {
    const first = question("a", { toolCallId: "c1" });
    const second = question("b", { toolCallId: "c2" });
    const called = step("c2");
    const items: (Item | undefined)[] = [step("c1"), first, undefined, called, second];
    expect(pendingApproval(items)).toEqual({ question: second, step: called });
  });

  it("still shows the question when the call has not been loaded", () => {
    const asked = question("a", { toolCallId: "c9" });
    expect(pendingApproval([asked])).toEqual({ question: asked });
  });
});
