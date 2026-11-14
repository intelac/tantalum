import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const searchContent = vi.fn();
vi.mock("../api/client", () => ({ searchContent: (...args: unknown[]) => searchContent(...args) }));

const { CONTENT_DELAY_MS, useContentSearch } = await import("./useContentSearch");

// session-find-pin R3.3、R3.4
describe("useContentSearch", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    searchContent.mockReset();
  });

  it("waits for typing to stop, asks once, and aborts the request of an older query", async () => {
    const signals: AbortSignal[] = [];
    searchContent.mockImplementation((_q: string, signal: AbortSignal) => {
      signals.push(signal);
      return Promise.resolve({ hits: [{ session: "s1", role: "user", snippet: "inv" }], more: false });
    });
    const { result, rerender } = renderHook(({ q }) => useContentSearch(q), { initialProps: { q: "in" } });
    expect(result.current.loading).toBe(true);
    rerender({ q: "inv" });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(CONTENT_DELAY_MS);
    });
    expect(searchContent).toHaveBeenCalledTimes(1);
    expect(searchContent).toHaveBeenCalledWith("inv", expect.anything());
    expect(result.current.result?.hits[0]?.session).toBe("s1");
    rerender({ q: "invo" });
    expect(signals[0]?.aborted).toBe(true);
    expect(result.current.result).toBeNull();
  });

  it("reports a failure and stays idle for an empty query", async () => {
    searchContent.mockRejectedValue(new Error("down"));
    const { result, rerender } = renderHook(({ q }) => useContentSearch(q), { initialProps: { q: "x" } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(CONTENT_DELAY_MS);
    });
    expect(result.current.failed).toBe(true);
    rerender({ q: "  " });
    expect(result.current).toEqual({ result: null, loading: false, failed: false });
  });
});
