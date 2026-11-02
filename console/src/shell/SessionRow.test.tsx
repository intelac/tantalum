import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { SessionSummary } from "../api/types";
import { SessionRow } from "./SessionRow";

afterEach(cleanup);

const now = new Date("2026-10-31T12:00:00Z");
const base: SessionSummary = { id: "s1", title: "利息在哪里算的", lastActivity: "2026-10-31T11:55:00Z", runStatus: "idle" };

describe("SessionRow", () => {
  it("shows title and relative time when idle, and selects on click", async () => {
    const onSelect = vi.fn();
    render(
      <ul>
        <SessionRow session={base} selected={false} onSelect={onSelect} now={now} />
      </ul>,
    );
    const row = screen.getByRole("button", { name: /利息在哪里算的/ });
    expect(row.textContent).toContain("5 分钟前");
    await userEvent.click(row);
    expect(onSelect).toHaveBeenCalledWith("s1");
  });

  it("shows the running state instead of the time", () => {
    render(
      <ul>
        <SessionRow session={{ ...base, runStatus: "AwaitingModel" }} selected onSelect={vi.fn()} now={now} />
        <SessionRow session={{ ...base, id: "s3", title: "" }} selected={false} onSelect={vi.fn()} now={now} />
      </ul>,
    );
    const [running, untitled] = screen.getAllByRole("button");
    expect(running?.textContent).toContain("运行中");
    expect(running?.textContent).not.toContain("分钟前");
    expect(running?.getAttribute("aria-current")).toBe("page");
    expect(untitled?.textContent).toContain("新会话");
  });
});
