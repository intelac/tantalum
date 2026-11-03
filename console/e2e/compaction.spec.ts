import { expect, test, type Page } from "@playwright/test";
import { login, openNewSession, send } from "./app";

// 脚本化模型：含 "long" 的提问得到约 12 KB 的回答（估算 4000 token）；e2e 服务器的窗口是 32000，阈值 15616，
// 保留 3904，所以第四五轮之间会自动压缩。摘要调用返回固定的 "## Goal" 文本。

async function longTurn(page: Page, n: number): Promise<void> {
  await send(page, `long 第 ${n} 轮`);
  await expect(page.getByText(`长回答结束：long 第 ${n} 轮`)).toBeVisible({ timeout: 30_000 });
}

test.describe("上下文压缩", () => {
  test("长对话自动压缩：分隔标记、可展开的摘要、刷新后仍在、轨迹里有压缩步骤", async ({ page }) => {
    const session = await openNewSession(page);
    for (let n = 1; n <= 5; n++) {
      await longTurn(page, n);
    }
    const mark = page.getByRole("button", { name: /^上下文已压缩 .* → .* tok$/ });
    await expect(mark.first()).toBeVisible({ timeout: 30_000 });
    await mark.first().click();
    await expect(page.getByRole("heading", { name: "Goal" })).toBeVisible();
    await expect(page.getByText(/用户在和脚本化模型聊天/)).toBeVisible();

    // 原始条目都还在：五个问题、五个长回答。
    await expect(page.getByText(/^长回答结束：long 第 \d 轮$/)).toHaveCount(5);

    await page.reload();
    await expect(page.getByRole("button", { name: /^上下文已压缩/ }).first()).toBeVisible();
    await expect(page.getByText(/^长回答结束：long 第 \d 轮$/)).toHaveCount(5);

    await page.getByRole("tab", { name: "轨迹" }).click();
    await expect(page).toHaveURL(new RegExp(`/s/${session}/trace`));
    // 自动压缩在轨迹里是一个压缩组：摘要请求加一条“已压缩”记录，带前后估算。
    const compacted = page.locator("[role=table] [role=row][data-record^='k:']").first();
    await expect(compacted).toContainText(/上下文已压缩 · .+ → .+/, { timeout: 15_000 });
    await expect(page.locator("[role=table] [role=row]", { hasText: /请求 #\d+ · 压缩/ }).first()).toBeVisible();
  });

  test("回复被截断有提示", async ({ page }) => {
    await openNewSession(page);
    await send(page, "truncate 试一下");
    await expect(page.getByText("这段回复会被截")).toBeVisible();
    await expect(page.getByRole("note").filter({ hasText: "回复被截断" })).toBeVisible();
  });

  test("状态栏手动压缩：按钮在运行中不可用，空闲时点击后出现手动标记", async ({ page }) => {
    await openNewSession(page);
    const button = page.getByRole("button", { name: "压缩上下文" });
    await send(page, "slow 先慢慢答");
    await expect(button).toBeDisabled();
    await expect(page.getByText("慢慢回答第 40 段。")).toBeVisible({ timeout: 30_000 });
    await longTurn(page, 1);
    await longTurn(page, 2);
    await expect(button).toBeEnabled();
    await button.click();
    await expect(page.getByRole("button", { name: /^上下文已压缩 .* · 手动$/ })).toBeVisible({ timeout: 30_000 });
  });

  test("500 条消息的会话：多次压缩，打开只载最近一页，向上滚动逐页加载且不跳", async ({ page }) => {
    test.setTimeout(300_000);
    await login(page);
    const origin = new URL(page.url().startsWith("http") ? page.url() : "http://localhost:5174").origin;
    const request = page.request;   // 带着登录 Cookie 的请求上下文
    const session = `s-${Date.now().toString(16).slice(-8)}`;
    for (let n = 1; n <= 250; n++) {
      const response = await request.post(`/api/sessions/${session}/submissions`, {
        data: { requestId: `r-${session}-${n}`, mode: "prompt", text: `第 ${n} 条 ${"填充 ".repeat(40)}` },
        headers: { origin },
      });
      expect(response.ok(), await response.text()).toBeTruthy();
      await expect
        .poll(
          async () => {
            const runs = (await (await request.get(`/api/sessions/${session}/runs`)).json()) as { runs?: { endReason?: string }[] };
            return runs.runs?.length === n && runs.runs[n - 1]?.endReason !== undefined;
          },
          { timeout: 20_000 },
        )
        .toBe(true);
    }
    await page.goto(`/s/${session}`);
    await expect(page.getByText(/^你说：第 250 条/)).toBeVisible({ timeout: 30_000 });
    const asked = page.getByText(/^你说：第 \d+ 条/);
    const firstPage = await asked.count();
    expect(firstPage, "only the latest page is loaded").toBeLessThan(250);
    await expect(page.getByText(/^你说：第 1 条/)).toHaveCount(0);

    // 反复滚到顶部加载更早的页。每次加载后，原来最上面的那条提问还在原来的位置：不跳。
    const scroller = page.locator("div.overflow-y-auto").first();
    for (let pages = 0; (await page.getByText(/^你说：第 1 条/).count()) === 0; pages++) {
      expect(pages, "the first message never showed up").toBeLessThan(20);
      const loadedBefore = await asked.count();
      const top = await asked.first().elementHandle();
      const yAtTop = await scroller.evaluate((element, anchor) => {
        element.scrollTop = 0;
        return (anchor as HTMLElement).getBoundingClientRect().top;
      }, top);
      await expect.poll(() => asked.count(), { timeout: 10_000 }).toBeGreaterThan(loadedBefore);
      await page.waitForTimeout(100);
      const yAfter = await top!.evaluate((anchor) => (anchor as HTMLElement).getBoundingClientRect().top);
      expect(Math.abs(yAfter - yAtTop), "the view jumped while an older page was inserted").toBeLessThan(2);
    }

    const texts = await asked.allTextContents();
    const numbers = texts.map((text) => Number(/第 (\d+) 条/.exec(text)![1]));
    expect(new Set(numbers).size, "no message twice").toBe(numbers.length);
    expect(numbers.length).toBe(250);
    expect(numbers).toEqual([...numbers].sort((a, b) => a - b));
    const marks = page.getByRole("button", { name: /^上下文已压缩/ });
    expect(await marks.count()).toBeGreaterThanOrEqual(2);
    await page.screenshot({ path: "test-results/paging-top.png" });
  });
});
