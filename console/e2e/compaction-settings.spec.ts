import { expect, test, type Page } from "@playwright/test";
import { login, openNewSession, OTHER_USER, USER } from "./app";

// 设置页的自动压缩点（compaction-threshold 需求 2）。e2e 服务器的本地模型地址不通，窗口用配置的 35000、预留 16384，
// 所以 50% 是 17500，其余三档封顶在 35000 − 16384 = 18616；“改小以后更早压缩”由内核测试和真实模型测试覆盖。
// 选择按用户持久保存，改设置的用例用第二个账号，结束时恢复成用满。

async function openModelPane(page: Page) {
  await page.getByRole("button", { name: "更多" }).first().click();
  await page.getByRole("menuitem", { name: "设置" }).click();
  const dialog = page.getByRole("dialog", { name: "设置" });
  await dialog.getByRole("button", { name: "模型" }).click();
  await expect(dialog.getByRole("radiogroup", { name: "自动压缩" })).toBeVisible();
  return dialog;
}

test.describe("自动压缩点", () => {
  test.afterEach(async ({ page }) => {
    await login(page, OTHER_USER);
    const response = await page.request.put("/api/settings/compaction", { data: { percent: 100 } });
    expect(response.ok(), await response.text()).toBeTruthy();
  });

  test("模型页显示窗口来源和四个选项；选了刷新后还在，另一个账号不受影响", async ({ page }) => {
    await openNewSession(page, OTHER_USER);
    const dialog = await openModelPane(page);
    await expect(dialog.getByText("来自配置（没能从模型读到）").first()).toBeVisible();
    const radios = dialog.getByRole("radiogroup", { name: "自动压缩" }).getByRole("radio");
    await expect(radios).toHaveCount(4);
    await expect(dialog.getByRole("radio", { name: "用满" })).toHaveAttribute("aria-checked", "true");
    await expect(dialog.getByRole("radio", { name: "50%" })).toContainText("17.5K");
    await expect(dialog.getByText(/从下一次提问开始生效/)).toBeVisible();

    await dialog.getByRole("radio", { name: "50%" }).click();
    await expect(dialog.getByRole("radio", { name: "50%" })).toHaveAttribute("aria-checked", "true");
    await page.reload();
    await expect((await openModelPane(page)).getByRole("radio", { name: "50%" })).toHaveAttribute("aria-checked", "true");

    await login(page, USER);
    const other = await page.request.get("/api/settings");
    expect((await other.json()).compaction.percent).toBe(100);
  });

  test("手机宽度：选项不横向滚动", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openNewSession(page, OTHER_USER);
    await page.getByRole("button", { name: "展开侧栏" }).click().catch(() => {});
    const dialog = await openModelPane(page);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    const last = await dialog.getByRole("radio", { name: "用满" }).boundingBox();
    expect(last !== null && last.x + last.width <= 390).toBe(true);
    await page.screenshot({ path: "test-results/compaction-390.png" });
  });
});
