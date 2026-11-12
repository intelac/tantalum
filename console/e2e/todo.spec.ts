import { expect, test } from "@playwright/test";
import { openNewSession, send } from "./app";

// 待办清单（todo-tool 需求 3）。E2eModel 的 todo: 剧本写三步清单，每隔 1.2 秒更新一次（第一步完成、第二步完成、全部完成），
// 然后回答“计划做完了。”。输入框上方的计划横条跟着变；这一轮结束后还在、刷新也在；下一次提问开始时消失。

test.describe("待办清单", () => {
  test("计划横条跟着清单变，刷新还在，下一轮开始时消失", async ({ page }) => {
    await openNewSession(page);
    await send(page, "todo: 修一个测试");
    const bar = page.getByRole("region", { name: "计划" });
    await expect(bar).toContainText("0/3 已完成 · 正在：读失败的测试", { timeout: 30_000 });
    await expect(bar).toContainText("1/3 已完成 · 正在：修 apply_discount", { timeout: 30_000 });
    await expect(page.getByText("计划做完了。")).toBeVisible({ timeout: 30_000 });
    await expect(bar).toContainText("3/3 已完成 · 全部完成");
    await expect(bar.getByText("跑全部测试")).toBeVisible();

    // 收起成一行，刷新后还是收起的，清单从记录里还原
    await bar.getByRole("button", { name: "收起计划" }).click();
    await expect(bar.getByText("跑全部测试")).toHaveCount(0);
    await page.reload();
    const again = page.getByRole("region", { name: "计划" });
    await expect(again).toContainText("3/3 已完成 · 全部完成", { timeout: 30_000 });
    await expect(again.getByText("跑全部测试")).toHaveCount(0);
    await again.getByRole("button", { name: "展开计划" }).click();
    await expect(again.getByText("跑全部测试")).toBeVisible();

    // 下一次提问：横条消失，直到模型再写
    await send(page, "hello again");
    await expect(page.getByRole("region", { name: "计划" })).toHaveCount(0, { timeout: 30_000 });
  });

  test("手机宽度下横条不撑出横向滚动", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 800 });
    await openNewSession(page);
    await send(page, "todo: 窄屏");
    await expect(page.getByRole("region", { name: "计划" })).toContainText("3/3 已完成", { timeout: 30_000 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
