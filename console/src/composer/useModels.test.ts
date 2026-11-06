import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MODELS } from "./fixtures";
import { readChoice, saveChoice } from "./drafts";
import { findModel, levelFor, pickChoice, useSessionChoice } from "./useModels";

vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return { ...actual, setModelChoice: vi.fn(() => Promise.resolve({ compaction: { percent: 100, points: [] } })) };
});
const client = await import("../api/client");

afterEach(() => {
  sessionStorage.clear();
  vi.mocked(client.setModelChoice).mockClear();
});

const SONNET = { route: "claude", model: "claude-sonnet-5-5", effort: "medium" };
const QWEN = { route: "local", model: "qwen3.8", effort: "on" };
const OPUS = { route: "claude", model: "claude-opus-5-5", effort: "high" };

function wrapper({ children }: { children: ReactNode }) {
  return createElement(QueryClientProvider, { client: new QueryClient() }, children);
}

describe("useModels", () => {
  /** 设计 useSessionChoice：没发出的 → 会话现在的 → 上次的 → 默认。 */
  it("takes the unsent choice, then the session's, then the user's last, then the default", () => {
    expect(pickChoice(QWEN, SONNET, { ...MODELS, mine: OPUS })).toEqual(QWEN);
    expect(pickChoice(null, SONNET, { ...MODELS, mine: OPUS })).toEqual(SONNET);
    expect(pickChoice(null, null, { ...MODELS, mine: OPUS })).toEqual(OPUS);
    expect(pickChoice(null, null, MODELS)).toEqual(MODELS.default);
    expect(pickChoice(null, undefined, undefined)).toBeNull();
  });

  /** 需求 3.4：新模型也有当前档位就留着，没有就用新模型的默认；没有档位的模型为 null。 */
  it("keeps the level when the new model has it and takes the new model's default otherwise", () => {
    const sonnet46 = findModel(MODELS, { route: "claude", model: "claude-sonnet-4-6", effort: null });
    const qwen = findModel(MODELS, QWEN);
    const plain = findModel(MODELS, { route: "local", model: "plain", effort: null });
    if (sonnet46 === undefined || qwen === undefined || plain === undefined) throw new Error("fixture");
    expect(levelFor(sonnet46, "high")).toBe("high");
    expect(levelFor(sonnet46, "xhigh")).toBe("medium");
    expect(levelFor(qwen, "high")).toBe("on");
    expect(levelFor(plain, "high")).toBeNull();
  });

  /** M6、M7：选了就存成上次的选择，并留作没发出的（刷新后还在）；提交成功后清掉，已生效的就是它。 */
  it("keeps an unsent choice across a reload and clears it once submitted", () => {
    const { result } = renderHook(() => useSessionChoice("s1", SONNET, MODELS), { wrapper });
    expect(result.current.shown).toEqual(SONNET);
    expect(result.current.pending).toBe(false);
    act(() => result.current.select(QWEN));
    expect(client.setModelChoice).toHaveBeenCalledWith(QWEN);
    expect(result.current.shown).toEqual(QWEN);
    expect(result.current.pending).toBe(true);
    expect(readChoice("s1")).toEqual(QWEN);

    const reloaded = renderHook(() => useSessionChoice("s1", SONNET, MODELS), { wrapper });
    expect(reloaded.result.current.shown).toEqual(QWEN);

    act(() => reloaded.result.current.submitted(QWEN));
    expect(readChoice("s1")).toBeNull();
    expect(reloaded.result.current.applied).toEqual(QWEN);
    expect(reloaded.result.current.pending).toBe(false);
  });

  it("ignores a stored choice it cannot read", () => {
    sessionStorage.setItem("tantalum.model.s2", "{oops");
    expect(readChoice("s2")).toBeNull();
    saveChoice("s2", { route: "local", model: "plain", effort: null });
    expect(readChoice("s2")).toEqual({ route: "local", model: "plain", effort: null });
  });
});
