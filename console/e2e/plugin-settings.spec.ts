import { type Browser, expect, type Page, test } from "@playwright/test";
import { login, OTHER_USER, send, USER } from "./app";

// plugin-settings 需求 2–9：三组、参数、密码、锁定、插件默认值、运维里按人指定。
// E2eServer 为它多挂了三样：truncateOutput（用户自己的参数 maxChars）、dailyQuota（参数 tokens 只归管理员，默认关）、
// 密码测试工具 e2e_secret_echo；第二个账号的电脑上有一个 400 字符的 big.md。
// 管理员是第一个账号（e2e），被管的是第二个账号（e2e2）；每条用例结束时把改过的都恢复，别的用例不受影响。

async function signedIn(browser: Browser, user: string): Promise<Page> {
  const page = await browser.newPage();
  await login(page, user);
  await page.goto("/");
  await expect(page).toHaveURL(/\/s\/s-[0-9a-f]{8}$/);
  return page;
}

async function openPlugins(page: Page) {
  await page.getByRole("button", { name: "插件", exact: true }).first().click();
  const dialog = page.getByRole("dialog", { name: "插件" });
  await expect(dialog.getByRole("group", { name: "规则" })).toBeVisible();
  return dialog;
}

async function call(page: Page, method: "put" | "delete", path: string, data?: unknown) {
  const response = await page.request[method](path, data === undefined ? {} : { data });
  expect(response.ok(), `${method} ${path}: ${await response.text()}`).toBeTruthy();
}

/** 把两个账号在这些用例里动过的都放回去。 */
async function restore(browser: Browser) {
  const admin = await browser.newPage();
  await login(admin, USER);
  await call(admin, "put", "/api/admin/plugins/plugin/toolApproval", { enabled: null, locked: false });
  await call(admin, "put", `/api/admin/users/${OTHER_USER}/params/plugin/dailyQuota`, { values: { tokens: null } });
  await admin.close();
  const user = await browser.newPage();
  await login(user, OTHER_USER);
  await call(user, "delete", "/api/plugins/plugin/truncateOutput/params");
  await call(user, "delete", "/api/plugins/plugin/dailyQuota");
  await call(user, "delete", "/api/plugins/tool/e2e_secret_echo/params/token");
  await user.close();
}

