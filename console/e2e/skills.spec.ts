import { expect, test, type Locator, type Page } from "@playwright/test";
import { composer, login, openNewSession, processHeader, send, SKILL_USER } from "./app";

// 技能和知识库（skills-wiki 任务 24）：第五个账号连着一台测试电脑，电脑上有 skills/（greeting、只能手动用的 by-hand、没写描述的
// unfinished）和 wiki/（index.md 加一页）。测试电脑跑的是连接程序同一份列技能、读技能、列 wiki 页的代码。
// E2eModel 的 loadskill: 让模型自己调 skill；skillcheck 让它说出这条消息带着哪些技能。
// 目录设置按用户持久保存：每个用例开始时清掉，结束时也清掉。

async function clearDirs(page: Page) {
  for (const path of ["/api/local/skills", "/api/local/wiki"]) {
    const response = await page.request.put(path, { data: { dir: "" } });
    expect(response.ok(), await response.text()).toBeTruthy();
  }
}

async function openCard(page: Page): Promise<Locator> {
  await page.getByRole("button", { name: "插件", exact: true }).first().click();
  const card = page.getByRole("dialog", { name: "插件" }).getByRole("region", { name: "本机工作区" });
  await expect(card).toBeVisible();
  return card;
}

/** 测试电脑允许的目录：卡片上“允许的目录：…”那一行。 */
async function allowedDir(card: Locator): Promise<string> {
  const line = (await card.getByText(/^允许的目录：/).textContent()) ?? "";
  const dir = line.replace(/^允许的目录：/, "").trim();
  expect(dir).not.toBe("");
  return dir;
}

/** 目录分隔符跟着电脑走（Windows 上是反斜杠）。 */
function child(dir: string, name: string): string {
  return dir.includes("\\") ? `${dir}\\${name}` : `${dir}/${name}`;
}

async function fillDirs(page: Page): Promise<void> {
  const card = await openCard(page);
  const root = await allowedDir(card);
  const skills = card.getByLabel("技能目录");
  await expect(skills).toBeEnabled();
  await skills.fill(child(root, "skills"));
  await skills.press("Enter");
  await expect(card.getByText("找到 2 个技能")).toBeVisible();
  const wiki = card.getByLabel("wiki 目录");
  await expect(wiki).toBeEnabled();
  await wiki.fill(child(root, "wiki"));
  await wiki.press("Enter");
  await expect(card.getByText("共 1 页 · 有 index.md")).toBeVisible();
}

