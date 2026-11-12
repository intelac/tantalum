import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { WebPlugins } from "../api/types";
import { WebCard } from "./WebCard";

afterEach(cleanup);

function web(extra: Partial<WebPlugins> = {}): WebPlugins {
  return {
    switches: { web: true, "web-fetch": true },
    admin: { "web-fetch": true },
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

/** web-tools W10、需求 5.1、5.3：插件页的联网卡片。local-browser 需求 6.3、7.5：浏览器不在这张卡片上，内网那一行只说抓网页。 */
describe("WebCard", () => {
  it("shows the master and the fetch switch, both on by default, and that fetching can reach the private network", () => {
    const { region } = show(web());
    for (const name of ["联网", "抓网页"]) {
      expect(state(region, name)).toEqual({ checked: "true", disabled: false });
    }
    expect(within(region).getByText("web")).toBeTruthy();
    expect(within(region).getByText("web_fetch")).toBeTruthy();
    expect(within(region).getByText("抓网页可以访问服务器所在的内网")).toBeTruthy();
    expect(within(region).queryByText(/管理员已关闭/)).toBeNull();
  });

  it("has no browser switch any more and says where the browser went", () => {
    const { region } = show(web());
    expect(within(region).queryByRole("switch", { name: "浏览器" })).toBeNull();
    expect(within(region).getAllByRole("switch")).toHaveLength(2);
    expect(within(region).getByText(/浏览器在你自己的电脑上，见“本机工作区”/)).toBeTruthy();
  });

  it("sends the user's own switch change, and a switched-off item shows as off", async () => {
    const { region, onChange } = show(web({ switches: { web: true, "web-fetch": false } }));
    expect(state(region, "抓网页").checked).toBe("false");
    await userEvent.click(within(region).getByRole("switch", { name: "抓网页" }));
    expect(onChange).toHaveBeenCalledWith("web-fetch", true);
  });

  it("disables the child while the master is off", () => {
    const { region } = show(web({ switches: { web: false, "web-fetch": true } }));
    expect(state(region, "联网")).toEqual({ checked: "false", disabled: false });
    expect(state(region, "抓网页").disabled).toBe(true);
  });

  /** 5.3：管理员关掉抓网页：这一项禁用并写明，总开关也禁用。 */
  it("disables everything when the administrator turned fetching off", () => {
    const { region } = show(web({ admin: { "web-fetch": false } }));
    for (const name of ["联网", "抓网页"]) {
      expect(state(region, name)).toEqual({ checked: "false", disabled: true });
    }
    expect(within(region).getAllByText("管理员已关闭").length).toBeGreaterThan(0);
  });

  it("says when the administrator blocked private network addresses for fetching", () => {
    const { region } = show(web({ blockPrivateNetwork: true }));
    expect(within(region).getByText("抓网页不能访问服务器所在的内网（默认拦，管理员可改）")).toBeTruthy();
    expect(within(region).queryByText("抓网页可以访问服务器所在的内网")).toBeNull();
  });
});
