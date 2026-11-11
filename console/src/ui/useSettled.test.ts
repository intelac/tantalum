import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSettled } from "./useSettled";

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-11-11T00:00:00Z"));
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("useSettled", () => {
  it("shows the first value at once and spaces later changes out", () => {
    const { result, rerender } = renderHook(({ value }) => useSettled(value, 150), { initialProps: { value: "a" } });
    expect(result.current).toBe("a");

    // 第一次变化离上次显示够久，立刻显示
    rerender({ value: "b" });
    expect(result.current).toBe("b");

    // 紧接着的变化要等，中间的只留最后一个
    rerender({ value: "c" });
    rerender({ value: "d" });
    expect(result.current).toBe("b");
    act(() => {
      vi.advanceTimersByTime(149);
    });
    expect(result.current).toBe("b");
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current).toBe("d");
  });
});
