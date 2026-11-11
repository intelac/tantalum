import { expect, test, type Page } from "@playwright/test";
import { composer, login, openNewSession, OTHER_USER, send } from "./app";

// 工具审批（2026-11-06 Yi 定，A2、A4）：toolApproval 是策略插件，默认关，在插件页由用户自己打开；开着时 write_file 调用前
// 在对话页上问：允许这一次、这个会话里一直允许、拒绝。“一直允许”只管这个会话，别的会话照样问。
// 问的时候批准卡片顶替输入框（input-area 需求 2）：对话里只有一行“等待批准”，回车选第一个，Esc 拒绝。
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
    await page.getByRole("button", { name: "插件", exact: true }).first().click();
    const dialog = page.getByRole("dialog", { name: "插件" });
    const toggle = dialog.getByRole("region", { name: "规则" }).getByRole("switch", { name: "toolApproval" });
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await expect(toggle).toBeEnabled();
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await page.keyboard.press("Escape");

    await send(page, `approve: a${stamp}`);
    const card = page.getByRole("group", { name: "允许调用 write_file 吗？" });
    await expect(card).toBeVisible({ timeout: 30_000 });
    await expect(card.getByText(new RegExp(`e2e/approve-a${stamp}\\.txt`))).toBeVisible();
    // 卡片顶替输入框；对话里只有一行，不重复画按钮
    await expect(card).toContainText("等待批准");
    await expect(card).toContainText("write_file");
    await expect(composer(page)).toBeHidden();
    await expect(page.getByRole("note").filter({ hasText: "等待批准 · 允许调用 write_file 吗？" })).toBeVisible();
    await expect(page.getByRole("button", { name: "这个会话里一直允许" })).toHaveCount(1);
    await card.getByRole("button", { name: "这个会话里一直允许" }).click();
    await expect(page.getByText(new RegExp(`结果：Created e2e/approve-a${stamp}\\.txt`))).toBeVisible({ timeout: 30_000 });
    await expect(card).toHaveCount(0);
    await expect(composer(page)).toBeVisible();

    // 同一个会话：不再问
    await send(page, `approve: b${stamp}`);
    await expect(page.getByText(new RegExp(`结果：Created e2e/approve-b${stamp}\\.txt`))).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("group", { name: "允许调用 write_file 吗？" })).toHaveCount(0);

    // 另一个会话照样问；拒绝时模型收到被拦下的结果
    await openNewSession(page, OTHER_USER);
    await send(page, `approve: c${stamp}`);
    const again = page.getByRole("group", { name: "允许调用 write_file 吗？" });
    await expect(again).toBeVisible({ timeout: 30_000 });
    await expect(again).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(page.getByText(/结果：Tool call blocked: the user denied this call to write_file/)).toBeVisible({ timeout: 30_000 });

    // 回车选第一个（允许这一次）：下一次还会问
    await send(page, `approve: d${stamp}`);
    await expect(again).toBeFocused({ timeout: 30_000 });
    await page.keyboard.press("Enter");
    await expect(page.getByText(new RegExp(`结果：Created e2e/approve-d${stamp}\\.txt`))).toBeVisible({ timeout: 30_000 });
  });
});
