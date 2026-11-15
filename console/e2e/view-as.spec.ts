import { type Browser, expect, type Page, test } from "@playwright/test";
import { composer, login, OTHER_USER, send, USER } from "./app";

// 管理员只读查看别人的会话（qagent-align 需求 6）。管理员是第一个账号（e2e），被看的是第二个账号（e2e2）。
// 8098 上另有一台服务器，部署的人关掉了这个功能。

const OFF = "http://127.0.0.1:8098";

async function signedIn(browser: Browser, user: string): Promise<Page> {
  const page = await browser.newPage();
  await login(page, user);
  await page.goto("/");
  await expect(page).toHaveURL(/\/s\/s-[0-9a-f]{8}$/);
  return page;
}

async function viewAs(admin: Page, name: string) {
  await admin.getByRole("button", { name: "设置", exact: true }).first().click();
  const settings = admin.getByRole("dialog", { name: "设置" });
  await settings.getByRole("button", { name: "运维" }).click();
  // 自己那一行没有这个按钮
  await expect(settings.getByRole("group", { name: `${USER} 的操作` }).getByRole("button", { name: /身份查看/ })).toHaveCount(0);
  await settings.getByRole("button", { name: `以 ${name} 的身份查看（只读）` }).click();
  await expect(admin.getByRole("status").filter({ hasText: `正在只读查看 ${name} 的会话` })).toBeVisible();
}

