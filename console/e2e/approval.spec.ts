import { expect, test, type Page } from "@playwright/test";
import { login, openNewSession, OTHER_USER, send } from "./app";

// 工具审批（2026-11-06 Yi 定，A2、A4）：toolApproval 是策略插件，默认关，在插件页由用户自己打开；开着时 write_file 调用前
// 在对话页上问：允许这一次、这个会话里一直允许、拒绝。“一直允许”只管这个会话，别的会话照样问。
// E2eModel 的 approve: 剧本调用 write_file。插件开关按用户保存，用第二个账号，结束时关回去。

async function setApproval(page: Page, enabled: boolean) {
  const response = await page.request.put("/api/plugins/plugin/toolApproval", { data: { enabled } });
  expect(response.ok(), await response.text()).toBeTruthy();
}

test.describe("工具审批", () => {
  test.afterEach(async ({ page }) => {
    await login(page, OTHER_USER);
    await setApproval(page, false);
  });

  test("默认关；打开后写文件前问，一直允许只管这个会话", async ({ page }) => {
    await openNewSession(page, OTHER_USER);
    const stamp = Date.now();

    // 默认关：插件页上是关着的，可以打开
    await page.getByRole("button", { name: "更多" }).first().click();
    await page.getByRole("menuitem", { name: "插件" }).click();
    const dialog = page.getByRole("dialog", { name: "插件" });
    const toggle = dialog.getByRole("region", { name: "钩子插件" }).getByRole("switch", { name: "toolApproval" });
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await expect(toggle).toBeEnabled();
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await page.keyboard.press("Escape");

    await send(page, `approve: a${stamp}`);
    const card = page.getByRole("group", { name: "允许调用 write_file 吗？" });
    await expect(card).toBeVisible({ timeout: 30_000 });
    await expect(card.getByText(new RegExp(`e2e/approve-a${stamp}\\.txt`))).toBeVisible();
    await card.getByRole("button", { name: "这个会话里一直允许" }).click();
    await expect(page.getByText(new RegExp(`结果：Created e2e/approve-a${stamp}\\.txt`))).toBeVisible({ timeout: 30_000 });
    await expect(card).toHaveCount(0);

    // 同一个会话：不再问
    await send(page, `approve: b${stamp}`);
    await expect(page.getByText(new RegExp(`结果：Created e2e/approve-b${stamp}\\.txt`))).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("group", { name: "允许调用 write_file 吗？" })).toHaveCount(0);

    // 另一个会话照样问；拒绝时模型收到被拦下的结果
    await openNewSession(page, OTHER_USER);
    await send(page, `approve: c${stamp}`);
    const again = page.getByRole("group", { name: "允许调用 write_file 吗？" });
    await expect(again).toBeVisible({ timeout: 30_000 });
    await again.getByRole("button", { name: "拒绝" }).click();
    await expect(page.getByText(/结果：Tool call blocked: the user denied this call to write_file/)).toBeVisible({ timeout: 30_000 });
  });
});
