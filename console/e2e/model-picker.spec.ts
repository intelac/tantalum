import { expect, test, type Page } from "@playwright/test";
import { login, openNewSession, OTHER_USER, send, USER } from "./app";

// 输入框旁选模型和思考档位（model-picker 需求 3、4、8.4）。E2eServer 有两条脚本化路线：本地（默认，scripted-e2e 有“关”“开”，
// scripted-plain 没有档位）和 Claude 协议的一条（Sonnet 5.5；Sonnet 5 不支持会话中途工具变化）。“上次的选择”按用户保存，
// 改选择的用例用第二个账号，结束时恢复成默认；改工具开关的结束时也恢复。

const DEFAULT = { route: "local", model: "scripted-e2e", effort: "on" };

function pickerButton(page: Page) {
  return page.getByRole("button", { name: /^模型和思考档位/ });
}

async function openPicker(page: Page) {
  await pickerButton(page).click();
  const panel = page.getByRole("dialog", { name: "模型和思考档位" });
  await expect(panel).toBeVisible();
  return panel;
}

async function choose(page: Page, model: string, level?: string) {
  const panel = await openPicker(page);
  await panel.getByRole("button", { name: model, exact: true }).click();
  if (level !== undefined) await panel.getByRole("group", { name: "思考档位" }).getByRole("button", { name: level, exact: true }).click();
  await page.keyboard.press("Escape");
}

test.describe("选模型和思考档位", () => {
  test.afterEach(async ({ page }) => {
    await login(page, OTHER_USER);
    const reset = await page.request.put("/api/settings/model", { data: DEFAULT });
    expect(reset.ok(), await reset.text()).toBeTruthy();
    const web = await page.request.put("/api/plugins/web/web-fetch", { data: { enabled: true } });
    expect(web.ok(), await web.text()).toBeTruthy();
  });

  test("按钮写“模型 · 档位”；换成没有档位的模型时档位那一栏消失", async ({ page }) => {
    await openNewSession(page, OTHER_USER);
    await expect(pickerButton(page)).toHaveText("scripted-e2e · 开");
    let panel = await openPicker(page);
    await expect(panel.getByRole("group", { name: "思考档位" })).toBeVisible();
    await panel.getByRole("button", { name: "scripted-plain", exact: true }).click();
    await expect(pickerButton(page)).toHaveText("scripted-plain");
    panel = await openPicker(page);
    await expect(panel.getByRole("group", { name: "思考档位" })).toHaveCount(0);
    await page.screenshot({ path: "test-results/model-picker-open.png" });
  });

  test("运行中改选择写“下一次提问生效”；下一次提问后对话页出现“换成 …”", async ({ page }) => {
    await openNewSession(page, OTHER_USER);
    await send(page, "long answer please");
    await expect(page.getByRole("button", { name: "停止" })).toBeVisible();
    await choose(page, "claude-sonnet-5-5", "高");
    await expect(pickerButton(page)).toHaveText("claude-sonnet-5-5 · 高");
    await expect(page.getByText("下一次提问生效")).toBeVisible();
    await expect(page.getByRole("button", { name: "停止" })).toHaveCount(0, { timeout: 30_000 });
    await send(page, "hello again");
    await expect(page.getByRole("note").filter({ hasText: "换成 claude-sonnet-5-5 · 高" })).toBeVisible();
    await expect(page.getByText("下一次提问生效")).toHaveCount(0);
  });

  test("新会话从上次的选择开始；换一个账号是默认", async ({ page }) => {
    await openNewSession(page, OTHER_USER);
    await choose(page, "claude-sonnet-5-5", "低");
    await expect(pickerButton(page)).toHaveText("claude-sonnet-5-5 · 低");
    await page.goto("/");
    await expect(page).toHaveURL(/\/s\/s-[0-9a-f]{8}$/);
    await expect(pickerButton(page)).toHaveText("claude-sonnet-5-5 · 低");

    await openNewSession(page, USER);
    await expect(pickerButton(page)).toHaveText("scripted-e2e · 开");
  });

  test("会话一开始就关着工具时，不支持会话中途工具变化的 Claude 模型灰掉", async ({ page }) => {
    await login(page, OTHER_USER);
    const off = await page.request.put("/api/plugins/web/web-fetch", { data: { enabled: false } });
    expect(off.ok(), await off.text()).toBeTruthy();
    await openNewSession(page, OTHER_USER);
    await send(page, "hello");
    await expect(page.getByRole("button", { name: "停止" })).toHaveCount(0, { timeout: 30_000 });
    await page.reload();
    const panel = await openPicker(page);
    const sonnet5 = panel.getByRole("button", { name: "claude-sonnet-5", exact: true });
    await expect(sonnet5).toBeDisabled();
    await expect(sonnet5).toHaveAttribute("title", "这个会话中途改过工具，这个模型不支持");
    await expect(panel.getByRole("button", { name: "claude-sonnet-5-5", exact: true })).toBeEnabled();
  });

  test("刷新页面后没发出的选择还在", async ({ page }) => {
    await openNewSession(page, OTHER_USER);
    await send(page, "hello");
    await expect(page.getByRole("button", { name: "停止" })).toHaveCount(0, { timeout: 30_000 });
    await choose(page, "scripted-e2e", "关");
    await expect(page.getByText("下一次提问生效")).toBeVisible();
    await page.reload();
    await expect(pickerButton(page)).toHaveText("scripted-e2e · 关");
    await expect(page.getByText("下一次提问生效")).toBeVisible();
  });
});