test.describe("只读查看别人的会话", () => {
  test("管理员看到他的会话、对话和轨迹，改不了任何东西；他那边的新内容实时出现；退出后回到自己", async ({ browser }) => {
    const stamp = Date.now();
    const user = await signedIn(browser, OTHER_USER);
    await send(user, `view me ${stamp}`);
    await expect(user.getByText(/^已完成/).first()).toBeVisible({ timeout: 30_000 });
    const session = user.url().split("/s/")[1]!;

    const admin = await signedIn(browser, USER);
    // 管理员自己先说一句：这不该出现在查看别人的页面里
    await send(admin, `admin own ${stamp}`);
    await expect(admin.getByText(/^已完成/).first()).toBeVisible({ timeout: 30_000 });
    await viewAs(admin, OTHER_USER);

    // 直接打开了他最近的会话；侧栏是他的会话，没有管理员自己的
    await expect(admin).toHaveURL(new RegExp(`/s/${session}$`));
    await expect(admin.getByText(`view me ${stamp}`).first()).toBeVisible();
    const sidebar = admin.getByRole("navigation", { name: "会话" });
    await expect(sidebar.getByText(`view me ${stamp}`)).toBeVisible();
    await expect(sidebar.getByText(`admin own ${stamp}`)).toHaveCount(0);

    // 只读：没有输入框、新会话、插件、设置；说明写在原来输入框的位置
    await expect(composer(admin)).toHaveCount(0);
    await expect(admin.getByText(`这是 ${OTHER_USER} 的会话，只能看，不能发消息、改标题或做任何改动。`)).toBeVisible();
    for (const name of ["新会话", "插件", "设置"]) {
      await expect(admin.getByRole("button", { name, exact: true })).toHaveCount(0);
    }
    await admin.screenshot({ path: "test-results/view-as.png" });

    // 轨迹和统计也能看
    await admin.getByRole("tab", { name: "轨迹" }).click();
    await expect(admin).toHaveURL(new RegExp(`/s/${session}/trace`));
    await expect(admin.getByText(`view me ${stamp}`).first()).toBeVisible();
    await admin.getByRole("tab", { name: "统计" }).click();
    await expect(admin).toHaveURL(new RegExp(`/s/${session}/stats`));
    await admin.getByRole("tab", { name: "对话" }).click();

    // 绕过页面直接发请求：写的一律拒，不在允许范围里的读也拒
    const write = await admin.request.post(`/api/sessions/${session}/submissions?viewAs=${OTHER_USER}`, {
      data: { requestId: `x${stamp}`, mode: "prompt", text: "as someone else" },
    });
    expect(write.status()).toBe(403);
    expect((await write.json()).code).toBe("view/read-only");
    expect((await admin.request.patch(`/api/sessions/${session}?viewAs=${OTHER_USER}`, { data: { title: "x" } })).status()).toBe(403);
    expect((await admin.request.get(`/api/plugin-api/memory/entries?viewAs=${OTHER_USER}`)).status()).toBe(403);
    expect((await admin.request.get(`/api/local?viewAs=${OTHER_USER}`)).status()).toBe(403);
    // 普通用户带这个参数：拒
    const peek = await user.request.get(`/api/sessions?viewAs=${USER}`);
    expect(peek.status()).toBe(403);
    expect((await peek.json()).code).toBe("view/forbidden");

    // 他那边继续提问：管理员这边实时看到
    await send(user, `live ${stamp}`);
    await expect(admin.getByText(`live ${stamp}`).first()).toBeVisible({ timeout: 30_000 });
    await expect(user.getByText(/^已完成/)).toHaveCount(2, { timeout: 30_000 });

    // 退出查看：回到自己的会话，输入框回来了
    await admin.getByRole("button", { name: "退出查看" }).click();
    await expect(admin.getByRole("status").filter({ hasText: "正在只读查看" })).toHaveCount(0);
    await expect(composer(admin)).toBeVisible();
    await expect(admin.getByRole("navigation", { name: "会话" }).getByText(`admin own ${stamp}`)).toBeVisible();
    await expect(admin.getByRole("navigation", { name: "会话" }).getByText(`view me ${stamp}`)).toHaveCount(0);

    // 再进去一次然后刷新：查看状态不保存，刷新就回到自己
    await viewAs(admin, OTHER_USER);
    await admin.reload();
    await expect(admin.getByRole("status").filter({ hasText: "正在只读查看" })).toHaveCount(0);
    await expect(composer(admin)).toBeVisible();

    // 他的会话没有被动过
    const title = await (await user.request.get("/api/sessions")).json();
    expect(JSON.stringify(title)).not.toContain("as someone else");
    await admin.close();
    await user.close();
  });

  test("390 宽：横幅和只读说明不撑出横向滚动", async ({ browser }) => {
    const stamp = Date.now();
    const user = await signedIn(browser, OTHER_USER);
    await send(user, `narrow ${stamp}`);
    await expect(user.getByText(/^已完成/).first()).toBeVisible({ timeout: 30_000 });
    const admin = await signedIn(browser, USER);
    await viewAs(admin, OTHER_USER);
    await admin.setViewportSize({ width: 390, height: 844 });
    await expect(admin.getByRole("button", { name: "退出查看" })).toBeVisible();
    expect(await admin.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    await admin.screenshot({ path: "test-results/view-as-phone.png" });
    await admin.close();
    await user.close();
  });

  test("部署的人关掉以后：运维页不给入口，接口拒绝", async ({ page }) => {
    const response = await page.request.post(`${OFF}/api/auth/login`, { data: { username: USER, password: "e2e-password" }, headers: { origin: OFF } });
    expect(response.ok(), await response.text()).toBeTruthy();
    const list = await (await page.request.get(`${OFF}/api/users`)).json();
    expect(list.viewAs).toBe(false);
    const refused = await page.request.get(`${OFF}/api/sessions?viewAs=${USER}`);
    expect(refused.status()).toBe(403);
    expect((await refused.json()).code).toBe("view/forbidden");
    await page.goto(`${OFF}/`);
    await page.getByRole("button", { name: "设置", exact: true }).first().click();
    const settings = page.getByRole("dialog", { name: "设置" });
    await settings.getByRole("button", { name: "运维" }).click();
    await expect(settings.getByRole("group", { name: `${USER} 的操作` })).toBeVisible();
    await expect(settings.getByRole("button", { name: /身份查看/ })).toHaveCount(0);
  });
});
