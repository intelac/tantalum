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

  /** qagent-align 需求 3.7：折算后的数要写明是折算的，并能看到原始用量；没配系数时不出现“折算”。 */
  it("says weighted and gives the raw usage when the server weighs tokens", () => {
    render(<TodayUsage usage={{ day: "2026-11-12", input: 100, output: 20, cacheRead: 300, cacheWrite: 40, used: 270, weighted: true, limit: 1000 }} />);
    const status = screen.getByRole("status");
    expect(status.textContent).toContain("今日折算 270 / 1,000");
    expect(status.getAttribute("title")).toContain("按模型折算");
    expect(status.getAttribute("title")).toContain("原始用量是 460 token");
    cleanup();
    render(<TodayUsage usage={today(270, 1000)} />);
    expect(screen.getByRole("status").textContent).not.toContain("折算");
    expect(screen.getByRole("status").getAttribute("title")).not.toContain("折算");
  });
});
