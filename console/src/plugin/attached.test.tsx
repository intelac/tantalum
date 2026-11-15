import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import type { UserItem } from "../api/types";
import { UserBubble } from "../conversation/UserBubble";
import { ConversationStore } from "../store/ConversationStore";

afterEach(cleanup);

/** memory-plugin 需求 1.2：插件附的内容在气泡下面写明是哪个插件，展开看全文。 */
describe("text a plugin attached to the user's message", () => {
  it("names the plugin and opens to the plain text", async () => {
    const item: UserItem = {
      kind: "user",
      id: "3.0",
      session: "s",
      at: "2026-11-15T10:00:00Z",
      text: "今天做什么",
      queued: false,
      attached: [{ kind: "attached", plugin: "memory", text: "- 用户在做 <b>Tantalum</b>" }],
    };
    const store = new ConversationStore();
    store.applyItem(item);
    render(<UserBubble store={store} id={item.id} />);
    const line = screen.getByRole("button", { name: "插件 memory 附加了内容" });
    expect(line.getAttribute("aria-expanded")).toBe("false");
    await userEvent.click(line);
    expect(screen.getByLabelText("插件 memory 附加的内容").textContent).toBe("- 用户在做 <b>Tantalum</b>");
  });
});
