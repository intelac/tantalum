import { expect, test, type Locator, type Page } from "@playwright/test";
import { BROWSER_USER, composer, login, openNewSession, send, USER } from "./app";

// 本机浏览器（local-browser 任务 20）：第六个账号连着的测试电脑说“浏览器可用：Google Chrome，有窗口”，电脑上是一个假浏览器
// （真浏览器由连接程序的 LocalBrowserIT 测）。别的账号的测试电脑没给 --allow-browser。
// E2eModel 的 browse: 让模型调 browser_navigate，capture: 让它调 browser_take_screenshot（要先 browse:，截图这类工具调过入口才有）。
// “浏览器”开关按用户持久保存：改过的用例结束时恢复。

async function setBrowser(page: Page, enabled: boolean) {
  const response = await page.request.put("/api/plugins/local/browser", { data: { enabled } });
  expect(response.ok(), await response.text()).toBeTruthy();
}

async function openCard(page: Page): Promise<Locator> {
  await page.getByRole("button", { name: "插件", exact: true }).first().click();
  const card = page.getByRole("dialog", { name: "插件" }).getByRole("region", { name: "本机工作区" });
  await expect(card).toBeVisible();
  return card;
}

test.describe("本机浏览器", () => {
  test.afterEach(async ({ page }) => {
    await login(page, BROWSER_USER);
    await setBrowser(page, true);
    await page.request.delete("/api/plugins/local/browser_navigate");
  });

  test("插件页：浏览器在本机工作区下面，写明是哪个浏览器；联网那一块里没有它；关掉后刷新还是关的", async ({ page }) => {
    await openNewSession(page, BROWSER_USER);
    const card = await openCard(page);
    const toggle = card.getByRole("switch", { name: "浏览器" });
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await expect(card.getByText("可用：Google Chrome，有窗口（用到时才打开）")).toBeVisible();
    await expect(card.getByText(/和你平时的浏览器分开/)).toBeVisible();
    const web = page.getByRole("dialog", { name: "插件" }).getByRole("region", { name: "联网" });
    await expect(web.getByRole("switch", { name: "浏览器" })).toHaveCount(0);
    await expect(web.getByText(/浏览器在你自己的电脑上/)).toBeVisible();

    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await expect(card.getByText(/^可用：Google Chrome/)).toHaveCount(0);
    await page.reload();
    const again = await openCard(page);
    await expect(again.getByRole("switch", { name: "浏览器" })).toHaveAttribute("aria-checked", "false");
    await page.screenshot({ path: "test-results/browser-plugin-card.png" });
  });

  test("电脑没给 --allow-browser：那一行写明怎么允许", async ({ page }) => {
    await openNewSession(page, USER);
    const card = await openCard(page);
    await expect(card.getByRole("switch", { name: "浏览器" })).toHaveAttribute("aria-checked", "true");
    await expect(card.getByText("连接程序没有允许：启动时加 --allow-browser 才能用浏览器")).toBeVisible();
    await expect(card.getByText(/^可用：/)).toHaveCount(0);
  });

  test("对话：模型打开网页、截图，调用转到电脑上的浏览器，结果回到对话页", async ({ page }) => {
    await openNewSession(page, BROWSER_USER);
    await send(page, "browse: the board");
    await expect(page.getByText("浏览器结果：Page: Board")).toBeVisible();
    // browser-on-demand：开始只给打开网页和标签页两个，调过以后同一轮里加上其余 9 个，对话里出一行工具变化
    await expect(page.getByText(/工具变化：新增 浏览器（9 个工具）/)).toBeVisible();
    await expect(composer(page)).toBeEnabled();
    await send(page, "capture: the board");
    await expect(page.getByText("截图结果：Screenshot of https://board.example/")).toBeVisible();
    // 这次运行的记录里有这两次调用
    const session = page.url().split("/s/")[1];
    const messages = await (await page.request.get(`/api/sessions/${session}/messages`)).text();
    expect(messages).toContain("browser_navigate");
    expect(messages).toContain("browser_take_screenshot");
    expect(messages).toContain("screenshot-board.example.png");
    await page.screenshot({ path: "test-results/browser-conversation.png" });
  });

  test("逐个勾工具：去掉 browser_navigate 的勾，模型再调它被拒；恢复默认后又能用", async ({ page }) => {
    await openNewSession(page, BROWSER_USER);
    let card = await openCard(page);
    await card.getByRole("button", { name: "已选 11 / 11 个工具" }).click();
    const list = card.getByRole("list", { name: "浏览器的工具" });
    await expect(list.getByRole("checkbox")).toHaveCount(11);
    await expect(list.getByText("在页面里执行脚本")).toBeVisible();
    // 勾选框先发请求、界面跟着变，所以点了以后等它变，不用 uncheck（它点完立刻就查）
    await list.getByRole("checkbox", { name: "browser_navigate", exact: true }).click();
    await expect(list.getByRole("checkbox", { name: "browser_navigate", exact: true })).not.toBeChecked();
    await expect(card.getByText("我改的")).toBeVisible();
    await page.screenshot({ path: "test-results/browser-tool-choices.png" });
    await page.keyboard.press("Escape");

    await send(page, "browse: the board");
    await expect(page.getByText("浏览器结果：This tool was turned off in the plugin page and is not available.")).toBeVisible();
    await expect(composer(page)).toBeEnabled();

    // 刷新后选择还在；恢复默认把勾都放回去
    await page.reload();
    card = await openCard(page);
    await expect(card.getByRole("button", { name: "已选 10 / 11 个工具" })).toBeVisible();
    await card.getByRole("button", { name: "浏览器 恢复默认" }).click();
    await expect(card.getByRole("button", { name: "已选 11 / 11 个工具" })).toBeVisible();
    await expect(card.getByText("我改的")).toHaveCount(0);
    await page.keyboard.press("Escape");
    await send(page, "browse: the board again");
    await expect(page.getByText("浏览器结果：Page: Board")).toBeVisible();
  });

  test("390px 宽：浏览器那一行不撑出横向滚动", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openNewSession(page, BROWSER_USER);
    // 手机上“插件”按钮在侧栏里：先展开
    await page.getByRole("button", { name: "展开侧栏" }).click().catch(() => {});
    const card = await openCard(page);
    const ready = card.getByText("可用：Google Chrome，有窗口（用到时才打开）");
    await ready.scrollIntoViewIfNeeded();
    await expect(ready).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    for (const target of [ready, card.getByRole("switch", { name: "浏览器" })]) {
      const box = await target.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(390);
    }
    // 展开逐个勾的工具列表也不撑出去
    await card.getByRole("button", { name: "已选 11 / 11 个工具" }).click();
    const last = card.getByRole("checkbox", { name: "browser_evaluate", exact: true });
    await last.scrollIntoViewIfNeeded();
    await expect(last).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    await page.screenshot({ path: "test-results/browser-plugin-phone.png" });
  });
});
