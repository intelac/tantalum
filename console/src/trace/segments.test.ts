import { describe, expect, it } from "vitest";
import pageJson from "../../../tantalum-shell/src/test/resources/wire/trace.json?raw";
import type { ModelRequest, TracePage, TraceRecord, UserInput } from "../api/types";
import { buildLedger, focusRows } from "./rows";
import {
  buildTimeline,
  centered,
  minimumSelection,
  MINIMUM_ZOOM_MS,
  MINIMUM_ZOOM_OPERATIONS,
  nearestSpan,
  panned,
  revealed,
  spansInRange,
  zoomed,
  type TimelineModel,
} from "./segments";

const RECORDS = (JSON.parse(pageJson) as TracePage).records;
const NONE = new Set<string>();
const ROWS = buildLedger(RECORDS, NONE, NONE);

const T0 = Date.parse("2026-10-31T00:00:00Z");
const iso = (ms: number) => new Date(T0 + ms).toISOString();

function user(id: string, run: string, at: number, end: number | undefined): UserInput {
  return { kind: "user", id, pos: `${at}.0`, run, turn: 1, step: 1, at: iso(at), end: end === undefined ? undefined : iso(end), mode: "PROMPT", text: id, source: { type: "user" } };
}

function request(id: string, run: string, at: number, end: number, firstToken?: number): ModelRequest {
  return {
    kind: "request", id, pos: `${at}.1`, run, turn: 1, step: 1, at: iso(at), end: iso(end), number: 1, purpose: "NORMAL", status: "DONE",
    attempts: [{ call: id, issuedAt: iso(at), startedAt: iso(at), firstTokenAt: firstToken === undefined ? undefined : iso(firstToken), endedAt: iso(end), outcome: "REPLIED" }],
    maxAttempts: 4, toolCalls: 0,
  };
}

function timeline(records: TraceRecord[], mode: "duration" | "sequence"): TimelineModel {
  return buildTimeline(buildLedger(records, NONE, NONE), mode) as TimelineModel;
}

describe("buildTimeline", () => {
  it("每个操作等宽：按记录表顺序一格一个，运行本身不画；四条道", () => {
    const model = buildTimeline(ROWS, "sequence") as TimelineModel;
    const drawn = ROWS.filter((row) => row.type === "record" && row.record.kind !== "run");
    expect(model.spans.map((span) => span.id)).toEqual(drawn.map((row) => (row.type === "record" ? row.record.id : "")));
    expect(model.spans.every((span, index) => span.start === index && span.end === index + 1)).toBe(true);
    expect(model.turns.map((turn) => turn.run)).toEqual(["s_0", "s_10", "s_13", "s_18", "s_21", "s_23"]);
    const lane = (id: string) => model.spans.find((span) => span.id === id)?.lane;
    expect([lane("u:0"), lane("s:2"), lane("c:11")]).toEqual([0, 0, 0]);
    expect([lane("r:s_2-0"), lane("k:15")]).toEqual([1, 1]);
    expect(lane("t:6.1")).toBe(2);
    expect([lane("h:1.0"), lane("w:s_1-0")]).toEqual([3, 3]);
  });

  it("按实际时长：没有任何活动的空档压掉，重叠的不算空档", () => {
    const model = timeline([user("u:0", "s_0", 0, 1_000), request("r:a", "s_0", 500, 2_000), user("u:9", "s_9", 7_000, 8_000)], "duration");
    expect(model.spans.map((span) => [span.id, span.start - T0, span.end - T0])).toEqual([
      ["u:0", 0, 1_000],
      ["r:a", 500, 2_000],
      // 2 秒到 7 秒之间什么都没有，压掉 5 秒。
      ["u:9", 2_000, 3_000],
    ]);
    expect(model.end - model.start).toBe(3_000);
    expect(model.turns.map((turn) => turn.time - T0)).toEqual([0, 2_000]);
  });

  it("助手段带首 token 位置；进行中的记录只有开始标记", () => {
    const model = timeline([request("r:a", "s_0", 0, 2_000, 600), user("u:1", "s_0", 2_500, undefined)], "duration");
    const assistant = model.spans.find((span) => span.id === "r:a");
    const running = model.spans.find((span) => span.id === "u:1");
    expect((assistant?.firstToken ?? 0) - T0).toBe(600);
    expect(running).toMatchObject({ running: true });
    expect(running?.end).toBe(running?.start);
  });

  it("没有可画的记录时为空", () => {
    expect(buildTimeline([], "duration")).toBeNull();
  });
});

describe("区间", () => {
  const model = buildTimeline(ROWS, "sequence") as TimelineModel;

  it("闭区间：刚好碰到边界的记录也算", () => {
    expect([...spansInRange(model, { start: 3, end: 3 })]).toEqual([model.spans[2]?.id, model.spans[3]?.id]);
    expect(spansInRange(model, { start: 2.5, end: 2.5 }).size).toBe(1);
  });

  it("记录表只留区间里的记录，空的步标题和轮标题去掉", () => {
    const ids = spansInRange(model, { start: 0, end: 0.5 });
    const rows = focusRows(ROWS, ids);
    expect(rows.map((row) => (row.type === "record" ? row.record.id : row.type))).toEqual(["turn", "group", "u:0"]);
  });

  it("点空白处：离得最近的段；拖选不足最小宽时以点击处为中心展开", () => {
    expect(nearestSpan(model, 0.2).id).toBe(model.spans[0]?.id);
    expect(nearestSpan(model, 100).id).toBe(model.spans.at(-1)?.id);
    expect(minimumSelection(model, 10)).toBe(1);
    expect(minimumSelection(model, 0.5)).toBe(0.5);
    expect(centered(0.1, 1, model)).toEqual({ start: 0, end: 1 });
    expect(centered(model.end, 1, model)).toEqual({ start: model.end - 1, end: model.end });
  });
});

describe("视口", () => {
  const model = buildTimeline(ROWS, "sequence") as TimelineModel;
  const full = model.end - model.start;

  it("滚轮以指针为锚点缩放：指针下的位置不动；缩回整个域时视口为空", () => {
    const view = zoomed(model, null, 0.5, -200, "sequence") as { start: number; end: number };
    expect(view.end - view.start).toBeCloseTo(full * Math.exp(-0.3));
    expect(view.start + 0.5 * (view.end - view.start)).toBeCloseTo(model.start + 0.5 * full);
    expect(zoomed(model, view, 0.5, 10_000, "sequence")).toBeNull();
  });

  it("最少显示 4 个操作宽，按实际时长时最少 20 毫秒", () => {
    const view = zoomed(model, null, 0, -100_000, "sequence") as { start: number; end: number };
    expect(view.end - view.start).toBe(MINIMUM_ZOOM_OPERATIONS);
    const timed = timeline([user("u:0", "s_0", 0, 10_000)], "duration");
    const narrow = zoomed(timed, null, 0, -100_000, "duration") as { start: number; end: number };
    expect(narrow.end - narrow.start).toBe(MINIMUM_ZOOM_MS);
  });

  it("平移限制在时间域里；选中的段在视口外时平移过去", () => {
    const view = { start: 0, end: 4 };
    expect(panned(model, view, -5)).toEqual({ start: 0, end: 4 });
    expect(panned(model, view, 1_000)).toEqual({ start: model.end - 4, end: model.end });
    const last = model.spans.at(-1);
    expect(revealed(model, view, last)).toEqual({ start: model.end - 4, end: model.end });
    expect(revealed(model, view, model.spans[1])).toBe(view);
    expect(revealed(model, null, last)).toBeNull();
  });
});
