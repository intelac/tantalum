import { expect, test } from "@playwright/test";
import { openNewSession, processHeader, send } from "./app";

async function expand(page: import("@playwright/test").Page): Promise<void> {
  const header = processHeader(page, /^已完成/);
  await expect(header).toBeVisible({ timeout: 30_000 });
  if ((await header.getAttribute("aria-expanded")) !== "true") await header.click();
}

test.describe("MCP 工具", () => {
  test("调用显示在工具链里，刷新后还在", async ({ page }) => {
    await openNewSession(page);
    await send(page, `external: 查一下工单 ${Date.now()}`);
    await expect(page.getByText("外部系统查到了负责人。")).toBeVisible({ timeout: 30_000 });
    await expand(page);

    const row = page.getByRole("button", { name: /^mcp__demo__lookup/ });
    await expect(row).toContainText("完成");
    await row.click();
    await expect(page.locator("pre", { hasText: "e2e-owner" })).toBeVisible();

    await page.reload();
    await expect(page.getByText("外部系统查到了负责人。")).toBeVisible();
    await expand(page);
    await expect(page.getByRole("button", { name: /^mcp__demo__lookup/ })).toContainText("完成");
  });

  test("未放行的工具调用失败，错误显示在工具链里", async ({ page }) => {
    await openNewSession(page);
    await send(page, `forbidden: 全部删掉 ${Date.now()}`);
    await expect(page.getByText("这个工具不可用。")).toBeVisible({ timeout: 30_000 });
    await expand(page);
    const row = page.getByRole("button", { name: /^mcp__demo__delete_all/ });
    await row.click();
    await expect(page.locator("pre", { hasText: "Unknown tool" })).toBeVisible();
  });
});
