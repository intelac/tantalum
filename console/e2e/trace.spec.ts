import { expect, test, type Locator, type Page } from "@playwright/test";
import { openNewSession, processHeader, send } from "./app";

// 轨迹页：E2eModel 的 trace: 剧本先 429 再成功，之后一批并行工具（一个被 E2eTraceHook 拦下，一个是 MCP），
// E2eTraceHook 在模型调用前加补丁，结束前要一轮引用核对工作。

function rows(page: Page): Locator {
  return page.locator("[role=table] [role=row][data-record]");
}

function row(page: Page, tag: string, text?: RegExp): Locator {
  const tagged = rows(page).filter({ has: page.locator("span", { hasText: new RegExp(`^${tag}$`) }) });
  return text === undefined ? tagged : tagged.filter({ hasText: text });
}

async function tabNames(page: Page): Promise<string[]> {
  return page.getByRole("complementary", { name: "事件详情" }).getByRole("tab").allTextContents();
}

async function runTraceScenario(page: Page): Promise<string> {
  const session = await openNewSession(page);
  await send(page, `trace: 读一下文件 ${Date.now()}`);
  await expect(page.getByText("问候在 notes/hello.md:3，工单 42 归 e2e-owner。")).toBeVisible({ timeout: 30_000 });
  await expect(processHeader(page, /^已完成/)).toBeVisible({ timeout: 30_000 });
  await page.getByRole("tab", { name: "轨迹" }).click();
  await expect(page).toHaveURL(new RegExp(`/s/${session}/trace$`));
  await expect(row(page, "插件工作")).toBeVisible();
  return session;
}

