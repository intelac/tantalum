import { expect, test, type Page } from "@playwright/test";
import { login, openNewSession, processHeader, send } from "./app";

// record-results 需求 11.4：恢复只读结果行，不重算。E2eServer 空闲 2 秒就把会话移出内存，
// 再打开时从会话文件载入，所以“移出内存后重新打开”就是冷载入。
const CRASHED_TOOL_SESSION = "s-c0ffee01";
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

test.describe("结果行", () => {
  test.afterEach(async ({ page }) => {
    await page.request.post("/api/e2e/scope/false", { headers: ORIGIN });
  });

  test("钩子的规则改了，旧对话不变，新规则只管之后的调用", async ({ page }) => {
    const session = await openNewSession(page);
    await send(page, `tools: scope: 找一下 hello ${Date.now()}`);
    await expect(processHeader(page, /^已完成/)).toBeVisible({ timeout: 30_000 });
    await expandProcess(page, /^已完成/);
    await expect(page.getByRole("button", { name: /^grep/ })).toContainText("完成");

    // 部署了新规则：grep 不在允许范围里。会话离开内存后再打开，从文件恢复。
    expect((await page.request.post("/api/e2e/scope/true", { headers: ORIGIN })).ok()).toBeTruthy();
    await evict(page, session);
    await page.reload();
    await expandProcess(page, /^已完成/);
    await expect(page.getByRole("button", { name: /^grep/ }).first()).toContainText("完成");
    await page.screenshot({ path: "test-results/record-results-old-rule.png" });

    // 之后的调用按新规则：这次的 grep 被拦下，旧的那次仍是完成。
    await send(page, `tools: scope: 再找一下 hello ${Date.now()}`);
    await expect(processHeader(page, /^已完成/).nth(1)).toBeVisible({ timeout: 30_000 });
    await expandProcess(page, /^已完成/, 1);
    await expect(page.getByRole("button", { name: /^grep/ }).nth(1)).toContainText("已拦截");
    await expect(page.getByRole("button", { name: /^grep/ }).first()).toContainText("完成");
    await page.screenshot({ path: "test-results/record-results-new-rule.png" });
  });

  test("进程死在工具执行途中：重启后这次调用记为结果未知，运行因重启中断", async ({ page }) => {
    await login(page);
    await page.goto(`/s/${CRASHED_TOOL_SESSION}`);
    await expect(page.getByText("读一下 notes/hello.md", { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("status").filter({ hasText: "服务重启，这次运行已中断。可以接着提问" }).first()).toBeVisible();
    await expandProcess(page, /已中断/);
    const call = page.getByRole("button", { name: /^read_file/ }).first();
    await call.click();
    await expect(page.getByText("结果未知：工具可能已执行").first()).toBeVisible();
    await page.screenshot({ path: "test-results/record-results-crashed-tool.png" });
  });

  test("执行轨迹页在内存里和从文件恢复后显示相同", async ({ page }) => {
    const session = await openNewSession(page);
    await send(page, `trace: 读一下文件 ${Date.now()}`);
    await expect(processHeader(page, /^已完成/)).toBeVisible({ timeout: 30_000 });
    await page.getByRole("tab", { name: "轨迹" }).click();
    const rows = page.locator("[role=table] [role=row][data-record]");
    await expect(rows.filter({ hasText: "插件工作" }).first()).toBeVisible();
    const live = await rows.allTextContents();

    await evict(page, session);
    await page.reload();
    await expect(rows.filter({ hasText: "插件工作" }).first()).toBeVisible();
    expect(await rows.allTextContents()).toEqual(live);
    await page.screenshot({ path: "test-results/record-results-trace.png" });
  });
});
