import { expect, test, type Page } from "@playwright/test";
import { openNewSession, processHeader, send } from "./app";

// 插件面板（plugin-panel）：示例面板“本轮概览”在沙箱 iframe 里，经消息桥拿当前会话最近一轮的调用树。
// E2eModel 的 trace: 剧本里有一个被 E2eTraceHook 拦下的工具，所以概览里有出错的一行。

function overview(page: Page) {
  return page.frameLocator("iframe[title=本轮概览]");
}

test.describe("插件面板", () => {
  test("打开本轮概览：显示最近一轮、出错标红，下一轮实时更新，点一行打开运行详情", async ({ page }) => {
    const session = await openNewSession(page);
    await send(page, `trace: 面板 ${Date.now()}`);
    await expect(processHeader(page, /^已完成/)).toBeVisible({ timeout: 30_000 });

    await page.getByRole("button", { name: "面板", exact: true }).click();
    await page.getByRole("menuitem", { name: "本轮概览" }).click();
    const frame = overview(page);
    await expect(frame.locator("#status")).toContainText("第 1 轮", { timeout: 15_000 });
    await expect(frame.locator(".node.step").first()).toContainText("第 1 步");
    await expect(frame.locator(".node.error").first()).toBeVisible();

    // 面板拿不到登录态、不能自己联网
    const panelFrame = page.frames().find((candidate) => candidate.url().includes("/panels/run-overview/"));
    expect(panelFrame).toBeDefined();
    const probe = await panelFrame!.evaluate(async () => {
      let fetched = "blocked";
      try {
        await fetch("/api/sessions");
        fetched = "allowed";
      } catch {
        fetched = "blocked";
      }
      let cookie = "denied";
      try {
        cookie = document.cookie === "" ? "empty" : "readable";
      } catch {
        cookie = "denied";
      }
      return { cookie, fetched };
    });
    // 沙箱里读 cookie 直接抛 SecurityError；fetch 被 CSP 的 connect-src 'none' 拦下
    expect(probe).toEqual({ cookie: "denied", fetched: "blocked" });

    // 下一轮：面板跟着实时轨迹更新
    await send(page, `trace: 第二轮 ${Date.now()}`);
    await expect(frame.locator("#status")).toContainText("第 2 轮", { timeout: 30_000 });

    // 点一行：控制台打开这次运行的详情
    await frame.locator(".node.step").first().click();
    await expect(page).toHaveURL(new RegExp(`/runs/${session}/${session}_\\d+$`));
    await expect(page.getByRole("tree", { name: "调用树" })).toBeVisible();
  });

  test("每个会话记住开着的面板；放大、还原、关闭", async ({ page }) => {
    const session = await openNewSession(page);
    await send(page, `trace: 记住 ${Date.now()}`);
    await expect(processHeader(page, /^已完成/)).toBeVisible({ timeout: 30_000 });
    await page.getByRole("button", { name: "面板", exact: true }).click();
    await page.getByRole("menuitem", { name: "本轮概览" }).click();
    const region = page.getByRole("region", { name: "面板：本轮概览" });
    await expect(region).toBeVisible();

    await page.reload();
    await expect(page).toHaveURL(new RegExp(`/s/${session}$`));
    await expect(region).toBeVisible();
    // 轨迹页签也开着
    await page.getByRole("tab", { name: "轨迹" }).click();
    await expect(region).toBeVisible();

    await region.getByRole("button", { name: "放大" }).click();
    await expect(page.getByRole("region", { name: "轨迹时间线" })).toBeHidden();
    await region.getByRole("button", { name: "还原" }).click();
    await expect(page.getByRole("region", { name: "轨迹时间线" })).toBeVisible();
    await region.getByRole("button", { name: "关闭面板" }).click();
    await expect(region).toHaveCount(0);
  });
});
