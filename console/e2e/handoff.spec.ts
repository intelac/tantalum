import { expect, test } from "@playwright/test";
import { openNewSession, send } from "./app";

// 会话之间交活在对话页上的样子。E2eModel 的 handoff: 剧本把“清点 <词>”交给一个新会话（不等结果），那个会话回答
// “清点完了，一共 42 个。”，结果回来把这边叫醒，这边回答“那边做完了。”。
// 交来的活和回来的结果都不是用户说的：不画成右边的气泡，是带边框的一块，写明是哪个会话送来的，点它能过去。

test.describe("会话之间交活", () => {
  test("回来的结果和交来的活各是一块，写明来自哪个会话，能点过去", async ({ page }) => {
    const session = await openNewSession(page);
    await send(page, "handoff: 仓库");
    await expect(page.getByText("那边做完了。")).toBeVisible({ timeout: 30_000 });

    // 这边：用户的话是气泡，回来的结果不是
    const back = page.locator("[data-from-session]");
    await expect(back).toHaveCount(1);
    await expect(back).toContainText("清点完了，一共 42 个。");
    await expect(back).not.toContainText("Answer from a conversation");
    const link = back.getByRole("button", { name: /的回话$/ });
    await expect(link).toHaveText("「清点 仓库」的回话");

    // 点过去：那个会话的开头是这边交来的活，只有任务，没有给模型看的开场白
    await link.click();
    await expect(page).not.toHaveURL(new RegExp(`/s/${session}$`));
    const handed = page.locator(`[data-from-session='${session}']`);
    await expect(handed).toHaveCount(1, { timeout: 30_000 });
    await expect(handed).toContainText("清点 仓库");
    await expect(handed).not.toContainText("This comes from another conversation");
    await expect(handed.getByRole("button", { name: /交来的活$/ })).toBeVisible();
    await expect(page.getByText("清点完了，一共 42 个。")).toBeVisible();

    // 刷新后还是这样（从记录里还原）；再点回去
    await page.reload();
    const again = page.locator(`[data-from-session='${session}']`);
    await expect(again).toHaveCount(1, { timeout: 30_000 });
    await again.getByRole("button", { name: /交来的活$/ }).click();
    await expect(page).toHaveURL(new RegExp(`/s/${session}$`));
    await expect(page.locator("[data-from-session]")).toHaveCount(1, { timeout: 30_000 });
  });
});
