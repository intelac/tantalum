import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { AttachmentRef, ImageAttachment } from "../api/types";
import { AttachmentView, singleImageSize } from "./AttachmentView";
import { UploadQueue } from "./uploads";

const hash = (char: string) => `sha256:${char.repeat(64)}`;
const image = (name: string, width = 800, height = 600, id = hash("a")): ImageAttachment => ({
  kind: "image",
  id,
  name,
  mediaType: "image/png",
  bytes: 1000,
  width,
  height,
});
const pdf: AttachmentRef = { kind: "file", id: hash("c"), name: "report.pdf", mediaType: "application/pdf", bytes: 2048, pages: 3 };

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("AttachmentView", () => {
  it("a single image has a 240 px long edge and an aspect ratio between 1:4 and 4:1", () => {
    expect(singleImageSize({ width: 800, height: 600 })).toEqual({ width: 240, height: 180 });
    expect(singleImageSize({ width: 600, height: 800 })).toEqual({ width: 180, height: 240 });
    expect(singleImageSize({ width: 4000, height: 100 })).toEqual({ width: 240, height: 60 });
    expect(singleImageSize({ width: 100, height: 4000 })).toEqual({ width: 60, height: 240 });
    render(<AttachmentView attachments={[image("cat.png")]} />);
    const button = screen.getByRole("button", { name: "cat.png，点击查看原图" });
    expect(button.style.width).toBe("240px");
    expect(button.style.height).toBe("180px");
  });

  it("several attachments are 64 px tiles and 240×64 file cards that download", () => {
    render(<AttachmentView attachments={[image("cat.png"), image("dog.png", 10, 10, hash("b")), pdf]} />);
    const tile = screen.getByRole("button", { name: "dog.png，点击查看原图" });
    expect([tile.style.width, tile.style.height]).toEqual(["64px", "64px"]);
    const card = screen.getByRole("link", { name: "下载 report.pdf" });
    expect(card.getAttribute("href")).toBe(`/api/attachments/${encodeURIComponent(hash("c"))}?name=report.pdf&download=1`);
    expect(card.getAttribute("download")).toBe("report.pdf");
    expect(card.textContent).toContain("2 KB · 3 页");
  });

  it("the lightbox closes on Escape, keeps focus inside and gives it back", async () => {
    render(<AttachmentView attachments={[image("cat.png")]} />);
    const opener = screen.getByRole("button", { name: "cat.png，点击查看原图" });
    await userEvent.click(opener);
    const dialog = screen.getByRole("dialog", { name: "原图预览" });
    expect(dialog.contains(document.activeElement)).toBe(true);
    const close = screen.getByRole("button", { name: "关闭原图预览" });
    close.focus();
    await userEvent.tab();
    expect(dialog.contains(document.activeElement)).toBe(true);
    expect(screen.getByRole("link", { name: "下载 cat.png" }).getAttribute("href")).toContain("download=1");
    await userEvent.click(screen.getByRole("button", { name: "实际大小" }));
    expect(screen.getByRole("button", { name: "适应窗口" })).toBeTruthy();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it("clicking the backdrop closes the lightbox", async () => {
    render(<AttachmentView attachments={[image("cat.png")]} />);
    await userEvent.click(screen.getByRole("button", { name: "cat.png，点击查看原图" }));
    fireEvent.mouseDown(screen.getByRole("dialog"));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  /** 刚上传的图片先用本地预览，服务器的图取到后换成服务器地址（需求 5.4）。 */
  it("starts with the local preview and switches to the server image once it loads", async () => {
    const probes: Array<{ onload: (() => void) | null; src: string }> = [];
    vi.stubGlobal("Image", class {
      onload: (() => void) | null = null;
      src = "";
      constructor() {
        probes.push(this);
      }
    });
    const uploaded = image("fresh.png", 800, 600, hash("d"));
    const queue = new UploadQueue(() => Promise.resolve(uploaded), { createUrl: () => "blob:fresh", revokeUrl: () => undefined });
    queue.add([new File(["x"], "fresh.png", { type: "image/png" })]);
    await queue.settled();
    render(<AttachmentView attachments={[uploaded]} />);
    const img = screen.getByRole("img", { name: "fresh.png" });
    expect(img.getAttribute("src")).toBe("blob:fresh");
    act(() => {
      probes[0]?.onload?.();
    });
    expect(screen.getByRole("img", { name: "fresh.png" }).getAttribute("src")).toBe(`/api/attachments/${encodeURIComponent(hash("d"))}?name=fresh.png`);
  });

  it("a server image that fails to load can be retried", async () => {
    render(<AttachmentView attachments={[image("cat.png", 800, 600, hash("e"))]} />);
    fireEvent.error(screen.getByRole("img", { name: "cat.png" }));
    await userEvent.click(screen.getByRole("button", { name: "图片加载失败，点击重试" }));
    expect(screen.getByRole("img", { name: "cat.png" })).toBeTruthy();
  });
});
