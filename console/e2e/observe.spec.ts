import { expect, test } from "@playwright/test";
import { openNewSession, processHeader, send } from "./app";

// 观测页面（observe-page）：E2eModel 的 trace: 剧本（先 429 再成功，一批并行工具，其中一个被拦下）跑完后，
// 从侧栏进观测页，列表里有这一轮；按工具筛；打开看调用树，第 1 步的请求有两次尝试，第一次出错；详情能跳到轨迹页。

test.describe("观测页面", () => {
  test("从侧栏进来，筛选，打开一次运行看调用树，再跳到轨迹页", async ({ page }) => {
    const session = await openNewSession(page);
    const prompt = `trace: 观测 ${Date.now()}`;
    await send(page, prompt);
    await expect(processHeader(page, /^已完成/)).toBeVisible({ timeout: 30_000 });

    await page.getByRole("button", { name: "观测", exact: true }).click();
    await expect(page).toHaveURL(/\/runs$/);
    await expect(page.getByRole("heading", { name: "观测" })).toBeVisible();
    const table = page.getByRole("table", { name: "运行列表" });
    const ours = table.getByRole("row", { name: `打开运行：${prompt}` });
    await expect(ours).toBeVisible({ timeout: 15_000 });
    await expect(ours).toContainText("完成");
    await expect(page.locator("dl[aria-label=汇总]")).toContainText("运行");

    // 按工具筛：地址跟着变，刷新后还在
    await page.getByRole("combobox", { name: "工具" }).selectOption("read_file");
    await expect(page).toHaveURL(/\/runs\?tool=read_file$/);
    await expect(ours).toBeVisible();
    await page.reload();
    await expect(page.getByRole("combobox", { name: "工具" })).toHaveValue("read_file");
    await expect(table.getByRole("row", { name: `打开运行：${prompt}` })).toBeVisible({ timeout: 15_000 });

    // 打开这次运行
    await table.getByRole("row", { name: `打开运行：${prompt}` }).click();
    await expect(page).toHaveURL(new RegExp(`/runs/${session}/${session}_\\d+$`));
    await expect(page.getByRole("heading", { name: "运行详情" })).toBeVisible();
    const tree = page.getByRole("tree", { name: "调用树" });
    await expect(tree.getByRole("treeitem", { name: /第 1 步/ }).first()).toBeVisible();
    // 第 1 步的请求重试过：展开后两次尝试，第一次出错
    const attempts = tree.locator("[role=treeitem][data-node$='#1'], [role=treeitem][data-node$='#2']");
    await expect(attempts).toHaveCount(2);
    await expect(tree.locator("[role=treeitem][data-node$='#1']")).toContainText("尝试 1");
    await page.getByRole("switch", { name: "只看出错" }).click();
    await expect(tree.locator("[role=treeitem][data-node$='#1']")).toBeVisible();
    await expect(tree.locator("[role=treeitem][data-node$='#2']")).toHaveCount(0);

    // 选中出错的尝试，看详情，跳到轨迹页
    await tree.locator("[role=treeitem][data-node$='#1']").click();
    const detail = page.getByRole("region", { name: "详情" });
    await expect(detail).toContainText("出错");
    await detail.getByRole("button", { name: "在轨迹页里看这条记录" }).click();
    await expect(page).toHaveURL(new RegExp(`/s/${session}/trace\\?record=`));
    await expect(page.getByRole("region", { name: "轨迹时间线" })).toBeVisible();
  });

  test("手机宽度：页面不横向滚动", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openNewSession(page);
    await page.goto("/runs");
    await expect(page.getByRole("heading", { name: "观测" })).toBeVisible();
    await expect(page.locator("dl[aria-label=汇总]")).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
