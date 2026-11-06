import { expect, test, type Browser, type Page } from "@playwright/test";
import { I18N_USER, PASSWORD } from "./app";

// 控制台的语言（console-i18n 需求 1.2–1.8、2.4、4.2）。别的用例固定用中文浏览器跑（I16）；这里另开 ja-JP、zh-CN 的浏览器。
// 改的是第四个账号存的语言，结束时恢复成跟随浏览器。

async function context(browser: Browser, locale: string): Promise<Page> {
  const page = await (await browser.newContext({ locale })).newPage();
  return page;
}

async function signIn(page: Page, labels: { username: string; password: string; submit: string }) {
  await page.goto("/login");
  await page.getByLabel(labels.username).fill(I18N_USER);
  await page.getByLabel(labels.password).fill(PASSWORD);
  await page.getByRole("button", { name: labels.submit }).click();
  await expect(page).toHaveURL(/\/s\/s-[0-9a-f]{8}$/);
}

const JA = { username: "ユーザー名", password: "パスワード", submit: "ログイン" };
const ZH = { username: "用户名", password: "密码", submit: "登录" };

test.describe("语言", () => {
  test.afterEach(async ({ browser }) => {
    const page = await context(browser, "zh-CN");
    await page.goto("/login");
    await page.request.post("/api/auth/login", { data: { username: I18N_USER, password: PASSWORD }, headers: { origin: new URL(page.url()).origin } });
    const reset = await page.request.put("/api/settings/language", { data: { language: null }, headers: { origin: new URL(page.url()).origin } });
    expect(reset.ok(), await reset.text()).toBeTruthy();
  });

  test("跟浏览器；在设置里换成英文当场生效、刷新后第一帧就是英文、换一个浏览器还是英文；退出后回到浏览器的语言", async ({ browser }) => {
    const ja = await context(browser, "ja-JP");
    await ja.goto("/login");
    await expect(ja.locator("html")).toHaveAttribute("lang", "ja");
    await expect(ja.getByRole("button", { name: "ログイン" })).toBeVisible();
    await signIn(ja, JA);

    await ja.getByRole("button", { name: "その他" }).first().click();
    await ja.getByRole("menuitem", { name: "設定" }).click();
    const dialog = ja.getByRole("dialog", { name: "設定" });
    await dialog.getByRole("button", { name: "言語" }).click();
    await expect(dialog.getByRole("radio", { name: "ブラウザーの言語に合わせる（日本語）" })).toHaveAttribute("aria-checked", "true");
    await dialog.getByRole("radio", { name: "English" }).click();
    // 当场换，设置窗口还开着
    await expect(ja.getByRole("dialog", { name: "Settings" })).toBeVisible();
    await expect(ja.locator("html")).toHaveAttribute("lang", "en");
    await expect(ja.getByRole("dialog", { name: "Settings" }).getByRole("radio", { name: "English" })).toHaveAttribute("aria-checked", "true");

    // 刷新：index.html 的内联脚本在 React 起来前就写好 lang
    await ja.reload({ waitUntil: "domcontentloaded" });
    expect(await ja.evaluate(() => document.documentElement.lang)).toBe("en");
    await expect(ja.getByRole("textbox", { name: "Message Tantalum" })).toBeVisible();

    // 另一个浏览器（中文）登录同一个账号：语言跟着账号
    const zh = await context(browser, "zh-CN");
    await signIn(zh, ZH);
    await expect(zh.getByRole("textbox", { name: "Message Tantalum" })).toBeVisible();
    await expect(zh.locator("html")).toHaveAttribute("lang", "en");

    // 退出登录：登录页回到这个浏览器的语言
    await zh.getByRole("button", { name: "More" }).first().click();
    await zh.getByRole("menuitem", { name: "Sign out" }).click();
    await expect(zh.getByRole("button", { name: "登录" })).toBeVisible();
    await expect(zh.locator("html")).toHaveAttribute("lang", "zh-CN");
  });

  test("日文：超过上限的图片用日文说，数字按日文写；插件页的内置工具是日文名，不显示给模型看的英文说明", async ({ browser }) => {
    const ja = await context(browser, "ja-JP");
    await signIn(ja, JA);

    // 50 MB 多一点的 PNG（只有文件头是真的），在页面里造好拖进来：上传前就按上限拒绝
    await ja.evaluate(() => {
      const bytes = new Uint8Array(50 * 1024 * 1024 + 1);
      bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
      const data = new DataTransfer();
      data.items.add(new File([bytes], "huge.png", { type: "image/png" }));
      document.dispatchEvent(new DragEvent("dragenter", { dataTransfer: data, bubbles: true }));
      document.dispatchEvent(new DragEvent("drop", { dataTransfer: data, bubbles: true, cancelable: true }));
    });
    await expect(ja.getByRole("alert")).toHaveText("画像は 1 枚 50 MB までです");

    await ja.getByRole("button", { name: "その他" }).first().click();
    await ja.getByRole("menuitem", { name: "プラグイン" }).click();
    const builtins = ja.getByRole("dialog", { name: "プラグイン" }).getByRole("region", { name: "組み込みツール" });
    await expect(builtins.getByRole("switch", { name: "添付ファイルを読む" })).toBeVisible();
    await expect(builtins.getByRole("switch", { name: "メモリに書く" })).toBeVisible();
    await expect(builtins).not.toContainText("Read a file the user attached");
    await expect(builtins).not.toContainText("Save a long-term memory");
  });
});
