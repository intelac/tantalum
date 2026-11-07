import { expect, test, type Browser, type Page } from "@playwright/test";
import { login, openNewSession, OTHER_USER, send } from "./app";

// 等用户回答（waiting-state 需求 6.3、7.1、7.3、8.1）。E2eModel 的 ask: 剧本调用 ask_user 问“备份删还是留？”（删除 / 保留，允许补一句），
// 回答后复述“你选了：”加工具结果。问题是对话条目：刷新还在、另一个浏览器同一账号也看到；侧栏标“待回答”；取消后标“没有回答”。

async function askedQuestion(page: Page) {
  const card = page.getByRole("group", { name: "备份删还是留？" });
  await expect(card).toBeVisible({ timeout: 30_000 });
  return card;
}

async function sameAccount(browser: Browser, url: string): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await page.goto("/login");
  await login(page, OTHER_USER);
  await page.goto(url);
  return page;
}

test.describe("等用户回答", () => {
  test("问题在对话里：刷新还在，选项加文字一起交，模型复述；侧栏标待回答", async ({ page }) => {
    await openNewSession(page, OTHER_USER);
    await send(page, "ask: 整理备份");
    await askedQuestion(page);
    // 过程区标题和侧栏（侧栏按当前会话名找：当前会话是列表里选中的那一行）
    await expect(page.getByRole("button", { name: "等你回答" })).toBeVisible();
    const current = page.getByRole("listitem").filter({ has: page.locator('[aria-current="page"]') });
    await expect(current.getByLabel("待回答")).toBeVisible();

    // 刷新：问题从 journal 还原，还能答
    await page.reload();
    const card = await askedQuestion(page);
    await card.getByRole("textbox", { name: "写一句回答" }).fill("归档到 old/");
    await card.getByRole("button", { name: "保留" }).click();

    const note = page.getByRole("note", { name: "备份删还是留？" });
    await expect(note).toBeVisible({ timeout: 30_000 });
    await expect(note).toContainText("你的回答：保留 · 归档到 old/");
    await expect(page.getByText(/你选了：.*"choice":"保留".*"text":"归档到 old\/"/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("group", { name: "备份删还是留？" })).toHaveCount(0);
    await expect(current.getByLabel("待回答")).toHaveCount(0);
  });

  test("另一个浏览器同一账号也看到问题，答了以后两边都变成已回答", async ({ page, browser }) => {
    await openNewSession(page, OTHER_USER);
    await send(page, "ask: 第二个浏览器");
    await askedQuestion(page);

    const other = await sameAccount(browser, page.url());
    const card = await askedQuestion(other);
    await card.getByRole("button", { name: "删除" }).click();
    await expect(other.getByRole("note", { name: "备份删还是留？" })).toContainText("你的回答：删除", { timeout: 30_000 });
    await expect(page.getByRole("note", { name: "备份删还是留？" })).toContainText("你的回答：删除", { timeout: 30_000 });
    await expect(page.getByText(/你选了：.*"choice":"删除"/)).toBeVisible({ timeout: 30_000 });
    await other.context().close();
  });

  test("取消在等人的运行：问题标没有回答，再答是已经不在", async ({ page }) => {
    const session = await openNewSession(page, OTHER_USER);
    await send(page, "ask: 然后取消");
    await askedQuestion(page);
    await page.getByRole("button", { name: "停止" }).click();

    await expect(page.getByRole("status").filter({ hasText: "已取消" })).toBeVisible({ timeout: 30_000 });
    const note = page.getByRole("note", { name: "备份删还是留？" });
    await expect(note).toContainText("运行已取消，没有回答");
    const questions = await page.request.get(`/api/sessions/${session}/questions`);
    expect(await questions.json()).toEqual({ questions: [] });
    const late = await page.request.post(`/api/sessions/${session}/questions/ask-c1`, {
      data: { choice: "保留" },
      headers: { origin: new URL(page.url()).origin },
    });
    expect(late.status()).toBe(404);
  });
});
