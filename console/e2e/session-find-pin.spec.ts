import { expect, test } from "@playwright/test";
import { openNewSession, send } from "./app";

// session-find-pin 任务 7：会话菜单的置顶和复制会话号（R1、R2）、按对话内容搜会话（R3）、轮次导航（R4）。

test.describe("会话：置顶、复制会话号、按内容搜、轮次导航", () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test("置顶排到组顶、复制会话号、按对话内容搜到", async ({ page, context }) => {
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    const stamp = Date.now();
    const first = await openNewSession(page);
    await send(page, `first ${stamp}`);
    await expect(page.getByText(`你说：first ${stamp}`)).toBeVisible({ timeout: 30_000 });
    // 第二句只在内容里，不在标题里
    await send(page, `needle${stamp} in the middle`);
    await expect(page.getByText(`你说：needle${stamp} in the middle`)).toBeVisible({ timeout: 30_000 });
    await openNewSession(page);
    await send(page, `second ${stamp}`);
    await expect(page.getByText(`你说：second ${stamp}`)).toBeVisible({ timeout: 30_000 });

    const sidebar = page.getByRole("complementary", { name: "会话" });
    const rows = sidebar.getByRole("button", { name: new RegExp(`(first|second) ${stamp}(?! 的操作)`) });
    await expect(rows.first()).toHaveAccessibleName(new RegExp(`second ${stamp}`));

    // 1. 置顶：排到前面，标题前有图钉
    await sidebar.getByText(`first ${stamp}`).hover();
    await sidebar.getByRole("button", { name: `first ${stamp} 的操作` }).click();
    await page.getByRole("menuitem", { name: "置顶" }).click();
    await expect(rows.first()).toHaveAccessibleName(new RegExp(`first ${stamp}`));
    await expect(rows.first().getByRole("img", { name: "已置顶" })).toBeVisible();

    // 2. 复制会话号：剪贴板里是会话 ID，行里短暂显示“已复制”
    await sidebar.getByText(`first ${stamp}`).hover();
    await sidebar.getByRole("button", { name: `first ${stamp} 的操作` }).click();
    await page.getByRole("menuitem", { name: "复制会话号" }).click();
    await expect(rows.first()).toContainText("已复制");
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(first);

    // 3. 按内容搜：标题里没有的词也找得到，下面显示命中的片段
    await page.getByRole("searchbox", { name: "搜索会话" }).fill(`needle${stamp}`);
    const hit = sidebar.getByRole("button", { name: new RegExp(`first ${stamp}(?! 的操作)`) });
    await expect(hit).toBeVisible();
    await expect(hit).toContainText(`needle${stamp} in the middle`);
    await page.screenshot({ path: "test-results/session-content-search.png" });
    await page.getByRole("button", { name: "清除搜索" }).click();

    // 4. 取消置顶：回到按最近活动排
    await sidebar.getByText(`first ${stamp}`).hover();
    await sidebar.getByRole("button", { name: `first ${stamp} 的操作` }).click();
    await page.getByRole("menuitem", { name: "取消置顶" }).click();
    await expect(rows.first()).toHaveAccessibleName(new RegExp(`second ${stamp}`));
  });

  test("轮次导航：点刻度跳到那一轮，悬停看提问，窄了就不显示", async ({ page }) => {
    await openNewSession(page);
    const stamp = Date.now();
    const tall = "\n" + "line\n".repeat(25);
    for (const n of [1, 2, 3]) {
      await send(page, `turn ${n} ${stamp}${tall}`);
      await expect(page.getByText(`你说：turn ${n} ${stamp}`)).toBeVisible({ timeout: 30_000 });
    }
    const rail = page.getByRole("navigation", { name: "轮次" });
    await expect(rail.getByRole("button", { name: /^跳到第 \d 轮/ })).toHaveCount(3);
    await expect(rail.getByRole("button", { name: `跳到第 3 轮：turn 3 ${stamp}` })).toHaveAttribute("aria-current", "location");

    const firstBubble = page.locator("[data-turn]").first();
    await expect(firstBubble).not.toBeInViewport();
    await rail.getByRole("button", { name: `跳到第 1 轮：turn 1 ${stamp}` }).hover();
    await expect(page.getByRole("tooltip")).toHaveText(`turn 1 ${stamp}`);
    await page.screenshot({ path: "test-results/turn-rail.png" });
    await rail.getByRole("button", { name: `跳到第 1 轮：turn 1 ${stamp}` }).click();
    await expect(firstBubble).toBeInViewport();
    await page.mouse.move(10, 10);
    await expect(rail.getByRole("button", { name: `跳到第 1 轮：turn 1 ${stamp}` })).toHaveAttribute("aria-current", "location");

    await page.setViewportSize({ width: 880, height: 900 });
    await expect(rail).toBeHidden();
  });
});
