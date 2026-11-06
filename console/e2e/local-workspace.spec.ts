import { expect, test, type Page } from "@playwright/test";
import { LOCAL_USER, login, openNewSession, send } from "./app";

// local-workspace 需求 8.3：插件页生成令牌 → 测试代码用它在后台起真的连接程序（临时目录、--allow-write）→ 卡片显示已连接 →
// 对话里读、改电脑上的文件，文件真的变了，这一轮上方显示工具变化（新增）→ 撤销 → 连接程序退出 → 下一次提问显示不再可用。
// 用第三个账号：前两个账号连着 E2eServer 的测试电脑，一个用户只能连一台（L10）。
// 浏览器端的测试代码不起进程：/api/e2e/computer（只在 E2eServer 里）替它在临时目录里放好 a.txt、起 java 连接程序进程。

interface Computer {
  running: boolean;
  exitCode?: number | null;
  file?: string;
  output?: string;
}

async function computer(page: Page): Promise<Computer> {
  return (await (await page.request.get("/api/e2e/computer")).json()) as Computer;
}

async function openPlugins(page: Page) {
  await page.getByRole("button", { name: "更多" }).first().click();
  await page.getByRole("menuitem", { name: "插件" }).click();
  const dialog = page.getByRole("dialog", { name: "插件" });
  const card = dialog.getByRole("region", { name: "本机工作区" });
  await expect(card).toBeVisible();
  return card;
}

test.describe("本机工作区", () => {
  test.afterEach(async ({ page }) => {
    await login(page, LOCAL_USER);
    await page.request.delete("/api/e2e/computer");
    await page.request.delete("/api/local/tokens");
  });

  test("连接这台电脑、在电脑上改文件、撤销后工具不能用了", async ({ page }) => {
    await openNewSession(page, LOCAL_USER);
    const stamp = Date.now();
    await send(page, `hello 本机工作区 ${stamp}`);
    await expect(page.getByText(`你说：hello 本机工作区 ${stamp}`)).toBeVisible({ timeout: 30_000 });

    // 1. 插件页生成令牌（1 小时），拿到命令
    let card = await openPlugins(page);
    await expect(card.getByText(/没有连上电脑/)).toBeVisible();
    await card.getByRole("radio", { name: "1 小时" }).click();
    await card.getByRole("button", { name: "连接这台电脑" }).click();
    const command = (await card.locator("code").filter({ hasText: "TANTALUM_TOKEN=" }).textContent()) ?? "";
    const token = /TANTALUM_TOKEN=(\S+)/.exec(command)?.[1] ?? "";
    const server = /--server (\S+)/.exec(command)?.[1] ?? "";
    expect(token).toMatch(/^tc_/);
    expect(server).toMatch(/^http:\/\/127\.0\.0\.1:\d+$/);
    await expect(card.getByText(/令牌只显示这一次/)).toBeVisible();

    // 2. 后台起连接程序：令牌只放在环境变量里（L2），允许一个临时目录并且可以改文件
    const started = await page.request.post("/api/e2e/computer", { data: { server, token } });
    expect(started.ok(), await started.text()).toBeTruthy();

    // 3. 卡片显示已连接（页面每 2 秒取一次状态）
    await expect(card.getByText("已连接：E2E Mac", { exact: false })).toBeVisible({ timeout: 20_000 });
    await expect(card.getByText(/可以改文件/)).toBeVisible();
    await expect(card.getByText("连接程序没有允许：启动时加 --allow-shell 才能跑命令")).toBeVisible();
    await page.screenshot({ path: "test-results/local-workspace-card.png" });
    await page.keyboard.press("Escape");

    // 4. 对话里读、改电脑上的文件：文件真的变了；这一轮上方写明新增了工具
    await send(page, "readedit: 读 a.txt，把 hello 改成 hi");
    await expect(page.getByText(/改好了：Edited a\.txt: replaced 1 occurrence\./)).toBeVisible({ timeout: 30_000 });
    expect((await computer(page)).file).toBe("hi world\n");
    // 临时目录不是 Git 仓库，Git 工具也照样新增（B1）：一共 11 个，画面上只列前 5 个，完整名单在可访问名称里
    await expect(page.getByRole("note", { name: /git_log.*read_file/ }).filter({ hasText: /工具变化：新增 .*等 11 个/ })).toBeVisible();
    await page.screenshot({ path: "test-results/local-workspace-connected.png" });

    // 5. 撤销：连接程序退出（退出码 2，不重连），卡片变回没连上
    card = await openPlugins(page);
    await card.getByRole("button", { name: "撤销" }).click();
    await expect.poll(async () => (await computer(page)).exitCode, { timeout: 10_000 }).toBe(2);
    await expect(card.getByText(/没有连上电脑/)).toBeVisible({ timeout: 10_000 });
    const output = (await computer(page)).output ?? "";
    expect(output).toContain("a.txt");
    expect(output).not.toContain(token);
    await page.keyboard.press("Escape");

    // 下一次提问：这一轮上方写明这些工具不能用了，原因是本机工作区
    await send(page, `还在吗 ${stamp}`);
    await expect(page.getByText(`你说：还在吗 ${stamp}`)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("note", { name: /read_file/ }).filter({ hasText: /工具变化：不再可用 .*（本机工作区没有连上或没有允许）/ })).toBeVisible();
  });
});