test.describe("技能和知识库", () => {
  test.beforeEach(async ({ page }) => {
    await login(page, SKILL_USER);
    await clearDirs(page);
  });

  test.afterEach(async ({ page }) => {
    await login(page, SKILL_USER);
    await clearDirs(page);
  });

  test("填目录、看到技能和问题、点名一个技能、模型加载一个技能、轨迹里有 wiki 段", async ({ page }) => {
    const session = await openNewSession(page, SKILL_USER);

    // 1. 没填目录：两项都说还没填，输入框打 / 没有菜单
    let card = await openCard(page);
    await expect(card.getByText("还没填目录")).toHaveCount(2);
    await page.keyboard.press("Escape");
    await composer(page).fill("/");
    await expect(page.getByRole("listbox", { name: "技能" })).toHaveCount(0);
    await composer(page).fill("");

    // 2. 填两个目录：技能列出来，没写描述的置灰写明原因；知识库写多少页、有没有 index.md
    await fillDirs(page);
    card = page.getByRole("dialog", { name: "插件" }).getByRole("region", { name: "本机工作区" });
    const list = card.getByRole("list", { name: "技能" });
    await expect(list.getByText("greeting", { exact: true })).toBeVisible();
    await expect(list.getByText("Greet people the house way.")).toBeVisible();
    await expect(list.getByText("只能手动使用")).toBeVisible();
    await expect(list.getByText("unfinished")).toBeVisible();
    await expect(list.getByText("没有写描述")).toBeVisible();
    await expect(list.getByRole("switch", { name: "技能 greeting" })).toHaveAttribute("aria-checked", "true");
    await page.screenshot({ path: "test-results/skills-plugins-desktop.png" });
    await page.keyboard.press("Escape");

    // 3. 输入框打 / 选技能：菜单里两个都在，筛选、回车填入，不发送
    await composer(page).pressSequentially("/");
    const menu = page.getByRole("listbox", { name: "技能" });
    await expect(menu.getByRole("option")).toHaveCount(2);
    await expect(menu.getByRole("option", { name: /\/by-hand/ })).toContainText("只能手动使用");
    await composer(page).pressSequentially("by");
    await expect(menu.getByRole("option")).toHaveCount(1);
    await composer(page).press("Enter");
    await expect(composer(page)).toHaveValue("/by-hand ");
    await expect(menu).toHaveCount(0);

    // 4. 发送：技能跟着消息到了模型，气泡下面有一行，点开是全文
    const stamp = Date.now();
    await composer(page).pressSequentially(`skillcheck ${stamp}`);
    await composer(page).press("Enter");
    await expect(page.getByText("消息带着技能：by-hand")).toBeVisible({ timeout: 30_000 });
    const used = page.getByRole("button", { name: "用了技能 by-hand" });
    await expect(used).toBeVisible();
    await used.click();
    await expect(page.getByLabel("技能 by-hand 的全文")).toContainText("Manual steps e2e.");

    // 5. 模型自己加载：过程区写“加载了技能 greeting”，点开是全文；标题里算“用了技能”
    await expect(processHeader(page, /^正在/)).toHaveCount(0, { timeout: 30_000 });
    await send(page, `loadskill: greeting ${stamp}`);
    await expect(page.getByText('技能结果：<skill_content name="greeting">')).toBeVisible({ timeout: 30_000 });
    await expect(processHeader(page, /^正在/)).toHaveCount(0, { timeout: 30_000 });
    const header = processHeader(page, /^已完成.*用了技能/).last();
    await expect(header).toBeVisible();
    if ((await header.getAttribute("aria-expanded")) !== "true") await header.click();
    const step = page.getByRole("button", { name: /加载了技能 greeting/ });
    await expect(step).toBeVisible();
    await step.click();
    await expect(page.getByText(/Always open with greeting-e2e-body\./).last()).toBeVisible();

    // 6. 只能手动用的技能，模型调不动：过程区写没有加载
    await send(page, `loadskill: by-hand ${stamp}`);
    await expect(page.getByText('技能结果：Error: skill "by-hand" is not available for model invocation')).toBeVisible({ timeout: 30_000 });

    // 7. 轨迹：上下文记录里有技能清单和 wiki 两段；用户输入带着点名附上的内容
    const trace = await (await page.request.get(`/api/sessions/${session}/trace`)).text();
    expect(trace).toContain("Manual steps e2e.");
    const system = (JSON.parse(trace) as { records: { kind: string; call?: string }[] }).records.find((record) => record.kind === "system");
    const input = await (await page.request.get(`/api/sessions/${session}/trace/calls/${system?.call}`)).text();
    expect(input).toContain("<available_skills>");
    expect(input).toContain("- `greeting`: Greet people the house way.");
    expect(input).not.toContain("- `by-hand`");
    expect(input).toContain("wiki-e2e-index");
    expect(input).toContain("- ops/rollback.md — Rolling back");
  });

  test("关掉一个技能：菜单里没有它，模型也加载不了；目录不在允许的范围里时写明", async ({ page }) => {
    await openNewSession(page, SKILL_USER);
    await fillDirs(page);
    const card = page.getByRole("dialog", { name: "插件" }).getByRole("region", { name: "本机工作区" });
    const greeting = card.getByRole("switch", { name: "技能 greeting" });
    await greeting.click();
    await expect(greeting).toHaveAttribute("aria-checked", "false");
    await page.keyboard.press("Escape");

    await composer(page).pressSequentially("/");
    const menu = page.getByRole("listbox", { name: "技能" });
    await expect(menu.getByRole("option")).toHaveCount(1);
    await expect(menu.getByRole("option", { name: /\/by-hand/ })).toBeVisible();
    await composer(page).fill("");
    const stamp = Date.now();
    await send(page, `loadskill: greeting ${stamp}`);
    await expect(page.getByText('技能结果：Error: skill "greeting" is unknown or no longer available')).toBeVisible({ timeout: 30_000 });
    await expect(processHeader(page, /^正在/)).toHaveCount(0, { timeout: 30_000 });

    // 重新打开，再把目录改到允许的范围之外
    const again = await openCard(page);
    await again.getByRole("switch", { name: "技能 greeting" }).click();
    await expect(again.getByRole("switch", { name: "技能 greeting" })).toHaveAttribute("aria-checked", "true");
    const root = await allowedDir(again);
    const outside = root.replace(/[\\/][^\\/]+$/, "");
    await again.getByLabel("技能目录").fill(outside);
    await again.getByLabel("技能目录").press("Enter");
    await expect(again.getByText("这个目录不在电脑允许的范围里，启动连接程序时用 --allow 加上它")).toBeVisible();
    await expect(again.getByRole("list", { name: "技能" })).toHaveCount(0);
    // 写法不对的目录不保存
    await again.getByLabel("wiki 目录").fill("relative/wiki");
    await again.getByLabel("wiki 目录").press("Enter");
    await expect(again.getByRole("alert")).toContainText("没有保存：目录要写电脑上的绝对路径");
  });

  test("手机宽度：插件页两项和输入框的菜单都放得下", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openNewSession(page, SKILL_USER);
    await page.getByRole("button", { name: "展开侧栏" }).click().catch(() => {});
    await fillDirs(page);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    const dialog = page.getByRole("dialog", { name: "插件" });
    const list = dialog.getByRole("list", { name: "技能" });
    await list.scrollIntoViewIfNeeded();
    await expect(list.getByText("Greet people the house way.")).toBeVisible();
    for (const target of [dialog.getByLabel("技能目录"), dialog.getByLabel("wiki 目录"), list]) {
      const box = await target.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(390);
    }
    await page.screenshot({ path: "test-results/skills-plugins-phone.png" });
    await page.keyboard.press("Escape");
    await expect(dialog).toHaveCount(0);
    // 手机上侧栏盖着对话：收起来再看输入框
    await page.getByRole("button", { name: "收起侧栏" }).click().catch(() => {});

    await composer(page).pressSequentially("/gr");
    const menu = page.getByRole("listbox", { name: "技能" });
    await expect(menu.getByRole("option")).toHaveCount(1);
    const box = await menu.boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(390);
    await page.screenshot({ path: "test-results/skills-menu-phone.png" });
    await composer(page).press("Tab");
    await expect(composer(page)).toHaveValue("/greeting ");
  });
});
