import { describe, expect, it, vi } from "vitest";
import pageJson from "../../../tantalum-shell/src/test/resources/wire/trace.json?raw";
import pushLines from "../../../tantalum-shell/src/test/resources/wire/trace-sse.txt?raw";
import type { TracePage, TraceRecord, UserInput } from "../api/types";
import { comparePos, sessionOfRun, TraceStore } from "./TraceStore";

// 后端 WireFormatTest 写出的期望文件：同一场景的整页重放，以及逐事件的 SSE 推送（“序号.下标 trace JSON”）。
const FULL = JSON.parse(pageJson) as TracePage;
const PUSHES = pushLines
  .trim()
  .split("\n")
  .map((line) => {
    const [id = "", , ...rest] = line.split(" ");
    return { seq: Number(id.split(".")[0]), record: JSON.parse(rest.join(" ")) as TraceRecord };
  });
const SESSION = "s";
const SEQS = [...new Set(PUSHES.map((push) => push.seq))];

const sync = (flush: () => void) => flush();
const noFetch = () => Promise.reject(new Error("not expected"));

/** 截至序号 {@code seq} 的整页：每条记录取那时最后推送的版本。 */
function pageAt(seq: number): TracePage {
  const latest = new Map<string, TraceRecord>();
  for (const push of PUSHES) if (push.seq <= seq) latest.set(push.record.id, push.record);
  return { seq, records: [...latest.values()].sort((a, b) => comparePos(a.pos, b.pos)), before: 0, hasMore: false };
}

function user(run: string, seq: number, text: string): UserInput {
  return { kind: "user", id: `u:${seq}`, pos: `${seq}.0`, run, turn: 1, step: 1, at: "2026-10-31T00:00:00Z", mode: "PROMPT", text, source: { type: "user" } };
}

describe("TraceStore 合并", () => {
  it("期望文件自洽：推送的最终版本就是整页", () => {
    expect(pageAt(FULL.seq).records).toEqual(FULL.records);
  });

  it.each(SEQS)("页面截至 %i，之后按顺序套上全部推送，结果等于整页", (cut) => {
    const store = new TraceStore(noFetch, sync);
    store.loadLatest(SESSION, pageAt(cut));
    // 截点之前的推送也重放一遍：重连时补读和页面会重叠，旧版本和同版本都不能覆盖。
    for (const push of PUSHES) store.applyRecord(push.record, push.seq);
    expect(store.getRecords(SESSION)).toEqual(FULL.records);
  });

  it.each(SEQS)("推送先到、截至 %i 的页面后到，结果也等于整页", (cut) => {
    const store = new TraceStore(noFetch, sync);
    for (const push of PUSHES) if (push.seq > cut) store.applyRecord(push.record, push.seq);
    store.loadLatest(SESSION, pageAt(cut));
    expect(store.getRecords(SESSION)).toEqual(FULL.records);
  });

  it("比已有版本旧的推送丢掉", () => {
    const store = new TraceStore(noFetch, sync);
    store.applyRecord(user("s_0", 0, "new"), 5);
    store.applyRecord(user("s_0", 0, "old"), 3);
    store.applyRecord(user("s_0", 0, "same"), 5);
    expect((store.getRecord(SESSION, "u:0") as UserInput).text).toBe("new");
  });

  it("按会话分开：运行 ID 去掉最后的“_序号”就是会话", () => {
    expect(sessionOfRun("s-b4f3379b_12")).toBe("s-b4f3379b");
    expect(sessionOfRun("a_b_3")).toBe("a_b");
    const store = new TraceStore(noFetch, sync);
    store.applyRecord(user("a_1", 1, "x"), 1);
    store.applyRecord(user("b_1", 1, "y"), 1);
    expect(store.getRecords("a")).toHaveLength(1);
    expect(store.getRecords("b")).toHaveLength(1);
  });

  it("开始得比已加载部分还早的运行，推送不收", () => {
    const store = new TraceStore(noFetch, sync);
    store.loadLatest(SESSION, { seq: 20, records: [user("s_10", 10, "loaded")], before: 10, hasMore: true });
    store.applyRecord(user("s_3", 4, "too old"), 21);
    store.applyRecord(user("s_10", 12, "same run"), 21);
    expect(store.getRecords(SESSION).map((record) => record.id)).toEqual(["u:10", "u:12"]);
  });

  it("重新拉最新一页时，接得上的更早几页连同翻页位置保留，接不上的丢掉", () => {
    const older: TracePage = { seq: 30, records: [user("s_0", 0, "first")], before: 0, hasMore: false };
    const store = new TraceStore(() => Promise.resolve(older), sync);
    store.loadLatest(SESSION, { seq: 30, records: [user("s_10", 10, "a")], before: 10, hasMore: true });
    return store.loadOlder(SESSION).then(() => {
      expect(store.getPaging(SESSION)).toMatchObject({ before: 0, hasMore: false });
      store.loadLatest(SESSION, { seq: 31, records: [user("s_10", 10, "a"), user("s_10", 31, "b")], before: 10, hasMore: true });
      expect(store.getRecords(SESSION).map((record) => record.id)).toEqual(["u:0", "u:10", "u:31"]);
      expect(store.getPaging(SESSION)).toMatchObject({ before: 0, hasMore: false });

      store.loadLatest(SESSION, { seq: 90, records: [user("s_80", 80, "far")], before: 80, hasMore: true });
      expect(store.getRecords(SESSION).map((record) => record.id)).toEqual(["u:80"]);
      expect(store.getPaging(SESSION)).toMatchObject({ before: 80, hasMore: true });
    });
  });
});

