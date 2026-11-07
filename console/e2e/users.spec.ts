import { type Browser, expect, type Page, test } from "@playwright/test";
import { PASSWORD, USER, login } from "./app";

// ops-hardening-2 需求 3：管理员在设置页“用户”里加人、重置密码；新用户自己改密码；非管理员看不到“用户”页。
// e2e 服务器的第一个账号（e2e）是管理员。新建的用户名带时间戳，重跑不冲突；只改新用户的密码，别的用例不受影响。

async function openSettings(page: Page) {
  await page.goto("/");
  await expect(page).toHaveURL(/\/s\/s-[0-9a-f]{8}$/);
  await page.getByRole("button", { name: "更多" }).click();
  await page.getByRole("menuitem", { name: "设置" }).click();
  return page.getByRole("dialog", { name: "设置" });
}

/** 用一个全新的浏览器上下文试登录：登录会换掉上下文里原来的 Cookie，不能借用正在用的页面。 */
async function canLogin(browser: Browser, username: string, password: string): Promise<boolean> {
  const context = await browser.newContext();
  try {
    const response = await context.request.post("/api/auth/login", {
      data: { username, password },
      headers: { origin: "http://localhost:5174" },
    });
    return response.ok();
  } finally {
    await context.close();
  }
}

test.describe("用户管理", () => {
  test("管理员新建用户、重置密码；新用户看不到“用户”页，能改自己的密码", async ({ browser }) => {
    const name = `e2e-new-${Date.now()}`;
    const admin = await browser.newPage();
    await login(admin, USER);
    const settings = await openSettings(admin);
    await settings.getByRole("button", { name: "用户" }).click();
    await expect(settings.getByRole("group", { name: `${USER} 的操作` })).toBeVisible();
    // 对自己不能停用、不能取消管理员
    const mine = settings.getByRole("group", { name: `${USER} 的操作` });
    await expect(mine.getByRole("button", { name: "停用" })).toBeDisabled();
    await expect(mine.getByRole("button", { name: "取消管理员" })).toBeDisabled();

    await settings.getByRole("button", { name: "新建用户" }).click();
    await settings.getByLabel("名字").fill(name);
    await settings.getByLabel("初始密码").fill("first-password");
    await settings.getByRole("button", { name: "创建" }).click();
    await expect(settings.getByText(`已创建 ${name}。`)).toBeVisible();
    await expect(settings.getByRole("group", { name: `${name} 的操作` })).toBeVisible();

    // 新用户：能登录，设置里没有“用户”页，账号页能改自己的密码
    expect(await canLogin(browser, name, "first-password")).toBe(true);
    const user = await browser.newPage();
    await user.goto("/login");
    await user.request.post("/api/auth/login", { data: { username: name, password: "first-password" }, headers: { origin: new URL(user.url()).origin } });
    const theirs = await openSettings(user);
    await expect(theirs.getByRole("button", { name: "账号" })).toBeVisible();
    await expect(theirs.getByRole("button", { name: "用户" })).toHaveCount(0);
    await theirs.getByRole("button", { name: "账号" }).click();
    await theirs.getByLabel("当前密码").fill("first-password");
    await theirs.getByLabel("新密码", { exact: true }).fill("second-password");
    await theirs.getByLabel("再输一次新密码").fill("second-password");
    await theirs.getByRole("button", { name: "修改密码" }).click();
    await expect(theirs.getByText(/密码已修改/)).toBeVisible();
    expect(await canLogin(browser, name, "first-password")).toBe(false);
    expect(await canLogin(browser, name, "second-password")).toBe(true);
    // 改密码的这个登录还在
    await user.reload();
    await expect(user).toHaveURL(/\/s\/s-[0-9a-f]{8}$/);

    // 管理员重置：旧密码登不上，新密码能
    const row = settings.getByRole("group", { name: `${name} 的操作` });
    await row.getByRole("button", { name: "重置密码" }).click();
    await settings.getByLabel(`${name} 的新密码`).fill("third-password");
    await settings.getByRole("button", { name: "重置", exact: true }).click();
    await expect(settings.getByText(`已重置 ${name} 的密码，其所有登录已失效。`)).toBeVisible();
    expect(await canLogin(browser, name, "second-password")).toBe(false);
    expect(await canLogin(browser, name, "third-password")).toBe(true);

    // 停用后登不上；启用后又能
    await row.getByRole("button", { name: "停用" }).click();
    await expect(row.getByRole("button", { name: "启用" })).toBeVisible();
    expect(await canLogin(browser, name, "third-password")).toBe(false);
    await row.getByRole("button", { name: "启用" }).click();
    await expect(row.getByRole("button", { name: "停用" })).toBeVisible();
    expect(await canLogin(browser, name, "third-password")).toBe(true);
    // 管理员自己的密码没动
    expect(await canLogin(browser, USER, PASSWORD)).toBe(true);
    await user.close();
    await admin.close();
  });
});
