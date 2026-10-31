import { expect, test } from "@playwright/test";
import { composer, openNewSession, processHeader, send } from "./app";

// 脚本化模型：含 "slow" 的提问会分 40 段、每段 250 毫秒慢慢回答，足够在中途排队或停止。

test.describe("运行控制", () => {
  test("运行中提交进入排队，运行结束后自动执行", async ({ page }) => {
    await openNewSession(page);
    await send(page, "slow 先慢慢答");
    await expect(page.getByRole("button", { name: "停止" })).toBeVisible();
    await expect(page.getByRole("button", { name: "排队" })).toBeVisible();
    await expect(page.getByText("慢慢回答第 1 段。")).toBeVisible();

    await send(page, "hi 排在后面");
    const dock = page.getByRole("status", { name: "排队中" });
    await expect(dock).toContainText("1 条排队中");
    await expect(dock).toContainText("hi 排在后面");
    await expect(page.getByText("排队中", { exact: true })).toBeVisible();

    // 慢回复结束后排队的提交自动开始，并得到自己的回复。
    await expect(page.getByText("你说：hi 排在后面")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("慢慢回答第 40 段。")).toBeVisible();
    await expect(dock).toHaveCount(0);
    // 纯文本的慢回复没有过程区；排队的那次有思考，所以只有一个过程区标题。
    await expect(processHeader(page, /^已完成/)).toHaveCount(1);
    await expect(page.getByRole("button", { name: "停止" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "发送" })).toBeVisible();
  });

  test("停止按钮取消运行，已经发出的部分保留", async ({ page }) => {
    await openNewSession(page);
    await send(page, "slow 然后停掉");
    await expect(page.getByText("慢慢回答第 2 段。")).toBeVisible();
    await page.getByRole("button", { name: "停止" }).click();

    const cancelled = page.getByRole("status").filter({ hasText: "已取消" });
    await expect(cancelled).toBeVisible();
    await expect(page.getByText("慢慢回答第 1 段。")).toBeVisible();
    await expect(page.getByText("已中断")).toBeVisible();
    await expect(page.getByText("慢慢回答第 40 段。")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "停止" })).toHaveCount(0);

    // 刷新后仍然是取消状态，部分回复还在。
    await page.reload();
    await expect(cancelled).toBeVisible();
    await expect(page.getByText("慢慢回答第 1 段。")).toBeVisible();
    await expect(page.getByText("已中断")).toBeVisible();
  });

  test("切换会话：内容不串，草稿各自保留", async ({ page }) => {
    await openNewSession(page);
    const first = `hello 第一个 ${Date.now()}`;
    await send(page, first);
    await expect(page.getByText(`你说：${first}`)).toBeVisible();

    await page.getByRole("button", { name: "新会话" }).click();
    await expect(page).toHaveURL(/\/s\/s-[0-9a-f]{8}$/);
    await expect(page.getByText("有什么可以帮你？")).toBeVisible();
    await expect(page.getByText(`你说：${first}`)).toHaveCount(0);
    await composer(page).fill("还没发出去的草稿");

    await page.getByRole("navigation", { name: "会话" }).getByRole("button", { name: first }).click();
    await expect(page.getByText(`你说：${first}`)).toBeVisible();
    await expect(composer(page)).toHaveValue("");

    await page.goBack();
    await expect(page.getByText("有什么可以帮你？")).toBeVisible();
    await expect(composer(page)).toHaveValue("还没发出去的草稿");
  });
});
