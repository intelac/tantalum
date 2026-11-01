import { expect, test } from "@playwright/test";
import { openNewSession, send } from "./app";

test.describe("记忆", () => {
  test("模型写入的记忆在设置里能看、能读、能删", async ({ page }) => {
    await openNewSession(page);
    await send(page, `remember: 记住这件事 ${Date.now()}`);
    await expect(page.getByText("已记住。")).toBeVisible({ timeout: 30_000 });

    // 设置在侧栏“更多”菜单里。
    await page.getByRole("button", { name: "更多" }).click();
    await page.getByRole("menuitem", { name: "设置" }).click();
    const dialog = page.getByRole("dialog", { name: "设置" });
    await dialog.getByRole("button", { name: "记忆" }).click();

    await expect(dialog.getByText("已用 1/200 条")).toBeVisible();
    const row = dialog.getByRole("button", { name: /e2e-fact/ });
    await expect(row).toContainText("e2e 测试写入的记忆");
    await row.click();
    await expect(dialog.getByRole("heading", { name: "e2e", level: 2 })).toBeVisible();
    await expect(dialog.getByText("这是脚本化模型写入的记忆正文。")).toBeVisible();

    await dialog.getByRole("button", { name: "删除" }).click();
    const confirm = page.getByRole("dialog", { name: "删除这条记忆？" });
    await expect(confirm).toBeVisible();
    await confirm.getByRole("button", { name: "确定" }).click();

    await expect(dialog.getByText(/还没有记忆/)).toBeVisible();
    await expect(dialog.getByText("已用 0/200 条")).toBeVisible();
  });
});
