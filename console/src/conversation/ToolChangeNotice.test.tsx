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

  it("shows only what changed", () => {
    render(<ToolChangeNotice store={change({ gone: ["fetch_url"] })} id="4.0" />);
    expect(screen.getByRole("note").textContent).toBe("工具变化：不再可用 fetch_url（插件已删除或未加载）");
  });
});
