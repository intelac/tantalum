import { expect, test } from "@playwright/test";
import { PASSWORD, USER, openNewSession, send } from "./app";

test.describe("连接", () => {
  test("断线时顶部提示，重连后补上断线期间的内容", async ({ page, request }) => {
    const session = await openNewSession(page);
    await send(page, "hello 断线前");
    await expect(page.getByText("你说：hello 断线前")).toBeVisible();

    // 先让浏览器的重连都失败，再从服务端断开已有的 SSE 连接：提示出现，并且一直断着。
    const origin = { origin: "http://localhost:5174" };
    const login = await request.post("/api/auth/login", { data: { username: USER, password: PASSWORD }, headers: origin });
    expect(login.ok()).toBeTruthy();
    await page.route("**/api/events", (route) => route.abort());
    const dropped = await request.post("/api/e2e/disconnect", { headers: origin });
    expect(dropped.ok(), await dropped.text()).toBeTruthy();
    await expect(page.getByRole("status").filter({ hasText: "连接已断开，正在重连…" })).toBeVisible();

    // 断线期间由另一个客户端在同一个会话里提问，浏览器收不到推送。
    const submitted = await request.post(`/api/sessions/${session}/submissions`, {
      data: { requestId: `offline-${Date.now()}`, mode: "prompt", text: "hello 断线中" },
      headers: origin,
    });
    expect(submitted.ok(), await submitted.text()).toBeTruthy();
    await page.waitForTimeout(1500);
    await expect(page.getByText("你说：hello 断线中")).toHaveCount(0);

    // 网络恢复后自动重连（退避最多 4 秒），提示消失，内容和 journal 一致。
    await page.unroute("**/api/events");
    await expect(page.getByRole("status").filter({ hasText: "连接已断开" })).toHaveCount(0, { timeout: 15_000 });
    await expect(page.getByText("你说：hello 断线中")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("你说：hello 断线前")).toBeVisible();
  });

  test("退出登录后回到登录页，原来的会话地址需要重新登录", async ({ page }) => {
    const session = await openNewSession(page);
    await send(page, "hello 退出前");
    await expect(page.getByText("你说：hello 退出前")).toBeVisible();

    await page.getByRole("button", { name: "更多" }).click();
    await page.getByRole("menuitem", { name: "退出登录" }).click();
    await expect(page).toHaveURL(/\/login\?next=/);
    await expect(page.getByLabel("用户名")).toBeVisible();

    // Cookie 已失效：直接访问会话地址仍回到登录页，接口返回 401。
    await page.goto(`/s/${session}`);
    await expect(page).toHaveURL(new RegExp(`/login\\?next=%2Fs%2F${session}$`));
    const me = await page.request.get("/api/auth/me");
    expect(me.status()).toBe(401);
  });
});
