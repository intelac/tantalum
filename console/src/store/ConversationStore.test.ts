import { describe, expect, it, vi } from "vitest";
import type { Item, TextItem, ToolStepItem, UserItem } from "../api/types";
import { ConversationStore } from "./ConversationStore";

const user: UserItem = { kind: "user", id: "0.0", session: "s", run: "run-0", at: "2026-10-31T00:00:00Z", text: "hi", queued: false };
const step: ToolStepItem = {
  kind: "tool",
  id: "2.0",
  session: "s",
  run: "run-0",
  at: "2026-10-31T00:00:02Z",
  call: { kind: "toolCall", id: "c1", name: "read_file", rawArguments: "{}" },
  status: "RUNNING",
  group: "g2",
};
const text: TextItem = { kind: "text", id: "4.0", session: "s", run: "run-0", at: "2026-10-31T00:00:04Z", text: "done", interrupted: false };

describe("ConversationStore", () => {
  it("overwrites by id without moving the item", () => {
    const store = new ConversationStore();
    store.applyItem(user);
    store.applyItem(step);
    store.applyItem(text);
    const before = store.getOrder("s");
    store.applyItem({ ...step, status: "DONE" });

    expect(store.getOrder("s")).toBe(before);
    expect(store.getOrder("s")).toEqual(["0.0", "2.0", "4.0"]);
    expect((store.getItem("2.0") as ToolStepItem).status).toBe("DONE");
  });

  it("notifies only the changed item, and the order only when something is added", () => {
    const store = new ConversationStore();
    store.applyItem(user);
    const onUser = vi.fn();
    const onStep = vi.fn();
    const onOrder = vi.fn();
    store.subscribe(ConversationStore.itemKey("0.0"), onUser);
    store.subscribe(ConversationStore.itemKey("2.0"), onStep);
    store.subscribe(ConversationStore.orderKey("s"), onOrder);

    store.applyItem(step);
    expect(onStep).toHaveBeenCalledTimes(1);
    expect(onOrder).toHaveBeenCalledTimes(1);
    store.applyItem({ ...step, status: "DONE" });
    expect(onStep).toHaveBeenCalledTimes(2);
    expect(onOrder).toHaveBeenCalledTimes(1);
    expect(onUser).not.toHaveBeenCalled();
  });

  it("a chunk notifies only the live listener of its run", () => {
    const store = new ConversationStore();
    store.applyItem(user);
    const onUser = vi.fn();
    const onOrder = vi.fn();
    const onLive = vi.fn();
    const onOtherLive = vi.fn();
    store.subscribe(ConversationStore.itemKey("0.0"), onUser);
    store.subscribe(ConversationStore.orderKey("s"), onOrder);
    store.subscribe(ConversationStore.liveKey("run-0"), onLive);
    store.subscribe(ConversationStore.liveKey("run-9"), onOtherLive);

    store.applyChunk("run-0", "text", "Hel");
    store.applyChunk("run-0", "text", "lo");
    store.applyChunk("run-0", "reasoning", "hmm");

    expect(store.getLive("run-0")).toEqual({ text: "Hello", reasoning: "hmm" });
    expect(onLive).toHaveBeenCalledTimes(3);
    expect(onUser).not.toHaveBeenCalled();
    expect(onOrder).not.toHaveBeenCalled();
    expect(onOtherLive).not.toHaveBeenCalled();

    store.clearLive("run-0");
    expect(store.getLive("run-0")).toEqual({ text: "", reasoning: "" });
    expect(onLive).toHaveBeenCalledTimes(4);
    store.clearLive("run-0");
    expect(onLive).toHaveBeenCalledTimes(4);
  });

  it("replace takes the server order and notifies only changed items", () => {
    const store = new ConversationStore();
    store.applyItem(user);
    store.applyItem(step);
    const onUser = vi.fn();
    const onStep = vi.fn();
    const onOrder = vi.fn();
    store.subscribe(ConversationStore.itemKey("0.0"), onUser);
    store.subscribe(ConversationStore.itemKey("2.0"), onStep);
    store.subscribe(ConversationStore.orderKey("s"), onOrder);

    const fromServer: Item[] = [user, { ...step, status: "DONE" }, text];
    store.replace("s", fromServer);

    expect(store.getOrder("s")).toEqual(["0.0", "2.0", "4.0"]);
    expect(onUser).not.toHaveBeenCalled();
    expect(onStep).toHaveBeenCalledTimes(1);
    expect(onOrder).toHaveBeenCalledTimes(1);

    store.replace("s", fromServer);
    expect(onOrder).toHaveBeenCalledTimes(1);
    expect(onStep).toHaveBeenCalledTimes(1);
  });

  it("a snapshot older than what SSE already delivered does not roll items back", () => {
    const store = new ConversationStore();
    // SSE：步骤在事件 2 创建，事件 3 完成；事件 4 又来了一条文本。
    store.applyItem(user, 0);
    store.applyItem(step, 2);
    store.applyItem({ ...step, status: "DONE" }, 3);
    store.applyItem(text, 4);

    // 接口快照在事件 2 时算出，比 SSE 晚到：步骤还在运行、没有文本。
    store.replace("s", [user, step], 2);
    expect((store.getItem("2.0") as ToolStepItem).status).toBe("DONE");
    expect(store.getOrder("s")).toEqual(["0.0", "2.0", "4.0"]);

    // 比快照新的 SSE 推送仍然生效；重连补读的旧推送不会倒退。
    store.replace("s", [user, { ...step, status: "DONE" }, text], 4);
    store.applyItem({ ...text, text: "newer" }, 6);
    store.applyItem(step, 2);
    expect((store.getItem("4.0") as TextItem).text).toBe("newer");
    expect((store.getItem("2.0") as ToolStepItem).status).toBe("DONE");
  });

  it("unsubscribe stops notifications and unknown keys are empty", () => {
    const store = new ConversationStore();
    const listener = vi.fn();
    const off = store.subscribe(ConversationStore.itemKey("0.0"), listener);
    off();
    store.applyItem(user);
    expect(listener).not.toHaveBeenCalled();
    expect(store.getOrder("nope")).toEqual([]);
    expect(store.getOrder("nope")).toBe(store.getOrder("nope"));
    expect(store.getItem("nope")).toBeUndefined();
  });
});
