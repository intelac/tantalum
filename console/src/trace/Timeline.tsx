// 时间概览，照 DSH TrajectoryTimeline：悬停 500 ms 出提示；移动小于 3 px 算点击；点中段就选中，点空白处让记录表滚到最近的记录；
// 拖选闭区间；滚轮缩放；右键单击、双击、Esc 清除区间；放大后右键拖动平移，拖选时靠边自动平移；选中的段在视口外时平移过去。
// 自定：视口内的段是按钮，可以 Tab 聚焦、Enter 选中；每段有至少 6 px 宽的透明点击区。

import { useEffect, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from "react";
import { currentText, useText } from "../text";
import { cx } from "../ui/cx";
import { formatMs } from "./format";
import { contentOf, isError } from "./LedgerRow";
import type { Row } from "./rows";
import { buildTimeline, centered, minimumSelection, nearestSpan, panned, revealed, zoomed, type Span, type TimelineMode, type TimeRange } from "./segments";

export const TOOLTIP_DELAY_MS = 500;
export const MINIMUM_DRAG_PX = 3;
/** 每段透明点击区的最小宽度（需求 6.9）。 */
export const MINIMUM_HIT_PX = 6;
const MINIMUM_BAR_PX = 2;
const EDGE_PAN_ZONE_FRACTION = 0.08;
const EDGE_PAN_STEP_FRACTION = 0.025;
const MAXIMUM_EDGE_PAN_PX = 32;
const LANE_TOP = [7, 21, 35, 49, 63];
const BAR_HEIGHT = 8;

const LANE_COLOR = ["bg-bar-input", "bg-bar-model", "bg-bar-tool", "bg-warning", "bg-fg-faint"];

interface Props {
  /** 不收起时的记录表行：时间概览画的是全部已加载的记录。 */
  rows: readonly Row[];
  mode: TimelineMode;
  range: TimeRange | null;
  onRange: (range: TimeRange | null) => void;
  selected: string | null;
  matches: ReadonlySet<string>;
  onSelect: (id: string) => void;
  /** 点空白处：让记录表滚到最近的记录，选中不变。 */
  onFocus: (id: string) => void;
  hasEarlier: boolean;
  onLoadEarlier: () => void;
}

function clock(ms: number): string {
  const time = new Date(ms);
  const two = (value: number) => String(value).padStart(2, "0");
  return `${two(time.getHours())}:${two(time.getMinutes())}:${two(time.getSeconds())}.${String(time.getMilliseconds()).padStart(3, "0")}`;
}

/** 提示：类型和内容、开始和结束时刻、总时长，助手再加首 token 和解码。时刻取记录原来的时间，不受压掉空档影响。 */
export function tooltipText(span: Span): string {
  const t = currentText().trace;
  const { record } = span;
  const lines = [`${t.kind[record.kind] ?? record.kind} · ${contentOf(record)}`];
  const start = Date.parse(record.at);
  const end = record.end === undefined ? undefined : Date.parse(record.end);
  if (end === undefined) lines.push(t.timeline.started(clock(start)));
  else {
    lines.push(`${clock(start)} → ${clock(end)}`);
    const parts = [t.timeline.total(formatMs(end - start))];
    const first = record.kind === "request" ? record.attempts.at(-1)?.firstTokenAt : undefined;
    if (first !== undefined) parts.push(t.timeline.ttftDecoding(formatMs(Date.parse(first) - start), formatMs(end - Date.parse(first))));
    lines.push(parts.join(" · "));
  }
  return lines.join("\n");
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value));
}

