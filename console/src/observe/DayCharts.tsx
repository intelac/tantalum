// 按天的两张柱状图，共用 x 轴（observe-page 需求 3.2、3.5；pi CostPanel 的做法）：上面每天的运行数按结束状态分段，
// 下面每天的 token。照 dataviz：一个刻度、只标到达过的值、柱之间留 2px、顶端圆角、坐标轴和文字用主题的文字色；
// 鼠标移到某一天显示那天的数字；另有给读屏用的表格。

import { useEffect, useRef, useState } from "react";
import { useText } from "../text";
import { formatTokens } from "../trace/format";
import { END_FILL } from "./ends";
import { scale, STACK, type DayBar } from "./charts";

const LEFT = 44;
const RIGHT = 8;
const RUNS_HEIGHT = 120;
const TOKENS_HEIGHT = 72;
const GAP = 16;
const AXIS = 18;

function useWidth() {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(640);
  useEffect(() => {
    const element = ref.current;
    if (element === null || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => {
      const next = entries[0]?.contentRect.width;
      if (next !== undefined && next > 0) setWidth(next);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}

/** 顶端带圆角的柱（只圆上面两个角，底部贴着基线）。 */
function topRounded(x: number, y: number, width: number, height: number, radius: number): string {
  const r = Math.min(radius, width / 2, height);
  return `M${x},${y + height}V${y + r}Q${x},${y} ${x + r},${y}H${x + width - r}Q${x + width},${y} ${x + width},${y + r}V${y + height}Z`;
}

export function DayCharts({ bars }: { bars: readonly DayBar[] }) {
  const text = useText();
  const t = text.observe;
  const [ref, width] = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const plot = Math.max(40, width - LEFT - RIGHT);
  const slot = plot / Math.max(1, bars.length);
  const barWidth = Math.max(1, slot - 2);
  const runs = scale(Math.max(0, ...bars.map((bar) => bar.runs)));
  const tokens = scale(Math.max(0, ...bars.map((bar) => bar.tokens)));
  const tokensTop = RUNS_HEIGHT + GAP;
  const height = tokensTop + TOKENS_HEIGHT + AXIS;
  const present = STACK.filter((end) => bars.some((bar) => (bar.ends[end] ?? 0) > 0));
  const shown = hover === null ? undefined : bars[hover];
  const labelDays = bars.length <= 1 ? [0] : [0, Math.floor((bars.length - 1) / 2), bars.length - 1];

  return (
    <figure aria-label={t.charts.label} className="m-0 flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-fg-muted">
        <span className="font-medium text-fg">{t.charts.runs}</span>
        {present.map((end) => (
          <span key={end} className="inline-flex items-center gap-1.5">
            <span aria-hidden className="h-2 w-2 rounded-sm" style={{ background: END_FILL[end] }} />
            {t.ends[end]}
          </span>
        ))}
      </div>
      <div ref={ref} className="relative w-full" onPointerLeave={() => setHover(null)}>
        <svg width={width} height={height} role="img" aria-label={t.charts.label} className="block overflow-visible">
          {/* 运行数：网格和刻度 */}
          {runs.ticks.map((tick) => {
            const y = RUNS_HEIGHT - (tick / runs.top) * RUNS_HEIGHT;
            return (
              <g key={`r${tick}`}>
                <line x1={LEFT} x2={LEFT + plot} y1={y} y2={y} stroke="var(--color-line)" strokeWidth={1} />
                <text x={LEFT - 6} y={y} dy="0.32em" textAnchor="end" fontSize={10} fill="var(--color-fg-faint)">
                  {tick}
                </text>
              </g>
            );
          })}
          {bars.map((bar, index) => {
            const x = LEFT + index * slot + 1;
            let base = RUNS_HEIGHT;
            const segments = STACK.filter((end) => (bar.ends[end] ?? 0) > 0);
            return (
              <g key={bar.day}>
                {segments.map((end, segment) => {
                  const h = ((bar.ends[end] ?? 0) / runs.top) * RUNS_HEIGHT;
                  // 段之间留 2px 的背景缝
                  const drawn = Math.max(1, h - (segment < segments.length - 1 ? 2 : 0));
                  const y = base - h;
                  base -= h;
                  const top = segment === segments.length - 1;
                  return top ? (
                    <path key={end} d={topRounded(x, y, barWidth, drawn, 4)} fill={END_FILL[end]} opacity={hover === null || hover === index ? 1 : 0.55} />
                  ) : (
                    <rect key={end} x={x} y={y + (h - drawn)} width={barWidth} height={drawn} fill={END_FILL[end]} opacity={hover === null || hover === index ? 1 : 0.55} />
                  );
                })}
              </g>
            );
          })}
          {/* token：单色 */}
          {tokens.ticks.map((tick) => {
            const y = tokensTop + TOKENS_HEIGHT - (tick / tokens.top) * TOKENS_HEIGHT;
            return (
              <g key={`t${tick}`}>
                <line x1={LEFT} x2={LEFT + plot} y1={y} y2={y} stroke="var(--color-line)" strokeWidth={1} />
                <text x={LEFT - 6} y={y} dy="0.32em" textAnchor="end" fontSize={10} fill="var(--color-fg-faint)">
                  {formatTokens(tick)}
                </text>
              </g>
            );
          })}
          <text x={LEFT} y={tokensTop - 4} fontSize={10} fill="var(--color-fg-muted)">
            {t.charts.tokens}
          </text>
          {bars.map((bar, index) => {
            const h = (bar.tokens / tokens.top) * TOKENS_HEIGHT;
            if (h <= 0) return null;
            const x = LEFT + index * slot + 1;
            return (
              <path
                key={bar.day}
                d={topRounded(x, tokensTop + TOKENS_HEIGHT - h, barWidth, h, 4)}
                fill="var(--color-accent)"
                opacity={hover === null || hover === index ? 0.85 : 0.45}
              />
            );
          })}
          {labelDays.map((index) => {
            const bar = bars[index];
            if (bar === undefined) return null;
            const x = LEFT + index * slot + slot / 2;
            const anchor = index === 0 ? "start" : index === bars.length - 1 ? "end" : "middle";
            return (
              <text key={bar.day} x={index === 0 ? LEFT : index === bars.length - 1 ? LEFT + plot : x} y={height - 4} fontSize={10} textAnchor={anchor} fill="var(--color-fg-faint)">
                {bar.day.slice(5)}
              </text>
            );
          })}
          {/* 悬停的命中区比柱子大：整列 */}
          {bars.map((bar, index) => (
            <rect
              key={`hit${bar.day}`}
              x={LEFT + index * slot}
              y={0}
              width={slot}
              height={tokensTop + TOKENS_HEIGHT}
              fill="transparent"
              onPointerEnter={() => setHover(index)}
              onPointerMove={() => setHover(index)}
            />
          ))}
        </svg>
        {shown !== undefined && hover !== null && (
          <div
            role="status"
            className="pointer-events-none absolute top-0 z-10 rounded-md border border-line bg-raised px-2 py-1 text-xs text-fg shadow-sm"
            style={{ left: Math.min(Math.max(0, LEFT + hover * slot + slot / 2 - 80), Math.max(0, width - 170)), width: 170 }}
          >
            <div className="font-medium">{t.charts.day(shown.day, shown.runs, formatTokens(shown.tokens))}</div>
            {STACK.filter((end) => (shown.ends[end] ?? 0) > 0).map((end) => (
              <div key={end} className="flex items-center gap-1.5 text-fg-muted">
                <span aria-hidden className="h-2 w-2 rounded-sm" style={{ background: END_FILL[end] }} />
                {t.ends[end]} {shown.ends[end]}
              </div>
            ))}
          </div>
        )}
      </div>
      <table className="sr-only">
        <caption>{t.charts.label}</caption>
        <thead>
          <tr>
            <th scope="col">{t.table.start}</th>
            <th scope="col">{t.summary.runs}</th>
            {STACK.map((end) => (
              <th key={end} scope="col">
                {t.ends[end]}
              </th>
            ))}
            <th scope="col">{t.summary.tokens}</th>
          </tr>
        </thead>
        <tbody>
          {bars.map((bar) => (
            <tr key={bar.day}>
              <th scope="row">{bar.day}</th>
              <td>{bar.runs}</td>
              {STACK.map((end) => (
                <td key={end}>{bar.ends[end] ?? 0}</td>
              ))}
              <td>{bar.tokens}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}
