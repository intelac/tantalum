import { expect, test, type Page } from "@playwright/test";
import { openNewSession, processHeader, send } from "./app";

// 每天的笔记（memory-capture）：聊完一轮，后台自动记下要点（E2eModel 对记笔记的提示词回两条固定要点），记忆页能看、能改、能删；
// 关掉“自动记下”以后不再记。

async function openMemory(page: Page) {
  await page.getByRole("button", { name: "设置", exact: true }).first().click();
  const dialog = page.getByRole("dialog", { name: "设置" });
  await dialog.getByRole("button", { name: "记忆", exact: true }).click();
  return dialog;
}

test.describe("每天的笔记", () => {
  test("聊完一轮自动记下；能看、跳回那一轮、能改、能删", async ({ page }) => {
    await openNewSession(page);
    const prompt = `笔记测试 ${Date.now()}`;
    await send(page, prompt);
    await expect(processHeader(page, /^已完成/)).toBeVisible({ timeout: 30_000 });

    const dialog = await openMemory(page);
    const notes = dialog.getByRole("region", { name: "每天的笔记" });
    const row = notes.getByRole("button", { name: new RegExp(prompt) });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(row).toContainText("1 段");
    await row.click();
    await expect(dialog.getByText("The user tested memory notes in the end-to-end suite")).toBeVisible();
    await expect(dialog.getByRole("button", { name: "看这一轮" })).toBeVisible();

    await dialog.getByRole("button", { name: "编辑" }).click();
    const editor = dialog.getByRole("textbox", { name: "编辑" });
    await editor.fill((await editor.inputValue()).replace("下一步：看记忆页", "下一步：改过的笔记"));
    await dialog.getByRole("button", { name: "保存" }).click();
    await expect(dialog.getByText("下一步：改过的笔记")).toBeVisible();

    await dialog.getByRole("button", { name: "删除" }).click();
    const confirm = page.getByRole("dialog", { name: "删除这篇笔记？" });
    await confirm.getByRole("button", { name: "确定" }).click();
    await expect(notes.getByRole("button", { name: new RegExp(prompt) })).toHaveCount(0);
  });

  test("关掉自动记下以后，新的一轮不再记", async ({ page }) => {
    await openNewSession(page);
    let dialog = await openMemory(page);
    const toggle = dialog.getByRole("switch", { name: "自动记下" });
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await page.keyboard.press("Escape");

    const prompt = `不要记 ${Date.now()}`;
    await send(page, prompt);
    await expect(processHeader(page, /^已完成/)).toBeVisible({ timeout: 30_000 });
    await page.waitForTimeout(1500);
    dialog = await openMemory(page);
    await expect(dialog.getByRole("region", { name: "每天的笔记" }).getByRole("button", { name: new RegExp(prompt) })).toHaveCount(0);

    // 恢复，免得影响别的用例
    await dialog.getByRole("switch", { name: "自动记下" }).click();
    await expect(dialog.getByRole("switch", { name: "自动记下" })).toHaveAttribute("aria-checked", "true");
  });
});
