import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Button } from "./Button";
import { Dialog } from "./Dialog";
import { Menu } from "./Menu";
import { Tabs } from "./Tabs";

afterEach(cleanup);

describe("Button", () => {
  it("is a real button that can be focused and activated by keyboard", async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>保存</Button>);
    const button = screen.getByRole("button", { name: "保存" });
    expect(button.getAttribute("type")).toBe("button");
    await userEvent.tab();
    expect(document.activeElement).toBe(button);
    await userEvent.keyboard("{Enter}");
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});

function TabsHarness() {
  const [selected, setSelected] = useState("conversation");
  return (
    <Tabs
      label="视图"
      selected={selected}
      onSelect={setSelected}
      tabs={[
        { id: "conversation", label: "对话" },
        { id: "trace", label: "轨迹" },
      ]}
    />
  );
}

describe("Tabs", () => {
  it("moves selection with arrow keys and keeps a single tab stop", async () => {
    render(<TabsHarness />);
    const conversation = screen.getByRole("tab", { name: "对话" });
    const trace = screen.getByRole("tab", { name: "轨迹" });
    expect(conversation.getAttribute("aria-selected")).toBe("true");
    expect(trace.getAttribute("tabindex")).toBe("-1");

    await userEvent.tab();
    expect(document.activeElement).toBe(conversation);
    await userEvent.keyboard("{ArrowRight}");
    expect(trace.getAttribute("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(trace);
    await userEvent.keyboard("{ArrowRight}");
    expect(conversation.getAttribute("aria-selected")).toBe("true");
    await userEvent.keyboard("{End}");
    expect(trace.getAttribute("aria-selected")).toBe("true");
  });

  it("selects on click", async () => {
    render(<TabsHarness />);
    await userEvent.click(screen.getByRole("tab", { name: "轨迹" }));
    expect(screen.getByRole("tab", { name: "轨迹" }).getAttribute("aria-selected")).toBe("true");
  });
});

function DialogHarness() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button onClick={() => setOpen(true)}>打开设置</Button>
      <Dialog open={open} onClose={() => setOpen(false)} title="设置">
        <input aria-label="名字" />
        <Button onClick={() => setOpen(false)}>完成</Button>
      </Dialog>
    </>
  );
}

describe("Dialog", () => {
  it("focuses its first control, closes on Escape and returns focus to the opener", async () => {
    render(<DialogHarness />);
    const opener = screen.getByRole("button", { name: "打开设置" });
    await userEvent.click(opener);
    const dialog = screen.getByRole("dialog", { name: "设置" });
    expect(dialog.getAttribute("aria-modal")).toBe("true");
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "关闭" }));

    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it("keeps Tab inside the dialog", async () => {
    render(<DialogHarness />);
    await userEvent.click(screen.getByRole("button", { name: "打开设置" }));
    const done = screen.getByRole("button", { name: "完成" });
    done.focus();
    await userEvent.tab();
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "关闭" }));
    await userEvent.tab({ shift: true });
    expect(document.activeElement).toBe(done);
  });
});

describe("Menu", () => {
  it("opens, navigates with arrows, selects, and returns focus on Escape", async () => {
    const settings = vi.fn();
    const logout = vi.fn();
    render(
      <Menu
        label="更多"
        items={[
          { id: "settings", label: "设置", onSelect: settings },
          { id: "logout", label: "退出登录", danger: true, onSelect: logout },
        ]}
      >
        更多
      </Menu>,
    );
    const trigger = screen.getByRole("button", { name: "更多" });
    expect(screen.queryByRole("menu")).toBeNull();

    await userEvent.click(trigger);
    expect(trigger.getAttribute("aria-expanded")).toBe("true");
    expect(document.activeElement).toBe(screen.getByRole("menuitem", { name: "设置" }));
    await userEvent.keyboard("{ArrowDown}");
    expect(document.activeElement).toBe(screen.getByRole("menuitem", { name: "退出登录" }));

    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).toBeNull();
    expect(document.activeElement).toBe(trigger);

    await userEvent.click(trigger);
    await userEvent.click(screen.getByRole("menuitem", { name: "设置" }));
    expect(settings).toHaveBeenCalledTimes(1);
    expect(logout).not.toHaveBeenCalled();
    expect(screen.queryByRole("menu")).toBeNull();
  });
});
