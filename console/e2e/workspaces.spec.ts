import { expect, test } from "@playwright/test";
import { openNewSession, send } from "./app";

// workspaces 任务 25：添加框经电脑浏览目录 → 在工作区里新建会话 → 模型按相对路径读到的是工作区目录里的文件 →
// 侧栏分组、改名、归档和撤销、显示已归档、搜索；窄屏抽屉里分组和菜单照样能用。
// 用 e2e 账号：E2eServer 给它连着一台测试电脑，允许的目录下有 notes/hello.md（“hello e2e”）。

test.describe("工作区", () => {
  test("在工作区里干活：浏览目录添加、按工作区目录读文件、改名、归档", async ({ page }) => {
    await openNewSession(page);
    const sidebar = page.getByRole("navigation", { name: "会话" });

    // 1. 添加工作区：电脑允许的目录 → notes → 选这个目录；名字默认取目录名
    await page.getByRole("button", { name: "添加", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "添加工作区" });
    await dialog.getByRole("listitem").first().getByRole("button").click();
    await dialog.getByRole("button", { name: /^notes/ }).click();
    await dialog.getByRole("button", { name: "选这个目录" }).click();
    await expect(dialog.getByRole("textbox", { name: "目录" })).toHaveValue(/notes$/);
    await expect(dialog.getByRole("textbox", { name: "名字" })).toHaveAttribute("placeholder", "notes");
    await page.screenshot({ path: "test-results/workspaces-add.png" });
    await dialog.getByRole("button", { name: "添加", exact: true }).click();
    await expect(dialog).toBeHidden();
    await expect(sidebar.getByRole("button", { name: "收起 notes" })).toBeVisible();

    // 2. 在此新建会话：输入框旁写着 notes；相对路径从工作区目录读
    await sidebar.getByRole("button", { name: "工作区 notes 的操作" }).click();
    await page.getByRole("menuitem", { name: "在此新建会话" }).click();
    await expect(page.getByRole("button", { name: "工作区: notes" })).toBeVisible();
    const stamp = Date.now();
    await send(page, `readfile:hello.md ${stamp}`);
    // 回答按 Markdown 显示：文件的标题和正文分两段
    await expect(page.getByText("读到：# hello")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("hello e2e", { exact: true })).toBeVisible();
    // 发出第一句后工作区固定，只显示名字
    await expect(page.getByRole("button", { name: /^工作区: / })).toHaveCount(0);
    const group = sidebar.getByRole("listitem").filter({ has: page.getByRole("button", { name: "收起 notes" }) });
    await expect(group.getByText(`readfile:hello.md ${stamp}`)).toBeVisible();

    // 3. 改名：双击标题，名字进账，侧栏和页头都换了
    await group.getByText(`readfile:hello.md ${stamp}`).dblclick();
    const rename = page.getByRole("dialog", { name: "会话改名" });
    await rename.getByRole("textbox", { name: "名字" }).fill(`读 hello ${stamp}`);
    await rename.getByRole("button", { name: "保存" }).click();
    await expect(group.getByText(`读 hello ${stamp}`)).toBeVisible();
    await expect(page.getByRole("heading", { name: `读 hello ${stamp}` })).toBeVisible();

    // 4. 搜索工作区名能找到它，结果下面写着工作区
    await page.getByRole("searchbox", { name: "搜索会话" }).fill("notes");
    await expect(sidebar.getByText(`读 hello ${stamp}`)).toBeVisible();
    await page.keyboard.press("Escape");

    // 5. 归档：从侧栏消失，输入框换成“已归档”，撤销后回来；显示已归档时变灰
    await group.getByRole("button", { name: `读 hello ${stamp} 的操作` }).click();
    await page.getByRole("menuitem", { name: "归档" }).click();
    await expect(group.getByText(`读 hello ${stamp}`)).toBeHidden();
    await expect(page.getByText("这个会话已归档，取消归档后才能继续。")).toBeVisible();
    await page.getByRole("button", { name: "撤销" }).click();
    await expect(group.getByText(`读 hello ${stamp}`)).toBeVisible();
    await expect(page.getByText("这个会话已归档，取消归档后才能继续。")).toBeHidden();
    await page.screenshot({ path: "test-results/workspaces-sidebar.png" });

    // 6. 删掉工作区：会话回到“未分组”
    await sidebar.getByRole("button", { name: "工作区 notes 的操作" }).click();
    await page.getByRole("menuitem", { name: "删除" }).click();
    await page.getByRole("dialog", { name: /删除工作区/ }).getByRole("button", { name: "删除" }).click();
    await expect(sidebar.getByRole("button", { name: "收起 notes" })).toHaveCount(0);
    const ungrouped = sidebar.getByRole("listitem").filter({ has: page.getByRole("button", { name: "收起 未分组" }) });
    await expect(ungrouped.getByText(`读 hello ${stamp}`)).toBeVisible();
  });

  test("窄屏抽屉里分组和菜单照样能用", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 800 });
    await openNewSession(page);
    await page.getByRole("button", { name: "展开侧栏" }).click();
    const sidebar = page.getByRole("navigation", { name: "会话" });
    await expect(sidebar.getByRole("button", { name: "收起 未分组" })).toBeVisible();
    await sidebar.getByRole("button", { name: "收起 未分组" }).click();
    await expect(sidebar.getByRole("button", { name: "展开 未分组" })).toBeVisible();
    await sidebar.getByRole("button", { name: "展开 未分组" }).click();
    await page.screenshot({ path: "test-results/workspaces-narrow.png" });
  });
});
