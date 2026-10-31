import { expect, test, type Page } from "@playwright/test";
import { openNewSession, processHeader, send } from "./app";

/** 运行结束后过程区折叠，展开它才能看到思考和工具。 */
async function expandProcess(page: Page): Promise<void> {
  const header = processHeader(page, /^已完成/);
  await expect(header).toBeVisible({ timeout: 30_000 });
  if ((await header.getAttribute("aria-expanded")) !== "true") await header.click();
}

test.describe("对话", () => {
  test("提问后显示思考和回复，刷新后从 journal 还原", async ({ page }) => {
    await openNewSession(page);
    // 同一个账号会累积会话，用唯一文本避免和侧栏里旧会话的标题撞上。
    const greeting = `你好 e2e ${Date.now()}`;
    await send(page, greeting);
    await expect(page.getByText(greeting, { exact: true })).toBeVisible();
    await expect(page.getByText(`你说：${greeting}`)).toBeVisible();

    await expandProcess(page);
    await expect(page.getByRole("button", { name: /思考 用户说了一句话，直接复述。/ })).toBeVisible();

    // 侧栏标题来自第一条消息。
    await expect(page.getByRole("navigation", { name: "会话" }).getByRole("button", { name: greeting })).toBeVisible();

    await page.reload();
    await expect(page.getByText(`你说：${greeting}`)).toBeVisible();
    await expandProcess(page);
    await expect(page.getByRole("button", { name: /思考 用户说了一句话，直接复述。/ })).toBeVisible();
    // 侧栏标题、顶栏标题、气泡各一处；没有重复的气泡。
    await expect(page.getByText(greeting, { exact: true })).toHaveCount(3);
  });

  test("工具链：grep、两个并行 read_file 和每一步的思考都显示", async ({ page }) => {
    await openNewSession(page);
    await send(page, "tools: 找一下 hello");
    await expandProcess(page);

    await expect(page.getByRole("button", { name: /思考 先用 grep 找一下 hello/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /思考 两个文件都提到了/ })).toBeVisible();

    const grep = page.getByRole("button", { name: /^grep/ });
    await expect(grep).toContainText("完成");
    await grep.click();
    await expect(page.getByRole("heading", { name: "结果" })).toBeVisible();
    await expect(page.locator("pre", { hasText: "notes/hello.md" })).toBeVisible();

    const group = page.getByRole("group", { name: "2 个并行调用" });
    await expect(group).toBeVisible();
    await expect(group.getByRole("button", { name: /^read_file/ })).toHaveCount(2);
    await expect(group.getByRole("button", { name: /^read_file.*hello\.md/ })).toContainText("完成");
    await expect(group.getByRole("button", { name: /^read_file.*world\.md/ })).toContainText("完成");

    await expect(page.getByText("两个文件都读到了")).toBeVisible();
    await expect(page.locator("code", { hasText: "hello e2e" })).toBeVisible();

    await page.reload();
    await expandProcess(page);
    await expect(page.getByRole("group", { name: "2 个并行调用" }).getByRole("button", { name: /^read_file/ })).toHaveCount(2);
    await expect(page.getByRole("button", { name: /^grep/ })).toContainText("完成");
    await expect(page.locator("code", { hasText: "hello e2e" })).toBeVisible();
  });
});