export function Timeline({ rows, mode, range, onRange, selected, matches, onSelect, onFocus, hasEarlier, onLoadEarlier }: Props) {
  const t = useText().trace;
  const model = useMemo(() => buildTimeline(rows, mode), [rows, mode]);
  const root = useRef<HTMLElement>(null);
  const track = useRef<HTMLDivElement>(null);
  const drag = useRef<{ pointer: number; anchorTime: number; anchorX: number; span: string | null } | null>(null);
  const pan = useRef<{ pointer: number; anchorX: number; anchorStart: number; moved: boolean } | null>(null);
  const [draft, setDraft] = useState<TimeRange | null>(null);
  const [viewport, setViewport] = useState<TimeRange | null>(null);
  const [hover, setHover] = useState<{ span: Span; x: number } | null>(null);
  const [tooltip, setTooltip] = useState<{ span: Span; x: number } | null>(null);
  const [panning, setPanning] = useState(false);

  // 换画法或时间域变了，旧的视口和区间可能落在域外。
  useEffect(() => {
    setViewport(null);
    onRange(null);
    // 只在画法变化时清空。
  }, [mode]);

  useEffect(() => {
    if (model === null || selected === null) return;
    setViewport((current) => revealed(model, current, model.spans.find((span) => span.id === selected)));
  }, [model, selected]);

  // 悬停 500 ms 才出提示。
  useEffect(() => {
    setTooltip(null);
    if (hover === null || drag.current !== null) return;
    const timer = setTimeout(() => setTooltip(hover), TOOLTIP_DELAY_MS);
    return () => clearTimeout(timer);
  }, [hover]);

  const full = model === null ? 1 : Math.max(1, model.end - model.start);
  const domainStart = viewport?.start ?? model?.start ?? 0;
  const domain = viewport === null ? full : Math.max(1e-9, viewport.end - viewport.start);

  useEffect(() => {
    const element = root.current;
    if (element === null || model === null) return;
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      const rect = track.current?.getBoundingClientRect();
      if (rect === undefined) return;
      setViewport((current) => zoomed(model, current, clamp01((event.clientX - rect.left) / Math.max(1, rect.width)), event.deltaY, mode));
    };
    element.addEventListener("wheel", onWheel, { passive: false });
    return () => element.removeEventListener("wheel", onWheel);
  }, [model, mode]);

  if (model === null) {
    return (
      <section ref={root} aria-label={t.timeline.aria} className="shrink-0 border-b border-line bg-raised">
        <div className="grid h-20 grid-cols-[56px_minmax(0,1fr)]">
          <LaneLabels />
          <div className="flex items-center px-3 text-xs text-fg-faint">{t.timeline.noTimingData}</div>
        </div>
      </section>
    );
  }

  const fraction = (clientX: number) => {
    const rect = track.current?.getBoundingClientRect();
    return rect === undefined ? 0 : clamp01((clientX - rect.left) / Math.max(1, rect.width));
  };
  const spanAt = (target: EventTarget | null) => (target instanceof HTMLElement ? (target.closest<HTMLElement>("[data-span]")?.dataset.span ?? null) : null);

  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    event.currentTarget.setPointerCapture?.(event.pointerId);
    if (event.button === 2) {
      pan.current = { pointer: event.pointerId, anchorX: event.clientX, anchorStart: domainStart, moved: false };
      setPanning(true);
      return;
    }
    if (event.button !== 0) return;
    const anchorTime = domainStart + fraction(event.clientX) * domain;
    drag.current = { pointer: event.pointerId, anchorTime, anchorX: event.clientX, span: spanAt(event.target) };
    setDraft({ start: anchorTime, end: anchorTime });
  };

  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const panGesture = pan.current;
    if (panGesture !== null && panGesture.pointer === event.pointerId) {
      if (Math.abs(event.clientX - panGesture.anchorX) >= MINIMUM_DRAG_PX) panGesture.moved = true;
      if (viewport === null) return;
      const delta = (event.clientX - panGesture.anchorX) / Math.max(1, rect.width);
      setViewport(panned(model, viewport, panGesture.anchorStart - delta * domain));
      return;
    }
    const gesture = drag.current;
    if (gesture === null || gesture.pointer !== event.pointerId) return;
    let start = domainStart;
    if (viewport !== null) {
      // 拖选时靠近边缘，自动往那边平移。
      const local = event.clientX - rect.left;
      const edge = Math.min(MAXIMUM_EDGE_PAN_PX, Math.max(1, rect.width * EDGE_PAN_ZONE_FRACTION));
      const direction = local < edge ? -1 : local > rect.width - edge ? 1 : 0;
      if (direction !== 0) {
        const strength = clamp01((direction < 0 ? edge - local : local - (rect.width - edge)) / edge);
        const next = panned(model, viewport, domainStart + direction * domain * EDGE_PAN_STEP_FRACTION * Math.max(0.2, strength));
        start = next.start;
        if (next.start !== viewport.start) setViewport(next);
      }
    }
    const time = start + fraction(event.clientX) * domain;
    setDraft(gesture.anchorTime <= time ? { start: gesture.anchorTime, end: time } : { start: time, end: gesture.anchorTime });
  };

  const onPointerUp = (event: PointerEvent<HTMLDivElement>) => {
    const panGesture = pan.current;
    if (panGesture !== null && panGesture.pointer === event.pointerId) {
      pan.current = null;
      setPanning(false);
      if (!panGesture.moved && Math.abs(event.clientX - panGesture.anchorX) < MINIMUM_DRAG_PX) onRange(null);
      return;
    }
    const gesture = drag.current;
    if (gesture === null || gesture.pointer !== event.pointerId) return;
    drag.current = null;
    setDraft(null);
    const time = domainStart + fraction(event.clientX) * domain;
    const click = Math.abs(event.clientX - gesture.anchorX) < MINIMUM_DRAG_PX;
    if (click && gesture.span !== null) {
      onRange(null);
      onSelect(gesture.span);
      return;
    }
    const selection = gesture.anchorTime <= time ? { start: gesture.anchorTime, end: time } : { start: time, end: gesture.anchorTime };
    const minimum = minimumSelection(model, domain);
    onRange(selection.end - selection.start < minimum ? centered(click ? selection.start : (selection.start + selection.end) / 2, minimum, model) : selection);
    if (click) onFocus(nearestSpan(model, selection.start).id);
  };

  const onPointerCancel = () => {
    drag.current = null;
    pan.current = null;
    setDraft(null);
    setPanning(false);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Escape" && range !== null) {
      event.preventDefault();
      event.stopPropagation();
      onRange(null);
    }
  };

  const percent = (time: number) => ((time - domainStart) / domain) * 100;
  const visible = model.spans.filter((span) => span.end >= domainStart && span.start <= domainStart + domain);
  const shown = draft ?? range;

  return (
    <section ref={root} aria-label={t.timeline.aria} className="relative shrink-0 border-b border-line bg-raised select-none">
      <div className="grid h-20 grid-cols-[56px_minmax(0,1fr)]">
        <LaneLabels />
        <div
          ref={track}
          role="group"
          aria-label={t.timeline.overviewAria}
          tabIndex={0}
          data-panning={panning || undefined}
          onKeyDown={onKeyDown}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerCancel}
          onPointerLeave={() => setHover(null)}
          onDoubleClick={(event) => {
            event.preventDefault();
            onRange(null);
          }}
          onContextMenu={(event) => event.preventDefault()}
          className={cx("relative overflow-hidden touch-none focus-visible:outline-2 focus-visible:outline-focus", panning ? "cursor-grabbing" : "cursor-crosshair")}
        >
          {hasEarlier && domainStart === model.start && (
            <button
              type="button"
              aria-label={t.history.loadEarlier}
              title={t.history.loadEarlier}
              onPointerDown={(event) => event.stopPropagation()}
              onClick={onLoadEarlier}
              className="absolute inset-y-0 left-0 z-10 w-7 bg-gradient-to-r from-raised to-transparent pl-1 text-left text-xs text-fg-faint hover:text-fg"
            >
              …
            </button>
          )}
          {model.turns.map((turn) => (
            <span key={turn.run} aria-hidden className="absolute inset-y-0 w-px bg-line-strong" style={{ left: `${percent(turn.time)}%` }} />
          ))}
          {visible.map((span) => (
            <Segment
              key={span.id}
              span={span}
              left={percent(span.start)}
              width={Math.max(0, percent(span.end) - percent(span.start))}
              firstToken={span.firstToken === undefined ? undefined : percent(span.firstToken) - percent(span.start)}
              selected={span.id === selected}
              matched={matches.has(span.id)}
              onHover={(x) => setHover({ span, x })}
              onSelect={() => onSelect(span.id)}
            />
          ))}
          {shown !== null && (
            <span
              aria-hidden
              data-range
              className="pointer-events-none absolute inset-y-0 border-x border-accent bg-accent/15"
              style={{ left: `${percent(shown.start)}%`, width: `${Math.max(0.2, percent(shown.end) - percent(shown.start))}%` }}
            />
          )}
        </div>
      </div>
      {tooltip !== null && (
        <div
          role="tooltip"
          className="pointer-events-none absolute top-full z-30 mt-1 max-w-[360px] rounded-md border border-line bg-overlay px-2 py-1 text-[11px] whitespace-pre-line text-fg shadow-md"
          style={{ left: `min(calc(56px + ${tooltip.x}px), calc(100% - 360px))` }}
        >
          {tooltipText(tooltip.span)}
        </div>
      )}
    </section>
  );
}

