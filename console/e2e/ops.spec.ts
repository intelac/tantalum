import { expect, test, type Page } from "@playwright/test";
import { login, openNewSession, processHeader, send } from "./app";

/** E2eServer 启动前写好的会话：上次进程在它的运行刚开始时被杀。 */
const CRASHED_SESSION = "s-c0ffee00";

async function expandProcess(page: Page, pattern: RegExp): Promise<void> {
  const header = processHeader(page, pattern);
  await expect(header).toBeVisible({ timeout: 30_000 });
  if ((await header.getAttribute("aria-expanded")) !== "true") await header.click();
}

test.describe("运维", () => {
  test("同一用户两个浏览器，一个退出登录，另一个照常收到推送", async ({ browser }) => {
    const first = await browser.newContext();
    const second = await browser.newContext();
    try {
      const pageA = await first.newPage();
      const pageB = await second.newPage();
      const session = await openNewSession(pageA);
      const before = `两个窗口 ${Date.now()}`;
      await send(pageA, before);
      await expect(pageA.getByText(`你说：${before}`)).toBeVisible();

      await login(pageB);
      await pageB.goto(`/s/${session}`);
      await expect(pageB.getByText(`你说：${before}`)).toBeVisible();

      await pageA.getByRole("button", { name: "更多" }).click();
      await pageA.getByRole("menuitem", { name: "退出登录" }).click();
      await expect(pageA).toHaveURL(/\/login\?next=/);

      // 退出只关掉 A 这次登录的连接：B 的回复仍然靠 SSE 推过来，也没有出现断线提示。
      const after = `退出之后 ${Date.now()}`;
      await send(pageB, after);
      await expect(pageB.getByText(`你说：${after}`)).toBeVisible();
      await expect(pageB.getByRole("status").filter({ hasText: "连接已断开" })).toHaveCount(0);
      await pageB.screenshot({ path: "test-results/ops-second-browser.png" });
    } finally {
      await first.close();
      await second.close();
    }
  });

  test("被回收出内存的会话重新打开后内容完整", async ({ page }) => {
    const session = await openNewSession(page);
    await send(page, "tools: 找一下 hello");
    await expect(processHeader(page, /^已完成/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("两个文件都读到了")).toBeVisible();

    // E2eServer 空闲 2 秒就回收。回收在 cleanroom 处理事件时顺带检查，所以空闲够久之后在别的会话里提一个问题，
    // 再等服务端确认这个会话已经不在内存里。
    await page.waitForTimeout(2500);
    const other = `s-${Date.now().toString(16).slice(-8)}`;
    const nudged = await page.request.post(`/api/sessions/${other}/submissions`, {
      data: { requestId: `nudge-${Date.now()}`, mode: "prompt", text: "hello 别的会话" },
      headers: { origin: "http://localhost:5174" },
    });
    expect(nudged.ok(), await nudged.text()).toBeTruthy();
    await expect
      .poll(async () => (await (await page.request.get(`/api/e2e/resident/${session}`)).json()).resident, { timeout: 20_000 })
      .toBe(false);

    await page.reload();
    await expect(page.getByText("tools: 找一下 hello", { exact: true }).last()).toBeVisible();
    await expect(page.getByText("两个文件都读到了")).toBeVisible();
    await expect(page.locator("code", { hasText: "hello e2e" })).toBeVisible();
    await expandProcess(page, /^已完成/);
    await expect(page.getByRole("button", { name: /思考 先用 grep 找一下 hello/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /^grep/ })).toContainText("完成");
    await expect(page.getByRole("group", { name: "2 个并行调用" }).getByRole("button", { name: /^read_file/ })).toHaveCount(2);
    await page.screenshot({ path: "test-results/ops-cold-session.png" });

    // 打开会话把它载回内存，之后还能接着用。
    const next = `回收之后 ${Date.now()}`;
    await send(page, next);
    await expect(page.getByText(`你说：${next}`)).toBeVisible();
  });

  test("崩溃时没结束的运行显示已中断，之后能接着提问", async ({ page }) => {
    await login(page);
    await page.goto(`/s/${CRASHED_SESSION}`);
    await expect(page.getByText("hello 崩溃前", { exact: true }).last()).toBeVisible();
    await expect(page.getByRole("status").filter({ hasText: "服务重启，这次运行已中断。可以接着提问" })).toBeVisible();
    // 运行在开始前就被打断，没有思考和工具，所以没有过程区，只有说明行。
    await page.screenshot({ path: "test-results/ops-crashed-run.png" });

    // 服务可能被别的测试轮次复用，这个会话里已经有之前继续过的提问；用唯一文本。
    const next = `崩溃之后 ${Date.now()}`;
    await send(page, next);
    await expect(page.getByText(`你说：${next}`)).toBeVisible();
    await expect(page.getByRole("status").filter({ hasText: "服务重启，这次运行已中断。可以接着提问" })).toBeVisible();
    await page.screenshot({ path: "test-results/ops-crashed-continued.png" });
  });
});
