import { expect, test } from "@playwright/test";
import { PASSWORD, USER } from "./app";

test.describe("登录", () => {
  test("未登录访问会话被带到登录页，登录后回到原页面", async ({ page }) => {
    await page.goto("/s/s-e2e00001");
    await expect(page).toHaveURL(/\/login\?next=%2Fs%2Fs-e2e00001$/);
    await page.getByLabel("用户名").fill(USER);
    await page.getByLabel("密码").fill(PASSWORD);
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page).toHaveURL(/\/s\/s-e2e00001$/);
    await expect(page.getByRole("textbox", { name: "给 Tantalum 发消息" })).toBeVisible();
    // 模型信息来自后端配置：脚本化模型，标为本地。
    await expect(page.getByText("scripted-e2e")).toBeVisible();
    await expect(page.getByText("本地")).toBeVisible();
  });

  test("密码错误只提示用户名或密码错误", async ({ page }) => {
    await page.goto("/login");
    await page.getByLabel("用户名").fill(USER);
    await page.getByLabel("密码").fill("wrong-password");
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page.getByRole("alert")).toHaveText("用户名或密码错误");
    await expect(page).toHaveURL(/\/login$/);
  });
});
