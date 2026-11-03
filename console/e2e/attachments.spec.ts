import { expect, test, type Page } from "@playwright/test";
import { composer, openNewSession } from "./app";

// 附件：E2eModel 看到带附件的提问时回答“看到了附件：名字、名字。”。后端开着 tantalum.openai.vision，收图片。

const FIXTURES = "../tantalum-shell/src/test/resources/attachments";
const PNG = `${FIXTURES}/clean.png`;
const JPG = `${FIXTURES}/rotated.jpg`;

function tray(page: Page) {
  return page.getByRole("list", { name: "待发送附件" });
}

/** 文件内容：磁盘上的样例（先经一个临时的文件输入框交给页面），或页面里现造的文字。 */
type Spec = { path: string; name: string; type: string } | { text: string; name: string; type: string };

/** 在页面里造一个带这些文件的 DataTransfer，派发拖放或粘贴事件。 */
async function dispatchWithFiles(page: Page, kind: "drop" | "paste", specs: Spec[]) {
  const paths = specs.flatMap((spec) => ("path" in spec ? [spec.path] : []));
  if (paths.length > 0) {
    await page.evaluate(() => {
      const input = document.createElement("input");
      input.type = "file";
      input.multiple = true;
      input.id = "e2e-staged";
      input.hidden = true;
      document.body.append(input);
    });
    await page.locator("#e2e-staged").setInputFiles(paths);
  }
  await page.evaluate(
    ({ kind, specs }) => {
      const staged = document.querySelector<HTMLInputElement>("#e2e-staged");
      const disk = Array.from(staged?.files ?? []);
      const data = new DataTransfer();
      for (const spec of specs) {
        const source = "text" in spec ? new Blob([spec.text]) : (disk.shift() as File);
        data.items.add(new File([source], spec.name, { type: spec.type }));
      }
      staged?.remove();
      if (kind === "drop") {
        document.dispatchEvent(new DragEvent("dragenter", { dataTransfer: data, bubbles: true }));
        document.dispatchEvent(new DragEvent("drop", { dataTransfer: data, bubbles: true, cancelable: true }));
      } else {
        document.querySelector("textarea")?.dispatchEvent(new ClipboardEvent("paste", { clipboardData: data, bubbles: true, cancelable: true }));
      }
    },
    { kind, specs },
  );
}

