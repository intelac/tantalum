import { act, cleanup, fireEvent, render, renderHook, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { PanelInfo } from "../api/types";
import { TraceStore } from "../trace/TraceStore";
import { PanelLayout } from "./PanelLayout";
import { PanelMenu } from "./PanelMenu";
import { useOpenPanel } from "./usePanels";

afterEach(() => {
  cleanup();
  window.localStorage.clear();
});

const PANELS: PanelInfo[] = [
  { name: "run-overview", title: { en: "Run overview", zh: "本轮概览" }, icon: "list-tree", url: "/panels/run-overview/index.html" },
  { name: "probe", title: "Probe", icon: "activity", url: "/panels/probe/index.html" },
];

function Harness({ session = "s1" }: { session?: string }) {
  const { panel, open } = useOpenPanel(session, PANELS);
  const [maximized, setMaximized] = useState(false);
  return (
    <>
      <PanelMenu panels={PANELS} open={panel?.name ?? null} onOpen={open} />
      <PanelLayout
        panel={panel}
        session={session}
        trace={new TraceStore(() => new Promise(() => {}))}
        maximized={maximized}
        onMaximize={setMaximized}
        onClose={() => open(null)}
      >
        <p>conversation</p>
      </PanelLayout>
    </>
  );
}

describe("插件面板区", () => {
  it("菜单列出面板，选一个在右边打开：沙箱 iframe 只给脚本权限；放大时对话让开，Esc 关闭", async () => {
    render(<Harness />);
    await userEvent.click(screen.getByRole("button", { name: "面板" }));
    await userEvent.click(screen.getByRole("menuitem", { name: "本轮概览" }));
    const frame = screen.getByTitle("本轮概览") as HTMLIFrameElement;
    expect(frame.getAttribute("sandbox")).toBe("allow-scripts");
    expect(frame.getAttribute("src")).toBe("/panels/run-overview/index.html");
    expect(screen.getByRole("region", { name: "面板：本轮概览" })).toBeTruthy();
    expect(screen.getByRole("separator", { name: "调整面板宽度" })).toBeTruthy();

    await userEvent.click(screen.getByRole("button", { name: "放大" }));
    expect(screen.getByText("conversation").closest("[hidden]")).not.toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "还原" }));
    expect(screen.getByText("conversation").closest("[hidden]")).toBeNull();

    fireEvent.keyDown(screen.getByRole("region", { name: "面板：本轮概览" }), { key: "Escape" });
    expect(screen.queryByRole("region", { name: "面板：本轮概览" })).toBeNull();
  });

  it("每个会话记住开着哪个面板；不在能用的列表里时当作没开", () => {
    const first = renderHook(() => useOpenPanel("s1", PANELS));
    act(() => first.result.current.open("probe"));
    expect(first.result.current.panel?.name).toBe("probe");
    expect(renderHook(() => useOpenPanel("s1", PANELS)).result.current.panel?.name).toBe("probe");
    expect(renderHook(() => useOpenPanel("s2", PANELS)).result.current.panel).toBeUndefined();
    expect(renderHook(() => useOpenPanel("s1", PANELS.slice(0, 1))).result.current.panel).toBeUndefined();
  });

  it("没有面板时不显示按钮", () => {
    render(<PanelMenu panels={[]} open={null} onOpen={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "面板" })).toBeNull();
  });
});
