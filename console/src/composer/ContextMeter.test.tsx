import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { ContextMeter } from "./ContextMeter";

afterEach(cleanup);

const USAGE = { used: 48_210, window: 200_000, limit: 160_000, parts: { system: 5200, tools: 9100, messages: 33_910 } };

function meter(warn = false) {
  return render(
    <div>
      <ContextMeter label="上下文 24%" percent={24} warn={warn} hint={warn ? "快到压缩点了" : undefined} usage={USAGE} window={200_000} />
      <p>外面</p>
    </div>,
  );
}

describe("ContextMeter", () => {
  it("opens the breakdown with rounded-off wording and closes on Escape", async () => {
    meter();
    const trigger = screen.getByRole("button", { name: "上下文 24%" });
    expect(screen.queryByRole("dialog")).toBeNull();
    await userEvent.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "上下文用了 24%" });
    expect(dialog.textContent).toContain("约 48,210 / 200,000");
    const rows = Array.from(dialog.querySelectorAll("li")).map((row) => row.textContent);
    expect(rows).toEqual(["系统提示约 5,200", "工具定义约 9,100", "消息约 33,910"]);
    expect(trigger.getAttribute("aria-expanded")).toBe("true");
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it("closes when something outside is pressed", async () => {
    meter();
    await userEvent.click(screen.getByRole("button", { name: "上下文 24%" }));
    await userEvent.click(screen.getByText("外面"));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("draws the three segments as shares of the window", async () => {
    meter();
    await userEvent.click(screen.getByRole("button", { name: "上下文 24%" }));
    const widths = Array.from(screen.getByRole("dialog").querySelectorAll<HTMLElement>("div[aria-hidden] > span")).map((segment) => parseFloat(segment.style.width));
    expect(widths).toHaveLength(3);
    expect(Math.round(widths.reduce((sum, width) => sum + width, 0))).toBe(24);
  });

  it("shows only the totals when there is no breakdown", async () => {
    render(<ContextMeter label="上下文 5%" percent={5} warn={false} usage={{ used: 50 }} window={1000} />);
    await userEvent.click(screen.getByRole("button", { name: "上下文 5%" }));
    expect(screen.getByRole("dialog").textContent).toContain("约 50 / 1,000");
    expect(screen.getByRole("dialog").querySelectorAll("li")).toHaveLength(0);
  });

  it("turns red with a hint near the compaction point", () => {
    meter(true);
    const trigger = screen.getByRole("button", { name: "上下文 24%" });
    expect(trigger.className).toContain("text-danger");
    expect(trigger.getAttribute("title")).toBe("快到压缩点了");
  });
});