test.describe("插件参数", () => {
  test.afterEach(async ({ browser }) => {
    await restore(browser);
  });

  test("三组；改截断的上限，下一次工具结果按新值截断；参数恢复默认后不再截", async ({ browser }) => {
    const page = await signedIn(browser, OTHER_USER);
    const stamp = Date.now();
    let dialog = await openPlugins(page);
    await expect(dialog.getByRole("group", { name: "工具", exact: true })).toBeVisible();
    const output = dialog.getByRole("group", { name: "工具输出处理" });
    await expect(output.getByRole("switch", { name: "truncateOutput" })).toHaveAttribute("aria-checked", "true");
    // 普通用户没有“插件默认值”页签
    await expect(dialog.getByRole("tab")).toHaveCount(0);

    const form = output.getByRole("form", { name: "truncateOutput 的参数" });
    const limit = form.getByLabel("工具结果最多多少字符");
    await expect(limit).toHaveValue("100000");
    // 范围外的值：服务器不收，错写在这一项下面，填的还在
    await limit.fill("5");
    await form.getByRole("button", { name: "保存 truncateOutput" }).click();
    await expect(form.getByRole("alert")).toContainText("超出了允许的范围");
    await expect(limit).toHaveValue("5");
    await limit.fill("100");
    await form.getByRole("button", { name: "保存 truncateOutput" }).click();
    await expect(form.getByText("有没保存的改动")).toHaveCount(0);
    await page.keyboard.press("Escape");

    // 超过 100 字符：留开头和结尾各一半，中间写明省掉多少（tool-scenarios-2 需求 6.4a）
    await send(page, `grepfile:big.md ${stamp}`);
    await expect(page.getByText(/找到：big\.md:1:big-e2e-head x+\s*\[\.\.\. \d+ characters omitted \.\.\.\]\s*x+$/)).toBeVisible({ timeout: 30_000 });

    // 刷新后还是 100；恢复默认后回到 100000，同一个会话里下一次就不截了
    await page.reload();
    dialog = await openPlugins(page);
    const again = dialog.getByRole("form", { name: "truncateOutput 的参数" });
    await expect(again.getByLabel("工具结果最多多少字符")).toHaveValue("100");
    await again.getByRole("button", { name: "参数恢复默认" }).click();
    await expect(again.getByLabel("工具结果最多多少字符")).toHaveValue("100000");
    await page.keyboard.press("Escape");
    await send(page, `grepfile:big.md again${stamp}`);
    await expect(page.getByText(/找到：big\.md:1:big-e2e-head x{387}$/)).toBeVisible({ timeout: 30_000 });
    await page.close();
  });

  test("密码：填了显示已设置、不回显，工具收得到；清除后工具说没设置", async ({ browser }) => {
    const page = await signedIn(browser, OTHER_USER);
    const stamp = Date.now();
    const secret = `e2e-secret-${stamp}`;
    let dialog = await openPlugins(page);
    const form = dialog.getByRole("form", { name: "e2e_secret_echo 的参数" });
    await expect(form.getByText("已设置")).toHaveCount(0);
    await form.getByRole("textbox", { name: "测试密码" }).fill(secret);
    await form.getByRole("button", { name: "保存 e2e_secret_echo" }).click();
    await expect(form.getByText("已设置")).toBeVisible();
    await expect(form.getByRole("textbox", { name: "测试密码" })).toHaveValue("");
    // 接口也不回显
    expect(await (await page.request.get("/api/plugins")).text()).not.toContain(secret);
    await page.keyboard.press("Escape");

    await send(page, `secret: ${stamp}`);
    await expect(page.getByText(`密码工具说：收到了，长度 ${secret.length}`)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(secret)).toHaveCount(0);

    dialog = await openPlugins(page);
    await dialog.getByRole("button", { name: "清除测试密码" }).click();
    await expect(dialog.getByRole("form", { name: "e2e_secret_echo 的参数" }).getByText("已设置")).toHaveCount(0);
    await page.keyboard.press("Escape");
    await send(page, `secret: again${stamp}`);
    await expect(page.getByText("密码工具说：没设置")).toBeVisible({ timeout: 30_000 });
    await page.close();
  });

  test("管理员锁定一个插件并写原因：用户看到原因、开关拨不动；解锁后又能拨", async ({ browser }) => {
    const admin = await signedIn(browser, USER);
    const defaults = await openPlugins(admin);
    await defaults.getByRole("tab", { name: "插件默认值" }).click();
    const item = defaults.getByRole("form", { name: "toolApproval", exact: true });
    await expect(item.getByText("自带的默认：关")).toBeVisible();
    await item.getByRole("switch", { name: "锁定 toolApproval" }).click();
    await item.getByLabel("锁定的原因").fill("端到端测试先不让开");
    await item.getByRole("button", { name: "保存 toolApproval" }).click();
    await expect(item.getByText("有没保存的改动")).toHaveCount(0);

    const user = await signedIn(browser, OTHER_USER);
    let dialog = await openPlugins(user);
    let rules = dialog.getByRole("group", { name: "规则" });
    // qagent-align 需求 1：锁住的项挪到“只有管理员能改”一组，原来的组里没有了
    const locked = dialog.getByRole("group", { name: "只有管理员能改" });
    await expect(locked.getByText("锁定：端到端测试先不让开")).toBeVisible();
    await expect(locked.getByRole("switch", { name: "toolApproval" })).toBeDisabled();
    await expect(rules.getByRole("switch", { name: "toolApproval" })).toHaveCount(0);
    await user.screenshot({ path: "test-results/plugins-locked-group.png" });
    await expect(dialog.getByRole("button", { name: "去插件默认值改" })).toHaveCount(0);
    // 绕过页面直接调接口也不行
    const forced = await user.request.put("/api/plugins/plugin/toolApproval", { data: { enabled: true } });
    expect(forced.status()).toBe(403);
    expect((await forced.json()).code).toBe("plugin/locked");

    // 管理员自己那一页也锁着，从那一行能跳到默认值
    await admin.getByRole("tab", { name: "我的插件" }).click();
    // 锁着的每一行都有这个按钮（内置的“新建工作区先问”必须开启，也算一行），哪个都跳到同一页
    await defaults.getByRole("button", { name: "去插件默认值改" }).first().click();
    await expect(defaults.getByRole("tab", { name: "插件默认值" })).toHaveAttribute("aria-selected", "true");
    await item.getByRole("switch", { name: "锁定 toolApproval" }).click();
    await item.getByRole("button", { name: "保存 toolApproval" }).click();
    await expect(item.getByText("有没保存的改动")).toHaveCount(0);

    await user.reload();
    dialog = await openPlugins(user);
    rules = dialog.getByRole("group", { name: "规则" });
    await expect(rules.getByRole("switch", { name: "toolApproval" })).toBeEnabled();
    await expect(rules.getByText(/^锁定：/)).toHaveCount(0);
    await expect(dialog.getByRole("group", { name: "只有管理员能改" }).getByRole("switch", { name: "toolApproval" })).toHaveCount(0);
    await admin.close();
    await user.close();
  });

  test("运维：给一个人单独定很小的每日额度，这个人的提问被拦下；改回默认后恢复", async ({ browser }) => {
    const stamp = Date.now();
    const user = await signedIn(browser, OTHER_USER);
    // 每日额度默认关，用户自己打开；额度多少他改不了
    await call(user, "put", "/api/plugins/plugin/dailyQuota", { enabled: true });
    const own = await user.request.put("/api/plugins/plugin/dailyQuota/params", { data: { values: { tokens: 999999999 } } });
    expect(own.status()).toBe(403);
    await send(user, `hello before${stamp}`);
    await expect(user.getByText(/^已完成/).first()).toBeVisible({ timeout: 30_000 });

    const admin = await signedIn(browser, USER);
    await admin.getByRole("button", { name: "设置", exact: true }).first().click();
    const settings = admin.getByRole("dialog", { name: "设置" });
    await settings.getByRole("button", { name: "运维" }).click();
    await settings.getByRole("group", { name: `${OTHER_USER} 的操作` }).getByRole("button", { name: "参数" }).click();
    const form = settings.getByRole("form", { name: "dailyQuota 的参数" });
    await expect(form.getByLabel("每天的 token 上限")).toHaveValue("100000000");
    await form.getByLabel("每天的 token 上限").fill("1");
    await form.getByRole("button", { name: "保存 dailyQuota" }).click();
    await expect(form.getByText("有没保存的改动")).toHaveCount(0);

    await send(user, `hello blocked${stamp}`);
    await expect(user.getByText(/daily token quota used up/)).toBeVisible({ timeout: 30_000 });

    await form.getByRole("button", { name: "每天的 token 上限用默认" }).click();
    await form.getByRole("button", { name: "保存 dailyQuota" }).click();
    await expect(form.getByLabel("每天的 token 上限")).toHaveValue("100000000");
    await send(user, `hello after${stamp}`);
    await expect(user.getByText(`hello after${stamp}`).first()).toBeVisible();
    await expect(user.getByText(/daily token quota used up/)).toHaveCount(1);
    await expect(user.getByText(/^已完成/)).toHaveCount(2, { timeout: 30_000 });
    await admin.close();
    await user.close();
  });

  test("运维表格：各列；在表格里给一个人单独设很小的上限，他被拦下；恢复默认后又能用", async ({ browser }) => {
    const stamp = Date.now();
    const user = await signedIn(browser, OTHER_USER);
    await call(user, "put", "/api/plugins/plugin/dailyQuota", { enabled: true });
    await send(user, `table before${stamp}`);
    await expect(user.getByText(/^已完成/).first()).toBeVisible({ timeout: 30_000 });

    const admin = await signedIn(browser, USER);
    await admin.getByRole("button", { name: "设置", exact: true }).first().click();
    const settings = admin.getByRole("dialog", { name: "设置" });
    await settings.getByRole("button", { name: "运维" }).click();
    // 只看用户表：下面还有插件用量那张表
    await expect(settings.getByRole("table").first().getByRole("columnheader")).toHaveText(["用户", /^今日用量/, "每日上限", "会话", "进行中", "操作"]);
    // 表格上面：所有人的默认上限
    await expect(settings.getByText("默认每日上限", { exact: true })).toBeVisible();
    await expect(settings.getByText("100,000,000").first()).toBeVisible();

    const row = settings.getByRole("row").filter({ has: admin.getByText(OTHER_USER, { exact: true }) });
    // 他开了每日额度、用的是默认：有百分比和进度条，标“默认”；会话数至少 1
    await expect(row.getByRole("progressbar")).toBeVisible();
    await expect(row.getByText("默认", { exact: true })).toBeVisible();
    expect(Number(await row.getByRole("cell").nth(3).textContent())).toBeGreaterThanOrEqual(1);
    // 没开每日额度的人：没有上限
    const other = settings.getByRole("row").filter({ has: admin.getByText("e2e3", { exact: true }) });
    await expect(other.getByText("没有上限")).toBeVisible();
    await admin.screenshot({ path: "test-results/ops-table.png" });

    // 就地单独设置：回车保存
    await row.getByRole("button", { name: `给 ${OTHER_USER} 单独设置每日上限` }).click();
    const field = row.getByRole("textbox", { name: `${OTHER_USER} 的每日上限` });
    await expect(field).toHaveValue("100000000");
    await field.fill("0");
    await field.press("Enter");
    await expect(row.getByRole("alert")).toHaveText("不能小于 1");
    await field.fill("1");
    await field.press("Enter");
    await expect(row.getByText("单独设置", { exact: true }).first()).toBeVisible();
    await expect(row.getByRole("progressbar")).toHaveAttribute("data-level", "over");
    await admin.screenshot({ path: "test-results/ops-table-own-limit.png" });

    await send(user, `table blocked${stamp}`);
    await expect(user.getByText(/daily token quota used up/)).toBeVisible({ timeout: 30_000 });

    // 恢复默认
    await row.getByRole("button", { name: `把 ${OTHER_USER} 的每日上限恢复默认` }).click();
    await expect(row.getByText("默认", { exact: true })).toBeVisible();
    await send(user, `table after${stamp}`);
    await expect(user.getByText(/^已完成/)).toHaveCount(2, { timeout: 30_000 });
    await expect(user.getByText(/daily token quota used up/)).toHaveCount(1);

    // 窄屏：表格在自己的容器里横向滚，页面不滚
    await admin.setViewportSize({ width: 390, height: 800 });
    expect(await admin.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    await admin.screenshot({ path: "test-results/ops-table-phone.png" });
    await admin.close();
    await user.close();
  });

  test("390 宽：三组、表单、锁定条、默认值都不横向滚动", async ({ browser }) => {
    const admin = await signedIn(browser, USER);
    await call(admin, "put", "/api/admin/plugins/plugin/toolApproval", { locked: true, reason: "窄屏上的一条比较长的锁定原因，看它会不会把这一行撑宽" });
    // 窄屏上侧栏收着，先打开框再缩窄
    const dialog = await openPlugins(admin);
    await admin.setViewportSize({ width: 390, height: 800 });
    const fits = () => admin.evaluate(() => {
      const box = document.querySelector('[role="dialog"]')!;
      const wide = [...box.querySelectorAll("*")].filter((each) => each.scrollWidth > each.clientWidth + 1 && !["visible", "hidden", "clip"].includes(getComputedStyle(each).overflowX));
      return document.documentElement.scrollWidth <= 390 && box.scrollWidth <= box.clientWidth && wide.length === 0;
    });
    await expect(dialog.getByText(/^锁定：窄屏上/)).toBeVisible();
    await expect(dialog.getByRole("form", { name: "truncateOutput 的参数" })).toBeVisible();
    expect(await fits()).toBe(true);
    await dialog.getByRole("tab", { name: "插件默认值" }).click();
    await expect(dialog.getByRole("form", { name: "dailyQuota", exact: true })).toBeVisible();
    expect(await fits()).toBe(true);
    await admin.close();
  });
});