test.describe("轨迹页", () => {
  test("每类记录都能打开，页签和类型对应", async ({ page }) => {
    await runTraceScenario(page);

    await expect(row(page, "助手").first()).toContainText("请求 #1");
    await expect(row(page, "助手").first()).toContainText("2/4");
    await expect(row(page, "工具")).toHaveCount(3);
    await expect(row(page, "工具", /被拦下/)).toHaveCount(1);
    await expect(row(page, "工具", /demo\/lookup/)).toHaveCount(1);

    const expected: [string, RegExp | undefined, string[]][] = [
      ["用户", undefined, ["概述", "来源"]],
      ["系统", undefined, ["差异", "系统提示词", "工具"]],
      ["钩子决定", /补丁/, ["概述", "来源"]],
      ["助手", undefined, ["概述", "系统提示词", "工具", "消息", "选项", "用量", "计时"]],
      ["工具", /被拦下/, ["概述", "参数", "结果", "Schema", "计时"]],
      ["插件工作", undefined, ["概述", "输入", "结果", "计时"]],
    ];
    for (const [tag, text, tabs] of expected) {
      await row(page, tag, text).first().click();
      await expect(page.getByRole("complementary", { name: "事件详情" })).toBeVisible();
      expect(await tabNames(page), tag).toEqual(tabs);
    }

    // 请求的系统提示词按需加载，里面有钩子加的补丁，分段标出来源。
    await row(page, "助手").last().click();
    await page.getByRole("tab", { name: "系统提示词" }).click();
    await expect(page.getByText("插件 · e2eTrace", { exact: false })).toBeVisible();
    await expect(page.locator("aside pre", { hasText: "Trace e2e: keep answers short." })).toBeVisible();

    // 被拦的工具原样显示拦截理由。
    await row(page, "工具", /被拦下/).click();
    await page.getByRole("tab", { name: "结果" }).click();
    await expect(page.locator("aside pre", { hasText: "secret files are off limits" })).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(page.getByRole("complementary", { name: "事件详情" })).toHaveCount(0);
  });

  test("钩子行和工作行互相跳转", async ({ page }) => {
    await runTraceScenario(page);
    await row(page, "钩子决定", /要求工作/).click();
    await page.getByRole("button", { name: /^打开 w:/ }).click();
    const inspector = page.getByRole("complementary", { name: "事件详情" });
    await expect(inspector).toContainText("插件工作");
    await expect(row(page, "插件工作")).toHaveAttribute("aria-selected", "true");
    await inspector.getByRole("button", { name: /^打开 h:/ }).click();
    await expect(inspector).toContainText("钩子决定");
    await expect(row(page, "钩子决定", /要求工作/)).toHaveAttribute("aria-selected", "true");

    // 拦工具的钩子跳到被拦的工具。
    await row(page, "钩子决定", /拦截/).click();
    await page.getByRole("button", { name: /^打开 t:/ }).click();
    await expect(row(page, "工具", /被拦下/)).toHaveAttribute("aria-selected", "true");
  });

  test("刷新前后记录表相同；对话页可以跳到轨迹里的工具记录", async ({ page }) => {
    const session = await runTraceScenario(page);
    const before = await rows(page).allTextContents();
    await page.reload();
    await expect(row(page, "插件工作")).toBeVisible();
    expect(await rows(page).allTextContents()).toEqual(before);

    await page.getByRole("tab", { name: "对话" }).click();
    const header = processHeader(page, /^已完成/);
    if ((await header.getAttribute("aria-expanded")) !== "true") await header.click();
    await page.getByRole("button", { name: /^mcp__demo__lookup/ }).click();
    await page.getByRole("link", { name: "在轨迹中查看" }).click();
    await expect(page).toHaveURL(new RegExp(`/s/${session}/trace\\?record=t%3A`));
    await expect(row(page, "工具", /demo\/lookup/)).toHaveAttribute("aria-selected", "true");
    await expect(page.getByRole("complementary", { name: "事件详情" })).toContainText("demo/lookup");
  });

  test("搜索覆盖收起的调用，跳转时展开", async ({ page }) => {
    await runTraceScenario(page);
    await page.getByRole("button", { name: "收起所有调用" }).click();
    await expect(row(page, "工具")).toHaveCount(0);
    await page.getByRole("searchbox", { name: "搜索轨迹" }).fill("e2e-owner");
    await page.getByRole("searchbox", { name: "搜索轨迹" }).press("Enter");
    await expect(row(page, "工具", /demo\/lookup/)).toHaveAttribute("aria-selected", "true");
  });

  test("手机宽度：检查器盖在记录表上，页面没有横向滚动", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await runTraceScenario(page);
    await row(page, "工具", /被拦下/).click();
    const inspector = page.getByRole("complementary", { name: "事件详情" });
    await expect(inspector).toBeVisible();
    const box = await inspector.boundingBox();
    expect(box?.width ?? 0).toBeGreaterThan(380);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    await page.getByRole("button", { name: "关闭详情" }).click();
    await expect(inspector).toHaveCount(0);
  });

  test("时间概览：点段选中记录；拖选区间过滤记录表，Esc 清除；切换画法", async ({ page }) => {
    await runTraceScenario(page);
    const overview = page.getByRole("region", { name: "轨迹时间线" });
    await expect(overview.getByRole("button", { name: /^插件工作，/ })).toBeVisible();

    // 点中工具段：选中对应记录，检查器打开。
    await overview.getByRole("button", { name: /^工具，demo\/lookup/ }).click();
    await expect(row(page, "工具", /demo\/lookup/)).toHaveAttribute("aria-selected", "true");
    await expect(page.getByRole("complementary", { name: "事件详情" })).toContainText("demo/lookup");

    // 在轨道上从左往右拖到一半：记录表只留这段时间里的记录。
    await page.getByRole("button", { name: "使用等宽操作" }).click();
    const track = overview.getByRole("group", { name: /时间线概览/ });
    const box = await track.boundingBox();
    if (box === null) throw new Error("timeline track not laid out");
    const all = await rows(page).count();
    await page.mouse.move(box.x + 2, box.y + box.height - 4);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.3, box.y + box.height - 4, { steps: 5 });
    await page.mouse.up();
    await expect.poll(() => rows(page).count()).toBeLessThan(all);
    await expect(row(page, "用户").first()).toBeVisible();
    await track.focus();
    await page.keyboard.press("Escape");
    await expect.poll(() => rows(page).count()).toBe(all);

    await page.getByRole("button", { name: "使用实际时长" }).click();
    await expect(page.getByRole("button", { name: "使用等宽操作" })).toBeVisible();
  });

  for (const scheme of ["light", "dark"] as const) {
    for (const width of [1280, 390]) {
      test(`截图 ${width}px ${scheme}`, async ({ page }) => {
        await page.emulateMedia({ colorScheme: scheme });
        await page.setViewportSize({ width, height: 800 });
        await runTraceScenario(page);
        await row(page, "助手").last().click();
        await page.screenshot({ path: `test-results/trace-${width}-${scheme}.png` });
      });
    }
  }
});
