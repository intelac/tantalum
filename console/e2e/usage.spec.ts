import { expect, test } from "@playwright/test";
import { openNewSession, send } from "./app";

// 本轮用量（input-area 需求 5）：回答下面有“用量 …”，点开是这一轮的明细；刷新后从 journal 还原，还在。
// E2eModel 每次调用都报用量，其中一部分算缓存命中。

test.describe("本轮用量", () => {
  test("回答下面显示用量，点开看明细，刷新后还在", async ({ page }) => {
    await openNewSession(page);
    const greeting = `你好 用量 ${Date.now()}`;
    await send(page, greeting);
    await expect(page.getByText(`你说：${greeting}`)).toBeVisible({ timeout: 30_000 });

    const chip = page.getByRole("button", { name: /^用量 [\d.]+[KM]?$/ });
    await expect(chip).toBeVisible();
    const label = await chip.textContent();
    await chip.click();
    const details = page.getByRole("dialog", { name: "本轮用量" });
    await expect(details).toContainText("缓存命中");
    await expect(details).toContainText("输入（未命中缓存）");
    await expect(details).toContainText("输入（命中缓存）");
    await expect(details).toContainText("输出");
    await page.keyboard.press("Escape");
    await expect(details).toHaveCount(0);

    await page.reload();
    await expect(page.getByText(`你说：${greeting}`)).toBeVisible();
    await expect(chip).toHaveText(label ?? "");
  });
});