describe("TraceStore 翻页", () => {
  it("同一会话同时只有一个取更早一页的请求，轨迹和统计共用", async () => {
    let resolve: (page: TracePage) => void = () => {};
    const fetch = vi.fn(() => new Promise<TracePage>((done) => (resolve = done)));
    const store = new TraceStore(fetch, sync);
    store.loadLatest(SESSION, { seq: 20, records: [user("s_10", 10, "a")], before: 10, hasMore: true });
    const first = store.loadOlder(SESSION);
    const second = store.loadOlder(SESSION);
    expect(second).toBe(first);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith(SESSION, 10);
    expect(store.getPaging(SESSION).loading).toBe(true);
    resolve({ seq: 20, records: [user("s_0", 0, "old")], before: 0, hasMore: false });
    await first;
    expect(store.getPaging(SESSION)).toEqual({ loaded: true, before: 0, hasMore: false, loading: false });
    expect(store.getRecords(SESSION).map((record) => record.id)).toEqual(["u:0", "u:10"]);
    await store.loadOlder(SESSION);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("还没加载、或中间重新加载过时，不用这一页", async () => {
    let resolve: (page: TracePage) => void = () => {};
    const fetch = vi.fn(() => new Promise<TracePage>((done) => (resolve = done)));
    const store = new TraceStore(fetch, sync);
    await store.loadOlder(SESSION);
    expect(fetch).not.toHaveBeenCalled();

    store.loadLatest(SESSION, { seq: 20, records: [user("s_10", 10, "a")], before: 10, hasMore: true });
    const request = store.loadOlder(SESSION);
    store.loadLatest(SESSION, { seq: 90, records: [user("s_80", 80, "far")], before: 80, hasMore: true });
    resolve({ seq: 20, records: [user("s_0", 0, "stale")], before: 0, hasMore: false });
    await request;
    expect(store.getRecords(SESSION).map((record) => record.id)).toEqual(["u:80"]);
    expect(store.getPaging(SESSION)).toEqual({ loaded: true, before: 80, hasMore: true, loading: false });
  });

  it("失败后可以再试", async () => {
    const fetch = vi.fn().mockRejectedValueOnce(new Error("down")).mockResolvedValueOnce({ seq: 20, records: [], before: 0, hasMore: false });
    const store = new TraceStore(fetch, sync);
    store.loadLatest(SESSION, { seq: 20, records: [user("s_10", 10, "a")], before: 10, hasMore: true });
    await expect(store.loadOlder(SESSION)).rejects.toThrow("down");
    expect(store.getPaging(SESSION)).toMatchObject({ loading: false, hasMore: true });
    await store.loadOlder(SESSION);
    expect(fetch).toHaveBeenCalledTimes(2);
  });
});

describe("TraceStore 通知", () => {
  it("一批推送按帧合并，只通知一次；没变的推送不通知", () => {
    const frames: (() => void)[] = [];
    const store = new TraceStore(noFetch, (flush) => frames.push(flush));
    const listener = vi.fn();
    store.subscribe(TraceStore.recordsKey(SESSION), listener);
    for (const push of PUSHES.slice(0, 10)) store.applyRecord(push.record, push.seq);
    expect(frames).toHaveLength(1);
    frames.shift()?.();
    expect(listener).toHaveBeenCalledTimes(1);
    const before = store.getRecords(SESSION);

    store.applyRecord(PUSHES[0]?.record as TraceRecord, PUSHES[0]?.seq as number);
    expect(frames).toHaveLength(0);
    expect(store.getRecords(SESSION)).toBe(before);
  });
});
