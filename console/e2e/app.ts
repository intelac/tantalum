// 端到端共用：账号、登录、常用定位。文案和 src/text/zh.ts 保持一致。

import { expect, type Locator, type Page } from "@playwright/test";

export const USER = "e2e";
export const PASSWORD = "e2e-password";
/** 第二个账号（E2eServer.OTHER_USER）：插件开关按用户持久保存，会改开关的用例用它，不影响别的用例。 */
export const OTHER_USER = "e2e2";
/** 第三个账号（E2eServer.LOCAL_USER）：没连测试电脑，本机工作区的用例用它连真的连接程序。 */
export const LOCAL_USER = "e2e3";

/** 直接调接口登录，Cookie 落到浏览器上下文里；之后打开首页就进入新会话。 */
export async function login(page: Page, username = USER): Promise<void> {
  const response = await page.request.post("/api/auth/login", {
    data: { username, password: PASSWORD },
    headers: { origin: page.url().startsWith("http") ? new URL(page.url()).origin : "http://localhost:5174" },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
}

/** 登录并打开一个新会话，返回会话 ID。 */
export async function openNewSession(page: Page, username = USER): Promise<string> {
  await login(page, username);
  await page.goto("/");
  await expect(page).toHaveURL(/\/s\/s-[0-9a-f]{8}$/);
  return new URL(page.url()).pathname.split("/")[2] ?? "";
}

export function composer(page: Page): Locator {
  return page.getByRole("textbox", { name: "给 Tantalum 发消息" });
}

export async function send(page: Page, text: string): Promise<void> {
  await composer(page).fill(text);
  await composer(page).press("Enter");
}

/** 过程区的标题随运行结束变成“已完成，用时 …”。 */
export function processHeader(page: Page, pattern: RegExp): Locator {
  return page.getByRole("button", { name: pattern });
}
