import { expect, test } from "@playwright/test";
import { I18N_USER, openNewSession, send } from "./app";

// 本轮用量（input-area 需求 5）：回答下面有“用量 …”，点开是这一轮的明细；刷新后从 journal 还原，还在。
// E2eModel 每次调用都报用量，其中一部分算缓存命中。

test.describe("本轮用量", () => {
  test("回答下面显示用量，点开看明细，刷新后还在", async ({ page }) => {
    await openNewSession(page);
    const greeting = `你好 用量 ${Date.now()}`;
    await send(page, greeting);
    await expect(page.getByText(`你说：${greeting}`)).toBeVisible({ timeout: 30_000 });

    const chip = page.getByRole("button", { name: /^用量 [\d.]+[KM]?$/ });
    await expect(chip).toBeVisible();
    const label = await chip.textContent();
    await chip.click();
    const details = page.getByRole("dialog", { name: "本轮用量" });
    await expect(details).toContainText("缓存命中");
    await expect(details).toContainText("输入（未命中缓存）");
    await expect(details).toContainText("输入（命中缓存）");
    await expect(details).toContainText("输出");
    await page.keyboard.press("Escape");
    await expect(details).toHaveCount(0);

    await page.reload();
    await expect(page.getByText(`你说：${greeting}`)).toBeVisible();
    await expect(chip).toHaveText(label ?? "");
  });
});

// 状态行（需求 7、9）：上下文百分比点开是三项明细；开着的插件报了每日上限时，旁边有“今日 已用 / 上限”。
// 每日额度插件（dailyQuota）默认关，这里给自己打开，用完关回去；上限是 E2eServer 里的 1 亿。
test.describe("状态行", () => {
  test("上下文点开看明细；有每日上限时显示今日用量，关掉插件就不显示", async ({ page }) => {
    await openNewSession(page);
    const origin = new URL(page.url()).origin;
    const toggle = (enabled: boolean) => page.request.put("/api/plugins/plugin/dailyQuota", { data: { enabled }, headers: { origin } });
    expect((await toggle(true)).ok()).toBeTruthy();
    try {
      const greeting = `你好 状态行 ${Date.now()}`;
      await send(page, greeting);
      await expect(page.getByText(`你说：${greeting}`)).toBeVisible({ timeout: 30_000 });

      const bar = page.getByLabel("用量", { exact: true });
      const context = bar.getByRole("button", { name: /^上下文 \d+%$/ });
      await expect(context).toBeVisible();
      await context.click();
      const meter = page.getByRole("dialog", { name: /^上下文用了 \d+%$/ });
      await expect(meter).toContainText(/约 [\d,]+ \/ 35,000/);
      await expect(meter.getByRole("listitem")).toHaveText([/^系统提示约 [\d,]+$/, /^工具定义约 [\d,]+$/, /^消息约 [\d,]+$/]);
      await page.keyboard.press("Escape");
      await expect(meter).toHaveCount(0);

      await expect(bar.getByText(/^今日 [\d,]+ \/ 100,000,000$/)).toBeVisible();

      expect((await toggle(false)).ok()).toBeTruthy();
      await page.reload();
      await expect(context).toBeVisible();
      await expect(bar.getByText(/^今日 /)).toHaveCount(0);
    } finally {
      await toggle(false);
    }
  });
});

// 首屏那句话（需求 6）：管理员配的，标题不变；按界面语言换。
test.describe("首屏文字", () => {
  test("新会话显示配置的那句话，换成英文显示英文那句", async ({ page }) => {
    await openNewSession(page, I18N_USER);
    const origin = new URL(page.url()).origin;
    const language = (value: string | null) => page.request.put("/api/settings/language", { data: { language: value }, headers: { origin } });
    try {
      await expect(page.getByRole("heading", { name: "有什么可以帮你？" })).toBeVisible();
      await expect(page.getByText("这里是端到端测试用的助手，随便问。")).toBeVisible();
      expect((await language("en")).ok()).toBeTruthy();
      await page.reload();
      await expect(page.getByRole("heading", { name: "How can I help?" })).toBeVisible();
      await expect(page.getByText("This is the end-to-end test assistant. Ask anything.")).toBeVisible();
    } finally {
      await language(null);
    }
  });
});