test.describe("附件", () => {
  test("选择、拖放、粘贴，移除一个，发送；刷新后还在；灯箱和下载；轨迹页", async ({ page }) => {
    const session = await openNewSession(page);

    await page.getByTestId("attachment-picker").setInputFiles([PNG]);
    await dispatchWithFiles(page, "drop", [{ name: "notes.txt", type: "text/plain", text: "e2e notes" }]);
    await dispatchWithFiles(page, "paste", [{ name: "pasted.jpg", type: "image/jpeg", path: JPG }]);
    await dispatchWithFiles(page, "drop", [{ name: "extra.txt", type: "text/plain", text: "remove me" }]);
    await expect(tray(page).getByRole("listitem")).toHaveCount(4);
    await page.getByRole("button", { name: "移除 extra.txt" }).click();
    await expect(tray(page).getByRole("listitem")).toHaveCount(3);
    await expect(tray(page).getByRole("progressbar")).toHaveCount(0, { timeout: 15_000 });

    await composer(page).fill("这些是什么");
    await composer(page).press("Enter");
    await expect(page.getByText("看到了附件：clean.png、notes.txt、pasted.jpg。")).toBeVisible({ timeout: 30_000 });
    await expect(tray(page)).toHaveCount(0);

    // 刷新后从服务器读回：两张图片是方块，文件是卡片。
    await page.reload();
    await expect(page.getByRole("button", { name: "clean.png，点击查看原图" })).toBeVisible();
    const card = page.getByRole("link", { name: "下载 notes.txt" });
    await expect(card).toBeVisible();
    const download = page.waitForEvent("download");
    await card.click();
    expect((await download).suggestedFilename()).toBe("notes.txt");

    // 灯箱：转正后的照片是 8×16；Esc 关闭，焦点回到图片。
    const opener = page.getByRole("button", { name: "pasted.jpg，点击查看原图" });
    await opener.click();
    const dialog = page.getByRole("dialog", { name: "原图预览" });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("img", { name: "pasted.jpg" })).toHaveJSProperty("naturalWidth", 8);
    await dialog.getByRole("button", { name: "实际大小" }).click();
    await expect(dialog.getByRole("button", { name: "适应窗口" })).toBeVisible();
    await expect(dialog.getByRole("link", { name: "下载 pasted.jpg" })).toHaveAttribute("href", /download=1/);
    await page.keyboard.press("Escape");
    await expect(dialog).toHaveCount(0);
    await expect(opener).toBeFocused();

    // 把对话里的图片拖回输入框：直接加入，不再上传。
    const uploads: string[] = [];
    page.on("request", (request) => {
      if (request.method() === "POST" && request.url().includes("/attachments")) uploads.push(request.url());
    });
    await opener.dragTo(composer(page));
    await expect(tray(page).getByRole("img", { name: "pasted.jpg" })).toBeVisible();
    expect(uploads).toEqual([]);
    await composer(page).fill("再看一次");
    await composer(page).press("Enter");
    await expect(page.getByText("看到了附件：pasted.jpg。")).toBeVisible({ timeout: 30_000 });

    // 轨迹页：用户记录写明数量，检查器列出附件。
    await page.getByRole("tab", { name: "轨迹" }).click();
    await expect(page).toHaveURL(new RegExp(`/s/${session}/trace$`));
    const userRow = page.locator("[role=table] [role=row][data-record]", { hasText: "图片 ×2 · 文件 ×1" });
    await expect(userRow).toBeVisible();
    await userRow.click();
    const details = page.getByRole("complementary", { name: "事件详情" });
    await expect(details.getByRole("img", { name: "clean.png" })).toBeVisible();
    await expect(details.getByText("8×16px", { exact: false })).toBeVisible();
    await details.getByRole("tab", { name: "原始" }).click();
    await expect(details.getByText("sha256:", { exact: false }).first()).toBeVisible();
  });

  test("拖着文件经过页面时出现提示，写明图片的限制，离开后消失", async ({ page }) => {
    await openNewSession(page);
    await page.evaluate(() => {
      const data = new DataTransfer();
      data.items.add(new File(["x"], "a.txt"));
      document.dispatchEvent(new DragEvent("dragenter", { dataTransfer: data, bubbles: true }));
    });
    await expect(page.getByText("文件或图片拖动到此处即可添加")).toBeVisible();
    await expect(page.getByText("图片限制：最多 20 张，每张 50 MB")).toBeVisible();
    await page.evaluate(() => {
      const data = new DataTransfer();
      data.items.add(new File(["x"], "a.txt"));
      document.dispatchEvent(new DragEvent("dragleave", { dataTransfer: data, bubbles: true }));
    });
    await expect(page.getByText("文件或图片拖动到此处即可添加")).toHaveCount(0);
  });

  test("服务器拒绝的附件留在附件条上并说明原因，可以移除", async ({ page }) => {
    await openNewSession(page);
    await dispatchWithFiles(page, "drop", [{ name: "fake.png", type: "image/png", text: "not a png" }]);
    await expect(page.getByRole("button", { name: "重试上传 fake.png" })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole("button", { name: "重试上传 fake.png" })).toHaveAttribute("title", /不是 PNG/);
    await page.getByRole("button", { name: "移除 fake.png" }).click();
    await expect(tray(page)).toHaveCount(0);
  });

  test("手机宽度：附件条和气泡不超出屏幕", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 800 });
    await openNewSession(page);
    await page.getByTestId("attachment-picker").setInputFiles([PNG, JPG]);
    await expect(tray(page).getByRole("progressbar")).toHaveCount(0, { timeout: 15_000 });
    await composer(page).fill("手机");
    await composer(page).press("Enter");
    await expect(page.getByText("看到了附件：clean.png、rotated.jpg。")).toBeVisible({ timeout: 30_000 });
    const width = await page.evaluate(() => document.documentElement.scrollWidth);
    expect(width).toBeLessThanOrEqual(390);
  });
});
