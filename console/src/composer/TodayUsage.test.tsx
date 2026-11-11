import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { TodayUsageView } from "../api/types";
import { TodayUsage } from "./TodayUsage";

afterEach(cleanup);

function today(used: number, limit?: number): TodayUsageView {
  return { day: "2026-11-11", input: used, output: 0, cacheRead: 0, cacheWrite: 0, used, limit };
}

describe("TodayUsage", () => {
  it("shows nothing without a daily limit", () => {
    const { container } = render(<TodayUsage usage={today(2_606_090)} />);
    expect(container.textContent).toBe("");
    cleanup();
    expect(render(<TodayUsage usage={undefined} />).container.textContent).toBe("");
  });

  it("shows used over limit, and the percent for narrow screens", () => {
    render(<TodayUsage usage={today(2_606_090, 300_000_000)} />);
    const status = screen.getByRole("status");
    expect(status.textContent).toContain("今日 2,606,090 / 300,000,000");
    expect(status.textContent).toContain("今日 0%");
    expect(status.className).not.toContain("text-warning");
    expect(status.getAttribute("title")).toContain("每日上限 300,000,000");
  });

  it.each([
    [900, "text-warning", "每日上限"],
    [1000, "text-danger", "用完了"],
    [1500, "text-danger", "用完了"],
  ])("at %d of 1000 it is %s", (used, colour, hint) => {
    render(<TodayUsage usage={today(used, 1000)} />);
    const status = screen.getByRole("status");
    expect(status.className).toContain(colour);
    expect(status.getAttribute("title")).toContain(hint);
    expect(status.querySelector<HTMLElement>("span[aria-hidden] > span")!.style.width).toBe(`${Math.min(100, Math.floor(used / 10))}%`);
  });
});
