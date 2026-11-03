import { expect, test } from "@playwright/test";
import { openNewSession, processHeader, send } from "./app";

// 统计页签：E2eModel 的 trace: 剧本（429 重试、并行工具、插件工作）跑完后，按轮次看上下文、缓存和时间去向。

test.describe("统计页签", () => {
  test("每轮一行，展开看每一步；和轨迹页互相切换", async ({ page }) => {
    const session = await openNewSession(page);
    await send(page, `trace: 统计 ${Date.now()}`);
    await expect(processHeader(page, /^已完成/)).toBeVisible({ timeout: 30_000 });

    await page.getByRole("tab", { name: "统计" }).click();
    await expect(page).toHaveURL(new RegExp(`/s/${session}/stats$`));
    await expect(page.getByRole("tab", { name: "统计" })).toHaveAttribute("aria-selected", "true");
    const table = page.getByRole("table", { name: "按轮次的统计" });
    const turn = table.getByRole("row", { name: "展开或折叠第 1 轮的每一步" });
    await expect(turn).toContainText("用户提问");
    await expect(turn).toContainText("trace: 统计");
    await expect(turn).toContainText("2 个步骤");

    await turn.click();
    await expect(table.getByRole("row").filter({ hasText: /^第 1 步/ })).toContainText("read_file ×2, demo/lookup");
    await expect(table.getByRole("row").filter({ hasText: /^第 2 步/ })).toContainText("问候在 notes/hello.md:3");
    // 第 1 步的请求重试过一次，模型用时是两次尝试之和，首 token 和速度取最后一次。
    await expect(table.getByRole("row").filter({ hasText: /^第 1 步/ })).toContainText(/毫秒|秒/);
    await expect(table.getByRole("row").filter({ hasText: /^合计/ })).toBeVisible();

    await page.getByRole("tab", { name: "轨迹" }).click();
    await expect(page.getByRole("region", { name: "轨迹时间线" })).toBeVisible();
    await page.getByRole("tab", { name: "对话" }).click();
    await expect(page.getByRole("tab", { name: "对话" })).toHaveAttribute("aria-selected", "true");
  });

  test("手机宽度：表格在自己的区域里横向滚动，页面不横向滚动", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openNewSession(page);
    await send(page, `trace: 手机 ${Date.now()}`);
    await expect(processHeader(page, /^已完成/)).toBeVisible({ timeout: 30_000 });
    await page.getByRole("tab", { name: "统计" }).click();
    await expect(page.getByRole("table", { name: "按轮次的统计" })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    await page.screenshot({ path: "test-results/stats-390.png" });
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.getByRole("row", { name: "展开或折叠第 1 轮的每一步" }).click();
    await page.screenshot({ path: "test-results/stats-1280.png" });
  });
});
