import { expect, test } from "@playwright/test";
import { openNewSession, send } from "./app";

// 侧栏的三个问题（2026-11-13 Yi 报的）：菜单被侧栏右边裁掉、空工作区找不到“在此新建会话”、右键过分割线以后鼠标移过去侧栏就跟着动。

test.describe("侧栏", () => {
  test("会话名很长时“⋯”菜单整个在侧栏里，点得到归档", async ({ page }) => {
    await openNewSession(page);
    const long = `一个很长很长的会话名字，用来看改名和归档这些操作在名字很长的时候能不能显示完整 ${Date.now()}`;
    await send(page, long);
    const sidebar = page.getByRole("complementary", { name: "会话" });
    // 菜单按钮的名字是“会话名 的操作”（名字太长时中间省略）
    await sidebar.getByText(long.slice(0, 12)).first().hover();
    await sidebar.getByRole("button", { name: new RegExp(`^${long.slice(0, 12)}.* 的操作$`) }).click();
    const side = (await sidebar.boundingBox())!;
    for (const name of ["改名", "归档"]) {
      const item = (await page.getByRole("menuitem", { name }).boundingBox())!;
      expect(item.x).toBeGreaterThanOrEqual(side.x);
      expect(item.x + item.width).toBeLessThanOrEqual(side.x + side.width);
    }
    await page.keyboard.press("Escape");
  });

  test("右键分割线以后，鼠标只是移过去侧栏不动", async ({ page }) => {
    await openNewSession(page);
    const sidebar = page.getByRole("complementary", { name: "会话" });
    const separator = page.getByRole("separator", { name: "会话" });
    const before = (await sidebar.boundingBox())!.width;
    const box = (await separator.boundingBox())!;
    const x = box.x + box.width / 2;
    const y = box.y + 300;
    await page.mouse.move(x, y);
    await page.mouse.down({ button: "right" });
    await page.mouse.up({ button: "right" });
    await page.keyboard.press("Escape");
    await page.mouse.move(x + 150, y, { steps: 8 });
    expect((await sidebar.boundingBox())!.width).toBe(before);
    // 左键拖动照常
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x + 60, y, { steps: 6 });
    await page.mouse.up();
    const dragged = (await sidebar.boundingBox())!.width;
    expect(dragged).toBeGreaterThan(before + 40);
    await page.mouse.move(x + 200, y, { steps: 8 });
    expect((await sidebar.boundingBox())!.width).toBe(dragged);
  });
});
