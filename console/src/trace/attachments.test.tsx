import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import wire from "../../../tantalum-shell/src/test/resources/wire/attachments.txt?raw";
import type { ModelRequest, TracePage, TraceRecord, UserInput } from "../api/types";
import { Inspector } from "./inspector/Inspector";
import { contentOf } from "./LedgerRow";

// 后端 WireFormatTest 的附件场景：提问带图片和 PDF、插话带文件、只有图片的排队提交；照片大到每个请求都省略它。
const PAGE = JSON.parse(wire.slice(wire.indexOf("# trace\n") + "# trace\n".length)) as TracePage;
const RECORDS = PAGE.records;
const byId = (id: string) => RECORDS.find((record) => record.id === id) as TraceRecord;
const users = RECORDS.filter((record): record is UserInput => record.kind === "user");

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function open(record: TraceRecord) {
  vi.stubGlobal("fetch", vi.fn(() => new Promise(() => undefined)));
  render(
    <QueryClientProvider client={new QueryClient()}>
      <Inspector session="s" record={record} lookup={byId} containerWidth={1280} overlay={false} onClose={vi.fn()} onOpen={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe("attachments on the trace page", () => {
  it("user rows count images and files after the text", () => {
    expect(users.map(contentOf)).toEqual(["what is this? · 图片 ×1 · 文件 ×1", "插话 · also this · 文件 ×1", "排队 · 图片 ×1"]);
  });

  it("the overview lists every attachment with a 48 px thumbnail for images", () => {
    open(users[0] as UserInput);
    const thumb = screen.getByRole("img", { name: "photo.jpg" });
    expect(thumb.className).toContain("h-12 w-12");
    expect(screen.getByText("photo.jpg").closest("li")?.textContent).toContain("15.3 MB · image/jpeg · 2048×1536px · 由 4000×3000px 缩小");
    expect(screen.getByText("report.pdf").closest("li")?.textContent).toContain("88 KB · application/pdf · 3 页");
  });

  it("the raw tab shows the full references", async () => {
    open(users[0] as UserInput);
    await userEvent.click(screen.getByRole("tab", { name: "原始" }));
    expect(screen.getByRole("tabpanel").textContent).toContain("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
  });

  it("a request that omitted images says how many", () => {
    const request = RECORDS.find((record): record is ModelRequest => record.kind === "request") as ModelRequest;
    expect(request.omittedImages).toHaveLength(1);
    open(request);
    expect(screen.getByText("这次请求里省略了 1 张图片")).toBeTruthy();
  });
});
