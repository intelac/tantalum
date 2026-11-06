import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { WebPlugins } from "../api/types";
import { WebCard } from "./WebCard";

afterEach(cleanup);

function web(extra: Partial<WebPlugins> = {}): WebPlugins {
  return {
    switches: { web: true, "web-fetch": true, browser: true },
    admin: { "web-fetch": true, browser: true },
    blockPrivateNetwork: false,
    ...extra,
  };
}

function show(data: WebPlugins, onChange = vi.fn()) {
  render(<WebCard web={data} onChange={onChange} />);
  return { region: screen.getByRole("region", { name: "联网" }), onChange };
}

function state(region: HTMLElement, name: string) {
  const toggle = within(region).getByRole("switch", { name });
  return { checked: toggle.getAttribute("aria-checked"), disabled: (toggle as HTMLButtonElement).disabled };
}

/** web-tools W10、需求 5.1、5.3：插件页的联网卡片。 */
describe("WebCard", () => {
  it("shows the master and two switches, all on by default, and that the private network is reachable", () => {
    const { region } = show(web());
    for (const name of ["联网", "抓网页", "浏览器"]) {
      expect(state(region, name)).toEqual({ checked: "true", disabled: false });
    }
    expect(within(region).getByText("web")).toBeTruthy();
    expect(within(region).getByText("web_fetch")).toBeTruthy();
    expect(within(region).getByText("可以访问服务器所在的内网")).toBeTruthy();
    expect(within(region).queryByText(/管理员已关闭/)).toBeNull();
  });

  it("sends the user's own switch change, and a switched-off item shows as off", async () => {
    const { region, onChange } = show(web({ switches: { web: true, "web-fetch": false, browser: true } }));
    expect(state(region, "抓网页").checked).toBe("false");
    await userEvent.click(within(region).getByRole("switch", { name: "浏览器" }));
    expect(onChange).toHaveBeenCalledWith("browser", false);
  });

  it("disables the children while the master is off", () => {
    const { region } = show(web({ switches: { web: false, "web-fetch": true, browser: true } }));
    expect(state(region, "联网")).toEqual({ checked: "false", disabled: false });
    expect(state(region, "抓网页").disabled).toBe(true);
    expect(state(region, "浏览器").disabled).toBe(true);
  });

  /** 5.3：管理员关掉的那一项禁用并写明；另一项照常。 */
  it("marks an item the administrator turned off", () => {
    const { region } = show(web({ admin: { "web-fetch": true, browser: false } }));
    expect(state(region, "浏览器")).toEqual({ checked: "false", disabled: true });
    expect(state(region, "抓网页")).toEqual({ checked: "true", disabled: false });
    expect(state(region, "联网").disabled).toBe(false);
    expect(within(region).getAllByText("管理员已关闭")).toHaveLength(1);
  });

  it("disables the master too when the administrator turned both off", () => {
    const { region } = show(web({ admin: { "web-fetch": false, browser: false } }));
    for (const name of ["联网", "抓网页", "浏览器"]) {
      expect(state(region, name)).toEqual({ checked: "false", disabled: true });
    }
  });

  it("says when the administrator blocked private network addresses", () => {
    const { region } = show(web({ blockPrivateNetwork: true }));
    expect(within(region).getByText("管理员已禁止访问内网地址")).toBeTruthy();
    expect(within(region).queryByText("可以访问服务器所在的内网")).toBeNull();
  });
});
