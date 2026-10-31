import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { readTheme } from "../theme/theme";
import { SettingsDialog } from "./SettingsDialog";

afterEach(() => {
  cleanup();
  localStorage.clear();
});

const config = { provider: "claude", model: "claude-sonnet-5-5", cloud: true, contextWindow: 200000 };

describe("SettingsDialog", () => {
  it("offers three theme cards and applies the choice", async () => {
    render(<SettingsDialog open onClose={vi.fn()} config={config} username="yi" onLogout={vi.fn()} />);
    const radios = screen.getAllByRole("radio");
    expect(radios.map((radio) => radio.textContent)).toEqual(["浅色", "深色", "跟随系统"]);
    await userEvent.click(screen.getByRole("radio", { name: "深色" }));
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(readTheme()).toBe("dark");
    expect(screen.getByRole("radio", { name: "深色" }).getAttribute("aria-checked")).toBe("true");
  });

  it("shows the model read-only without any key field, and warns about the cloud", async () => {
    const { container } = render(<SettingsDialog open onClose={vi.fn()} config={config} username="yi" onLogout={vi.fn()} />);
    await userEvent.click(screen.getByRole("button", { name: "模型" }));
    expect(screen.getByText("claude-sonnet-5-5")).not.toBeNull();
    expect(screen.getByText("完整的提示词会发送到云端供应商。")).not.toBeNull();
    expect(container.querySelectorAll("input, textarea")).toHaveLength(0);
    expect(container.textContent?.toLowerCase()).not.toContain("sk-");
  });

  it("lets the user log out from the account pane", async () => {
    const onLogout = vi.fn();
    render(<SettingsDialog open onClose={vi.fn()} config={config} username="yi" onLogout={onLogout} />);
    await userEvent.click(screen.getByRole("button", { name: "账号" }));
    expect(screen.getByText("yi")).not.toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "退出登录" }));
    expect(onLogout).toHaveBeenCalledTimes(1);
  });
});
