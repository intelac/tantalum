import { expect, test, type Page } from "@playwright/test";
import { login, openNewSession, OTHER_USER, processHeader, send, USER } from "./app";

// 插件页（core-tools 需求 7、8）：E2eModel 的 write: 剧本只在 write_file 开着时写文件，forcewrite: 不管开没开都调用它，
// edit: 先写再改。开关按用户持久保存，改开关的用例都用第二个账号，结束时恢复。
// 文件工具在用户的电脑上执行（local-workspace）：E2eServer 给前两个账号各连了一台测试电脑；write_file 归本机工作区的“改文件”开关。

async function openPlugins(page: Page) {
  await page.getByRole("button", { name: "更多" }).first().click();
  await page.getByRole("menuitem", { name: "插件" }).click();
  const dialog = page.getByRole("dialog", { name: "插件" });
  await expect(dialog.getByRole("region", { name: "内置工具" })).toBeVisible();
  return dialog;
}

/** 运行结束后过程区收起：等最后一轮也结束（回答流式显示时标题还是“正在处理”），展开它，再点里面的步骤。 */
async function openStep(page: Page, name: RegExp) {
  await expect(processHeader(page, /^正在处理/)).toHaveCount(0, { timeout: 30_000 });
  const header = processHeader(page, /^已完成/).last();
  if ((await header.getAttribute("aria-expanded")) !== "true") await header.click();
  await page.getByRole("button", { name }).click();
}

async function setWriteFile(page: Page, enabled: boolean) {
  const response = await page.request.put("/api/plugins/local/file-edit", { data: { enabled } });
  expect(response.ok(), await response.text()).toBeTruthy();
}

test.describe("插件页", () => {
  test.afterEach(async ({ page }) => {
    await login(page, OTHER_USER);
    await setWriteFile(page, true);
  });

  test("列出内置工具、MCP 服务器和钩子插件，开关只改自己的", async ({ page }) => {
    await openNewSession(page, OTHER_USER);
    const dialog = await openPlugins(page);
    await expect(dialog.getByText("读文件")).toBeVisible();
    await expect(dialog.getByText(/从下一次提问开始可用/)).toBeVisible();
    const mcp = dialog.getByRole("region", { name: "MCP 服务器" });
    await expect(mcp.getByText("demo")).toBeVisible();
    await expect(mcp.getByText("已连接")).toBeVisible();
    await mcp.getByRole("button", { name: /个工具/ }).click();
    await expect(mcp.getByRole("switch", { name: "mcp__demo__lookup" })).toBeVisible();
    await expect(dialog.getByRole("region", { name: "钩子插件" }).getByText("citation", { exact: false }).first()).toBeVisible();

    // 文件工具不在内置工具里，在本机工作区卡片上
    await expect(dialog.getByRole("region", { name: "内置工具" }).getByText("读附件")).toBeVisible();
    const local = dialog.getByRole("region", { name: "本机工作区" });
    await expect(local.getByText(/已连接：Test Mac/)).toBeVisible();
    const write = local.getByRole("switch", { name: "改文件" });
    await expect(write).toHaveAttribute("aria-checked", "true");
    await write.click();
    await expect(write).toHaveAttribute("aria-checked", "false");
    await page.reload();
    await expect((await openPlugins(page)).getByRole("switch", { name: "改文件" })).toHaveAttribute("aria-checked", "false");

    // 另一个账号的开关不受影响
    await login(page, USER);
    const other = await page.request.get("/api/local");
    expect((await other.json()).switches["file-edit"]).toBe(true);
  });

  test("关掉的工具调用时立即被拒；下一次提问模型拿不到它；打开后下一次提问又能用", async ({ page }) => {
    await openNewSession(page, OTHER_USER);
    const stamp = Date.now();
    await setWriteFile(page, false);

    await send(page, `forcewrite: forced${stamp}`);
    await expect(page.getByText(/写入结果：This tool was turned off in the plugin page/)).toBeVisible({ timeout: 30_000 });

    await send(page, `write: off${stamp}`);
    await expect(page.getByText("我现在没有写文件的工具。")).toBeVisible({ timeout: 30_000 });

    const dialog = await openPlugins(page);
    await dialog.getByRole("switch", { name: "改文件" }).click();
    await expect(dialog.getByRole("switch", { name: "改文件" })).toHaveAttribute("aria-checked", "true");
    await page.keyboard.press("Escape");

    // 同一个会话的下一次提问就能用
    await send(page, `write: on${stamp}`);
    await expect(page.getByText(new RegExp(`写入结果：Created e2e/on${stamp}\\.txt`))).toBeVisible({ timeout: 30_000 });
    await openStep(page, new RegExp(`^write_file.*e2e/on${stamp}\\.txt`));
    await expect(page.getByText(/字节/).last()).toBeVisible();
    await expect(page.getByRole("button", { name: "展开全部 25 行" })).toBeVisible();

    // 轨迹页的上下文记录写明新增了 write_file。“改文件”开关同时管 edit_file；“本机工作区”一段也跟着变成可以改文件，
    // 所以这一行是“上下文变化：local-workspace · 新增：edit_file, write_file”
    await page.getByRole("tab", { name: "轨迹" }).click();
    await expect(page.getByText(/上下文变化：local-workspace · 新增：edit_file, write_file/).first()).toBeVisible({ timeout: 15_000 });
  });

  test("改文件在对话页显示差异，轨迹页的检查器有差异页签", async ({ page }) => {
    await openNewSession(page);
    await send(page, `edit: ${Date.now()}`);
    await expect(page.getByText(/改好了：Edited e2e\/edit\.txt: replaced 1 occurrence\./)).toBeVisible({ timeout: 30_000 });
    await openStep(page, /^edit_file.*e2e\/edit\.txt/);
    await expect(page.getByText("-beta", { exact: true })).toBeVisible();
    await expect(page.getByText("+delta", { exact: true })).toBeVisible();

    await page.getByRole("link", { name: "在轨迹中查看" }).last().click();
    const details = page.getByRole("complementary", { name: "事件详情" });
    await details.getByRole("tab", { name: "差异" }).click();
    await expect(details.getByText("+delta", { exact: true })).toBeVisible();
  });

  test("手机宽度：插件页不横向滚动，开关在每行右边", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openNewSession(page, OTHER_USER);
    await page.getByRole("button", { name: "展开侧栏" }).click().catch(() => {});
    const dialog = await openPlugins(page);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    const row = await dialog.getByRole("switch", { name: "读文件" }).boundingBox();
    expect(row !== null && row.x + row.width <= 390).toBe(true);
    await page.screenshot({ path: "test-results/plugins-390.png" });
  });
});
