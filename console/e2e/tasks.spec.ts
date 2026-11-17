import { expect, test, type Page } from "@playwright/test";
import { composer, login, openNewSession, OTHER_USER, send, SKILL_USER } from "./app";

// 任务（subagent 需求 10）。E2eModel 的 “tasks: 交代一|交代二” 剧本每一段放出一个任务，然后说一句就结束这一轮；
// 任务里按交代的内容走别的剧本：subtask: 停 1.5 秒、写一张清单、再停 1.5 秒、交结论；approve: 调 write_file；ask: 问用户。
// 任务的结果回来时主对话自动开一轮，模型说“任务回来了（…）：…”。

async function setApproval(page: Page, enabled: boolean) {
  const response = await page.request.put("/api/plugins/plugin/toolApproval", { data: { enabled } });
  expect(response.ok(), await response.text()).toBeTruthy();
}

test.describe("任务", () => {
  test("放出两个任务：卡片、展开看它在做什么、结果回来自动接着做，刷新还在", async ({ page }) => {
    await openNewSession(page);
    await send(page, "tasks: subtask: billing 模块|subtask: auth 模块");

    // 主对话这一轮照常结束，任务还在跑
    await expect(page.getByText("已经放出 2 个任务，我先做别的。")).toBeVisible({ timeout: 30_000 });
    const billing = page.getByRole("region", { name: "subtask: billing 模块" });
    const auth = page.getByRole("region", { name: "subtask: auth 模块" });
    await expect(billing).toContainText("进行中");
    await expect(auth).toContainText("进行中");
    await expect(billing).toContainText(/任务 t\d+-0 · 通用/);
    await expect(page.getByText("2 个任务进行中")).toBeVisible();
    await expect(page.getByRole("button", { name: "全部停止" })).toBeVisible();
    // 任务在跑的时候输入框照常能用
    await expect(composer(page)).toBeEnabled();

    // 展开：交代的全部内容和它在做的那一步
    await billing.getByRole("button", { name: "展开任务" }).click();
    await expect(billing).toContainText("交代的内容");
    await expect(billing).toContainText("只报告结论。");
    await expect(billing).toContainText("todo_write", { timeout: 15_000 });

    // 结果回来：不是用户气泡，是“任务结果”；主对话自己接着说
    const result = page.getByRole("region", { name: /任务 t\d+-0 的结果/ });
    await expect(result).toContainText("billing 模块：有 2 处没处理的错误。", { timeout: 30_000 });
    await expect(result).toContainText("完成");
    await expect(page.getByText(/任务回来了（completed）：auth 模块：有 2 处没处理的错误。/)).toBeVisible({ timeout: 30_000 });
    await expect(billing).toContainText("完成");
    await expect(auth).toContainText("完成");
    await expect(billing.getByRole("button", { name: "停止" })).toHaveCount(0);
    await expect(page.getByText(/个任务进行中/)).toHaveCount(0);
    // 用户只说过一句话
    await expect(page.getByText("tasks: subtask: billing 模块|subtask: auth 模块")).toHaveCount(1);

    // 侧栏只有主对话，没有任务的会话
    await expect(page.getByRole("navigation").getByText(/subtask: (billing|auth) 模块\n?只报告/)).toHaveCount(0);

    await page.reload();
    await expect(page.getByRole("region", { name: "subtask: billing 模块" })).toContainText("完成", { timeout: 30_000 });
    await expect(page.getByRole("region", { name: /任务 t\d+-\d+ 的结果/ })).toHaveCount(2);
  });

  test("任务停在等你回答：卡片上有标记，可以停掉，结果是已停止", async ({ page }) => {
    await openNewSession(page);
    await send(page, "tasks: ask: 选一个");
    const card = page.getByRole("region", { name: "ask: 选一个" });
    await expect(card).toContainText("等你回答", { timeout: 30_000 });
    await card.getByRole("button", { name: "停止" }).click();
    const result = page.getByRole("region", { name: /任务 t\d+-0 的结果/ });
    await expect(result).toContainText("已停止", { timeout: 30_000 });
    await expect(card).toContainText("已停止");
    await expect(page.getByText(/任务回来了（stopped before it finished）/)).toBeVisible({ timeout: 30_000 });
  });

  test("任务里要批准：问题显示在主对话的输入框位置，标明来自哪个任务", async ({ page }) => {
    await openNewSession(page, OTHER_USER);
    await setApproval(page, true);
    try {
      const stamp = Date.now();
      await send(page, `tasks: approve: t${stamp}`);
      const approval = page.getByRole("group", { name: "允许调用 write_file 吗？" });
      await expect(approval).toBeVisible({ timeout: 30_000 });
      await expect(approval).toContainText(/来自任务 t\d+-0「approve: t\d+」/);
      await expect(composer(page)).toBeHidden();
      await expect(page.getByRole("region", { name: `approve: t${stamp}` })).toContainText("等你回答");
      await approval.getByRole("button").first().click();
      await expect(page.getByRole("region", { name: /任务 t\d+-0 的结果/ })).toContainText(`结果：Created e2e/approve-t${stamp}.txt`, { timeout: 30_000 });
      await expect(approval).toHaveCount(0);
      await expect(composer(page)).toBeVisible();
    } finally {
      await login(page, OTHER_USER);
      await setApproval(page, false);
    }
  });

  test("用一个技能当任务的 agent：任务照技能的写法做，点了没有的技能就不开始并说明有哪些", async ({ page }) => {
    await openNewSession(page, SKILL_USER);
    // 填技能目录（测试电脑上有 greeting、by-hand 两个技能）
    await page.getByRole("button", { name: "插件", exact: true }).first().click();
    const local = page.getByRole("dialog", { name: "插件" }).getByRole("region", { name: "本机工作区" });
    const allowed = ((await local.getByText(/^允许的目录：/).textContent()) ?? "").replace(/^允许的目录：/, "").trim();
    const skills = local.getByLabel("技能目录");
    await skills.fill(allowed.includes("\\") ? `${allowed}\\skills` : `${allowed}/skills`);
    await skills.press("Enter");
    await expect(local.getByText("找到 2 个技能")).toBeVisible();
    await page.keyboard.press("Escape");
    try {
      await send(page, "taskas:greeting 写一句问候");
      const card = page.getByRole("region", { name: "subtask: 写一句问候" });
      await expect(card).toContainText(/任务 t\d+-0 · greeting/, { timeout: 30_000 });
      await expect(page.getByRole("region", { name: /任务 t\d+-0 的结果/ })).toContainText("写一句问候：有 2 处没处理的错误。（照技能 greeting 做的）", {
        timeout: 30_000,
      });

      // 没有这个技能：任务不开始，结果是失败，写明有哪些
      await send(page, "taskas:nope 另一件事");
      const failed = page.getByRole("region", { name: /任务 t\d+-0 的结果/ }).last();
      await expect(failed).toContainText("失败", { timeout: 30_000 });
      await expect(failed).toContainText(/no skill named "nope".*greeting/);
    } finally {
      await login(page, SKILL_USER);
      for (const path of ["/api/local/skills", "/api/local/wiki"]) await page.request.put(path, { data: { dir: "" } });
    }
  });

  test("手机宽度下卡片和结果不撑出横向滚动", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 800 });
    await openNewSession(page);
    await send(page, "tasks: subtask: 一个名字很长很长很长很长很长很长很长很长很长很长的模块");
    await expect(page.getByRole("region", { name: /任务 t\d+-0 的结果/ })).toBeVisible({ timeout: 30_000 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