function LaneLabels() {
  const t = useText().trace;
  return (
    <div aria-hidden className="relative border-r border-line text-[10px] leading-none text-fg-faint">
      {t.timeline.lanes.map((label, lane) => (
        <span key={label} className="absolute right-1 flex h-2 items-center whitespace-nowrap" style={{ top: LANE_TOP[lane] }}>
          {label}
        </span>
      ))}
    </div>
  );
}

interface SegmentProps {
  span: Span;
  left: number;
  width: number;
  /** 首 token 在段内的位置（百分比，相对整条轨道）。 */
  firstToken?: number;
  selected: boolean;
  matched: boolean;
  onHover: (x: number) => void;
  onSelect: () => void;
}

function Segment({ span, left, width, firstToken, selected, matched, onHover, onSelect }: SegmentProps) {
  const text = useText();
  const t = text.trace;
  const error = isError(span.record);
  const color = error ? "bg-danger" : (LANE_COLOR[span.lane] ?? "bg-fg-faint");
  // 点击区至少 6 px；瞬时记录（零宽）的点击区以它为中心。
  const instant = width === 0 || span.running;
  const style: CSSProperties = {
    left: instant ? `calc(${left}% - ${MINIMUM_HIT_PX / 2}px)` : `${left}%`,
    width: instant ? MINIMUM_HIT_PX : `max(${MINIMUM_HIT_PX}px, ${width}%)`,
    top: (LANE_TOP[span.lane] ?? 0) - 3,
    height: BAR_HEIGHT + 6,
  };
  const ttft = firstToken === undefined || width === 0 ? undefined : Math.min(100, Math.max(0, (firstToken / width) * 100));
  return (
    <button
      type="button"
      data-span={span.id}
      aria-label={text.common.labelled(t.kind[span.record.kind] ?? span.record.kind, contentOf(span.record))}
      aria-pressed={selected}
      onPointerEnter={(event) => onHover(event.currentTarget.offsetLeft)}
      onKeyDown={(event) => {
        if (event.key !== "Enter" && event.key !== " ") return;
        event.preventDefault();
        onSelect();
      }}
      className={cx("absolute flex items-center focus-visible:outline-2 focus-visible:outline-focus", instant && "justify-center")}
      style={style}
    >
      {span.running ? (
        <span className={cx("h-full w-0.5", color)} />
      ) : (
        <span
          className={cx("relative h-2 w-full overflow-hidden rounded-[1px]", color, selected && "ring-2 ring-accent", matched && !selected && "ring-2 ring-warning")}
          style={{ minWidth: MINIMUM_BAR_PX, maxWidth: width === 0 ? MINIMUM_BAR_PX : undefined }}
        >
          {ttft !== undefined && <span aria-hidden className="absolute inset-y-0 left-0 bg-canvas/40" style={{ width: `${ttft}%` }} />}
        </span>
      )}
    </button>
  );
}
