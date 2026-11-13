import { expect, test, type Page } from "@playwright/test";
import { composer, CONNECTOR_USER, login, openNewSession, send } from "./app";

// connector-jar（2026-11-14 Yi 定）：一个跨平台的 jar，服务器提供下载；命令每条先问；电脑上的本机 MCP 服务器。
// 用第七个账号：它的测试电脑说协议 6、Windows、PowerShell 可用、两个本机 MCP 服务器（tickets 可用，broken 出错）、
// 没有 Node.js（浏览器用不了）、版本 0.0.9 和服务器提供的 0.1.0 不一样。

async function openPlugins(page: Page) {
  await page.getByRole("button", { name: "插件", exact: true }).first().click();
  const card = page.getByRole("dialog", { name: "插件" }).getByRole("region", { name: "本机工作区" });
  await expect(card).toBeVisible();
  return card;
}

test.describe("跨平台连接程序", () => {
  test.afterEach(async ({ page }) => {
    // 命令先问恢复默认（开）
    await login(page, CONNECTOR_USER);
    await page.request.delete("/api/plugins/plugin/commandApproval");
    await page.request.delete(`/api/plugins/local/${encodeURIComponent("local-mcp:tickets")}`);
  });

  test("插件页：三步和下载、版本不一致、PowerShell、执行前先问我、本机 MCP、没有 Node.js", async ({ page }) => {
    await openNewSession(page, CONNECTOR_USER);
    const card = await openPlugins(page);

    // 版本不一致：黄色提示，按第 1 步重新下载
    await expect(card.getByRole("alert")).toContainText("这台电脑上的连接程序不是这里提供的版本", { timeout: 10_000 });
    // 第 1 步：下载连接程序；服务器真的给出文件
    const download = card.getByRole("link", { name: "下载连接程序 tantalum-connect.jar" });
    await expect(download).toHaveAttribute("href", "/api/local/connector.jar");
    const jar = await page.request.get("/api/local/connector.jar");
    expect(jar.status()).toBe(200);
    expect(jar.headers()["content-disposition"]).toContain("tantalum-connect.jar");
    await expect(card.getByText(/需要电脑上有 Java 25/)).toBeVisible();

    // 命令行：PowerShell 和它的位置，“执行前先问我”默认开
    await expect(card.getByText("PowerShell：C:\\Program Files\\PowerShell\\7\\pwsh.exe")).toBeVisible();
    const ask = card.getByRole("switch", { name: "执行前先问我" });
    await expect(ask).toHaveAttribute("aria-checked", "true");

    // 本机 MCP：两个服务器、状态、出错原因；点开工具；每个服务器一个开关
    const mcp = card.getByRole("list", { name: "本机 MCP" });
    await expect(mcp.getByText("tickets")).toBeVisible();
    await expect(mcp.getByText("可用")).toBeVisible();
    await expect(mcp.getByText("cannot start: spawn uvx ENOENT")).toBeVisible();
    await mcp.getByRole("button", { name: "1 个工具" }).click();
    await expect(mcp.getByText("find_ticket")).toBeVisible();
    // 浏览器：没有 Node.js
    await expect(card.getByText(/这台电脑上没有找到 Node.js/)).toBeVisible();
    await page.screenshot({ path: "test-results/connector-card.png", fullPage: true });

    // 关掉一个服务器：开关记下来，重新打开页面还是关的
    const tickets = mcp.getByRole("switch", { name: "本机 MCP 服务器 tickets" });
    await tickets.click();
    await expect(tickets).toHaveAttribute("aria-checked", "false");
    await page.keyboard.press("Escape");
    const again = await openPlugins(page);
    await expect(again.getByRole("switch", { name: "本机 MCP 服务器 tickets" })).toHaveAttribute("aria-checked", "false");
  });

  test("命令先问：允许、拒绝、这个会话都允许；关掉后不问", async ({ page }) => {
    await openNewSession(page, CONNECTOR_USER);
    const stamp = Date.now();
    const title = "在你的电脑上执行这条命令吗？";

    // 1. 允许：卡片写着命令全文，点“允许”后调用发到电脑上
    await send(page, `runcmd:powershell Get-ChildItem a${stamp}`);
    const card = page.getByRole("group", { name: title });
    await expect(card).toBeVisible({ timeout: 30_000 });
    await expect(card.getByText(`Get-ChildItem a${stamp}`)).toBeVisible();
    await expect(composer(page)).toBeHidden();
    await page.screenshot({ path: "test-results/connector-approval.png" });
    await card.getByRole("button", { name: "允许", exact: true }).click();
    // 测试电脑上没有真的 PowerShell：结果是“命令用不了”，说明调用确实发到了电脑上
    await expect(page.getByText(/命令结果：.*Commands are not available/)).toBeVisible({ timeout: 30_000 });

    // 2. 拒绝：命令不执行，模型收到拒绝
    await send(page, `runcmd:powershell Remove-Item b${stamp}`);
    await expect(card).toBeVisible({ timeout: 30_000 });
    await card.getByRole("button", { name: "拒绝" }).click();
    await expect(page.getByText(/命令结果：Tool call blocked: The user denied running this command/)).toBeVisible({ timeout: 30_000 });

    // 3. 这个会话都允许：这一条和以后的都不再问
    await send(page, `runcmd:powershell Get-Date c${stamp}`);
    await expect(card).toBeVisible({ timeout: 30_000 });
    await card.getByRole("button", { name: "这个会话都允许" }).click();
    await expect(page.getByText(/命令结果：.*Commands are not available/).nth(1)).toBeVisible({ timeout: 30_000 });
    await send(page, `runcmd:powershell Get-Date d${stamp}`);
    await expect(page.getByText(/命令结果：.*Commands are not available/).nth(2)).toBeVisible({ timeout: 30_000 });
    await expect(card).toHaveCount(0);

    // 4. 插件页关掉“执行前先问我”：新会话里也不问
    const plugins = await openPlugins(page);
    await plugins.getByRole("switch", { name: "执行前先问我" }).click();
    await expect(plugins.getByRole("switch", { name: "执行前先问我" })).toHaveAttribute("aria-checked", "false");
    await page.keyboard.press("Escape");
    await openNewSession(page, CONNECTOR_USER);
    await send(page, `runcmd:powershell Get-Date e${stamp}`);
    await expect(page.getByText(/命令结果：.*Commands are not available/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("group", { name: title })).toHaveCount(0);
  });

  test("本机 MCP 的工具：模型调到电脑上，结果标明是外部内容", async ({ page }) => {
    await openNewSession(page, CONNECTOR_USER);
    await send(page, "mcpcall:mcp__tickets__find_ticket");
    await expect(page.getByText(/MCP 结果：Output of a local MCP tool on the user's computer follows/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/local mcp tickets\/find_ticket answered \{"number":"42"\}/)).toBeVisible();
  });
});
