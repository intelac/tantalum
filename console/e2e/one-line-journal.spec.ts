import { expect, test, type Page } from "@playwright/test";
import { openNewSession, processHeader, send } from "./app";

// one-line-journal 需求 5、9.5：工具不能用了，旧对话照会话文件原样还原；接着问时，这一轮上面显示一行工具变化。
// E2eServer 空闲 2 秒就把会话移出内存，再打开时从会话文件载入。
const ORIGIN = { origin: "http://localhost:5174" };

async function expandProcess(page: Page, pattern: RegExp, index = 0): Promise<void> {
  const header = processHeader(page, pattern).nth(index);
  await expect(header).toBeVisible({ timeout: 30_000 });
  if ((await header.getAttribute("aria-expanded")) !== "true") await header.click();
}

/** 等这个会话离开内存：空闲够久之后在别的会话里提一个问题，回收检查随之进行。 */
async function evict(page: Page, session: string): Promise<void> {
  await page.waitForTimeout(2500);
  const other = `s-${Date.now().toString(16).slice(-8)}`;
  const nudged = await page.request.post(`/api/sessions/${other}/submissions`, {
    data: { requestId: `nudge-${Date.now()}`, mode: "prompt", text: "hello 别的会话" },
    headers: ORIGIN,
  });
  expect(nudged.ok(), await nudged.text()).toBeTruthy();
  await expect
    .poll(async () => (await (await page.request.get(`/api/e2e/resident/${session}`)).json()).resident, { timeout: 20_000 })
    .toBe(false);
}

async function grepEnabled(page: Page, enabled: boolean): Promise<void> {
  const response = await page.request.put("/api/plugins/tool/grep", { data: { enabled }, headers: ORIGIN });
  expect(response.ok(), await response.text()).toBeTruthy();
}

test.describe("工具变化", () => {
  test.afterEach(async ({ page }) => {
    await grepEnabled(page, true);
  });

  test("关掉用过的工具：旧对话不变，接着问时这一轮上面写明它不能用了", async ({ page }) => {
    const session = await openNewSession(page);
    await send(page, `tools: 找一下 hello ${Date.now()}`);
    await expandProcess(page, /^已完成/);
    await expect(page.getByRole("button", { name: /^grep/ })).toContainText("完成");

    await grepEnabled(page, false);
    await evict(page, session);
    await page.reload();
    await expandProcess(page, /^已完成/);
    await expect(page.getByRole("button", { name: /^grep/ }).first()).toContainText("完成");
    await expect(page.getByRole("note").filter({ hasText: "工具变化" })).toHaveCount(0);

    await send(page, `接着说 ${Date.now()}`);
    await expect(processHeader(page, /^已完成/).nth(1)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("note").filter({ hasText: "工具变化：不再可用 grep（已在插件页关闭）" })).toBeVisible();
    await page.screenshot({ path: "test-results/one-line-journal-tool-change.png" });

    // 再从文件打开一次，这一行还在原来的位置
    await evict(page, session);
    await page.reload();
    await expect(page.getByRole("note").filter({ hasText: "工具变化：不再可用 grep（已在插件页关闭）" })).toBeVisible();
  });
});
