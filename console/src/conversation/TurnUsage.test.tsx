import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { TurnUsage } from "./TurnUsage";

afterEach(cleanup);

function rows(): string[] {
  const dialog = screen.getByRole("dialog", { name: "本轮用量" });
  const terms = Array.from(dialog.querySelectorAll("dt"));
  return terms.map((term) => `${term.textContent}=${term.nextElementSibling?.textContent}`);
}

// input-area 需求 5
describe("TurnUsage", () => {
  it("shows the compact total and opens the details", async () => {
    render(<TurnUsage usage={{ input: 1200, output: 349, cacheRead: 10_000, cacheWrite: 700 }} />);
    const chip = screen.getByRole("button", { name: "用量 12.2K" });
    expect(screen.queryByRole("dialog")).toBeNull();
    await userEvent.click(chip);
    expect(screen.getByRole("dialog", { name: "本轮用量" }).textContent).toContain("12,249");
    expect(rows()).toEqual(["缓存命中=84.0%", "输入（未命中缓存）=1,200", "输入（命中缓存）=10,000", "写入缓存=700", "输出=349"]);
  });

  it("leaves out the cache rows when nothing was cached", async () => {
    render(<TurnUsage usage={{ input: 500, output: 20, cacheRead: 0, cacheWrite: 0 }} />);
    await userEvent.click(screen.getByRole("button", { name: "用量 520" }));
    expect(rows()).toEqual(["缓存命中=0.0%", "输入（未命中缓存）=500", "输出=20"]);
  });

  it("closes on Escape, giving the focus back, and on a click outside", async () => {
    render(
      <div>
        <p>outside</p>
        <TurnUsage usage={{ input: 500, output: 20, cacheRead: 0, cacheWrite: 0 }} />
      </div>,
    );
    const chip = screen.getByRole("button", { name: "用量 520" });
    await userEvent.click(chip);
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(chip);
    await userEvent.click(chip);
    await userEvent.click(screen.getByText("outside"));
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
