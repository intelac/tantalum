import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { ToolChangeItem } from "../api/types";
import { ConversationStore } from "../store/ConversationStore";
import { ToolChangeNotice } from "./ToolChangeNotice";

afterEach(cleanup);

function change(extra: Partial<ToolChangeItem> = {}): ConversationStore {
  const store = new ConversationStore();
  store.applyItem({
    kind: "toolChange",
    id: "4.0",
    session: "s",
    run: "run-3",
    at: "2026-11-06T00:00:04Z",
    added: [],
    disabled: [],
    gone: [],
    ...extra,
  });
  return store;
}

/** one-line-journal 需求 5.1：一行写明新增、关掉的和服务器上已经没有的工具。 */
describe("ToolChangeNotice", () => {
  it("names added, turned-off and gone tools with their reasons", () => {
    render(<ToolChangeNotice store={change({ added: ["write_file"], disabled: ["grep"], gone: ["fetch_url", "browser_click"] })} id="4.0" />);
    expect(screen.getByRole("note").textContent).toBe(
      "工具变化：新增 write_file；不再可用 grep（已在插件页关闭）；不再可用 fetch_url、browser_click（插件已删除或未加载）",
    );
  });

  it("lists the first few names when a whole server went away", () => {
    const gone = ["a", "b", "c", "d", "e", "f", "g"];
    render(<ToolChangeNotice store={change({ gone })} id="4.0" />);
    expect(screen.getByRole("note").textContent).toBe("工具变化：不再可用 a、b、c、d、e 等 7 个（插件已删除或未加载）");
    expect(screen.getByRole("note").getAttribute("title")).toBe("a, b, c, d, e, f, g");
  });

  /** local-workspace：本机工作区的工具不能用了，原因是电脑没连上或没允许，不是插件页关了。 */
  it("explains tools of the local workspace separately", () => {
    render(
      <ToolChangeNotice
        store={change({
          disabled: ["memory_write", "read_file", "grep"],
          sources: { memory_write: "builtin", read_file: "local", grep: "local" },
        })}
        id="4.0"
      />,
    );
    expect(screen.getByRole("note").textContent).toBe(
      "工具变化：不再可用 memory_write（已在插件页关闭）；不再可用 read_file、grep（本机工作区没有连上或没有允许）",
    );
  });

  /** web-tools 需求 5.2：联网的工具关了，写“联网已在插件页关闭”；11 个浏览器工具合成一项。 */
  describe("web tools", () => {
    const BROWSER = [
      "browser_click", "browser_evaluate", "browser_navigate", "browser_navigate_back", "browser_press_key", "browser_select_option",
      "browser_snapshot", "browser_tabs", "browser_take_screenshot", "browser_type", "browser_wait_for",
    ];
    const web = (list: string[]) => Object.fromEntries(list.map((name) => [name, "web"]));

    it("names web_fetch alone", () => {
      render(<ToolChangeNotice store={change({ disabled: ["web_fetch"], sources: web(["web_fetch"]) })} id="4.0" />);
      expect(screen.getByRole("note").textContent).toBe("工具变化：不再可用 web_fetch（联网已在插件页关闭）");
    });

    it("folds the eleven browser tools into one item", () => {
      render(<ToolChangeNotice store={change({ disabled: BROWSER, sources: web(BROWSER) })} id="4.0" />);
      expect(screen.getByRole("note").textContent).toBe("工具变化：不再可用 浏览器（11 个工具）（联网已在插件页关闭）");
    });

    it("writes both when the whole web group is off, next to other turned-off tools", () => {
      const all = ["web_fetch", ...BROWSER];
      render(
        <ToolChangeNotice
          store={change({ disabled: ["memory_write", ...all], sources: { memory_write: "builtin", ...web(all) } })}
          id="4.0"
        />,
      );
      expect(screen.getByRole("note").textContent).toBe(
        "工具变化：不再可用 memory_write（已在插件页关闭）；不再可用 web_fetch、浏览器（11 个工具）（联网已在插件页关闭）",
      );
    });
  });

  it("shows only what changed", () => {
    render(<ToolChangeNotice store={change({ gone: ["fetch_url"] })} id="4.0" />);
    expect(screen.getByRole("note").textContent).toBe("工具变化：不再可用 fetch_url（插件已删除或未加载）");
  });
});
