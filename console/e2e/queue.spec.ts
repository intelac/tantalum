import { expect, test, type Locator, type Page } from "@playwright/test";
import { composer, openNewSession, send } from "./app";

// input-area 需求 1：排队的消息可以改、可以移除、可以转成插话。
// 脚本化模型：含 "slow" 的提问慢慢答 10 秒；别的提问回 “你说：<最后一条用户消息>”。

function dock(page: Page): Locator {
  return page.getByRole("group", { name: "排队中" });
}

/** 排队一条，等它出现在队列条里再继续：上一条还没提交完时输入框不收新的。 */
async function queue(page: Page, text: string, count: number): Promise<void> {
  await send(page, text);
  await expect(composer(page)).toHaveValue("");
  await expect(dock(page)).toContainText(count === 1 ? text : `${count} 条排队中`);
}

function row(page: Page, text: string): Locator {
  return dock(page).getByRole("listitem").filter({ hasText: text });
}

test.describe("排队的消息", () => {
  test("改一条、移除一条、插话一条", async ({ page }) => {
    await openNewSession(page);
    await send(page, "slow 先慢慢答");
    await expect(page.getByText("慢慢回答第 1 段。")).toBeVisible();
    await queue(page, "hi 甲", 1);
    await queue(page, "hi 乙", 2);
    await queue(page, "hi 丙", 3);

    await dock(page).getByRole("button", { name: "展开排队的消息" }).click();
    await expect(dock(page).getByRole("listitem")).toHaveText(["hi 甲", "hi 乙", "hi 丙"]);

    await row(page, "hi 甲").getByRole("button", { name: "编辑" }).click();
    const editor = dock(page).getByRole("textbox", { name: "改排队的消息" });
    await editor.fill("hi 甲改过");
    await editor.press("Enter");
    await expect(dock(page).getByRole("listitem")).toHaveText(["hi 甲改过", "hi 乙", "hi 丙"]);

    await row(page, "hi 乙").getByRole("button", { name: "移除" }).click();
    await expect(dock(page).getByRole("listitem")).toHaveText(["hi 甲改过", "hi 丙"]);

    await row(page, "hi 丙").getByRole("button", { name: /^插话/ }).click();
    // 只剩一条：直接显示，不再折叠
    await expect(dock(page)).toContainText("hi 甲改过");
    await expect(dock(page).getByRole("button", { name: "展开排队的消息" })).toHaveCount(0);

    // 慢回答结束后先回应插话，再轮到改过的那条；移除的那条从没发出去
    await expect(page.getByText("你说：hi 丙")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("你说：hi 甲改过")).toBeVisible({ timeout: 30_000 });
    await expect(dock(page)).toHaveCount(0);
    await expect(page.getByText("hi 乙")).toHaveCount(0);

    await page.reload();
    await expect(page.getByText("你说：hi 甲改过")).toBeVisible();
    await expect(page.getByText("你说：hi 丙")).toBeVisible();
    await expect(page.getByText("hi 乙")).toHaveCount(0);
  });

  test("输入框空着按 Ctrl+Enter：排队的全部转成插话", async ({ page }) => {
    await openNewSession(page);
    await send(page, "slow 先慢慢答");
    await expect(page.getByText("慢慢回答第 1 段。")).toBeVisible();
    await queue(page, "hi 第一条", 1);
    await queue(page, "hi 第二条", 2);
    await expect(composer(page)).toHaveAttribute("placeholder", /Ctrl\/⌘\+Enter/);

    await composer(page).press("Control+Enter");
    await expect(dock(page)).toHaveCount(0);

    // 两条都并进了这一轮：模型接着回答，看到的最后一条是第二条
    await expect(page.getByText("你说：hi 第二条")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("你说：hi 第一条")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "发送" })).toBeVisible();
  });

  test("运行中 Ctrl+Enter 直接插话，不排队", async ({ page }) => {
    await openNewSession(page);
    await send(page, "slow 先慢慢答");
    await expect(page.getByText("慢慢回答第 1 段。")).toBeVisible();
    await composer(page).fill("hi 直接插话");
    await composer(page).press("Control+Enter");
    await expect(composer(page)).toHaveValue("");
    await expect(dock(page)).toHaveCount(0);
    await expect(page.getByText("你说：hi 直接插话")).toBeVisible({ timeout: 30_000 });
  });
});
