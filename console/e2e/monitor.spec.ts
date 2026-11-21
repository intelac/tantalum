import { expect, test } from "@playwright/test";
import { openNewSession, processHeader, send } from "./app";

// 实时监视（live-monitor）：示例面板“实时监视”订阅流水线的阶段消息，事件走到哪个组件，那个组件就亮起来。

test.describe("实时监视面板", () => {
  test("提问后光点走过流水线：模型节点亮过，顶部写着这一步；暂停和继续", async ({ page }) => {
    await openNewSession(page);
    await page.getByRole("button", { name: "面板", exact: true }).click();
    await page.getByRole("menuitem", { name: "实时监视" }).click();
    const frame = page.frameLocator("iframe[title=实时监视]");
    await expect(frame.locator("#empty")).toContainText("等待事件");

    await send(page, `你好 ${Date.now()}`);
    await expect(processHeader(page, /^已完成/)).toBeVisible({ timeout: 30_000 });
    // 动画按到达顺序播放：最后停在推送或别的阶段上；走过的组件都标成 done 或 cur
    await expect(frame.locator("#empty")).toBeHidden({ timeout: 15_000 });
    await expect(frame.locator("g.node.done, g.node.cur")).not.toHaveCount(0);
    await expect.poll(async () => frame.locator("g.node.done, g.node.cur").allTextContents(), { timeout: 15_000 })
      .toEqual(expect.arrayContaining([expect.stringContaining("调模型"), expect.stringContaining("内核走一步"), expect.stringContaining("写 journal")]));
    await expect(frame.locator("#now")).not.toBeEmpty();

    await frame.getByRole("button", { name: "暂停" }).click();
    await expect(frame.getByRole("button", { name: "继续" })).toBeVisible();
    await frame.getByRole("button", { name: "继续" }).click();
    await expect(frame.getByRole("button", { name: "暂停" })).toBeVisible();
  });

  test("放出任务时亮的是“任务”节点，不是“执行工具”", async ({ page }) => {
    await openNewSession(page);
    await page.getByRole("button", { name: "面板", exact: true }).click();
    await page.getByRole("menuitem", { name: "实时监视" }).click();
    const frame = page.frameLocator("iframe[title=实时监视]");
    await expect(frame.locator("#empty")).toContainText("等待事件");

    // E2eModel 的 tasks: 剧本放出一个任务，这一轮没有调别的工具
    await send(page, "tasks: subtask: billing 模块");
    const lit = () => frame.locator("g.node.done, g.node.cur").allTextContents();
    await expect.poll(lit, { timeout: 30_000 }).toEqual(expect.arrayContaining([expect.stringContaining("DeliveryHandler")]));
    expect((await lit()).some((text) => text.includes("ToolHandler"))).toBe(false);
  });
});
