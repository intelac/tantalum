// 运行详情的调用树（observe-page 需求 4.2、4.3）：每个节点一行，按层级缩进、可以折叠；右边在整次运行的时间轴上画一根条，
// 按种类配色，模型请求分“等首 token”和“生成”两段（DSH 轨迹总览的做法）。键盘：上下移动，左右折叠展开，回车选中。

import { AlertCircle, ChevronDown, ChevronRight, Clock, Loader2 } from "lucide-react";
import { useEffect, useRef, type KeyboardEvent } from "react";
import type { RunNode } from "../api/types";
import { useText } from "../text";
import { formatMs } from "../trace/format";
import { cx } from "../ui/cx";
import { bar, stepLabel, type TreeRow } from "./tree";

const FILL: Partial<Record<RunNode["kind"], string>> = {
  request: "var(--color-bar-model)",
  attempt: "var(--color-bar-model)",
  tool: "var(--color-bar-tool)",
  hook: "var(--color-warning)",
  work: "var(--color-warning)",
  question: "var(--color-fg-faint)",
  task: "var(--color-accent)",
  step: "var(--color-line-strong)",
  compaction: "var(--color-line-strong)",
};

interface Props {
  rows: readonly TreeRow[];
  span: { start: number; end: number } | undefined;
  now: number;
  selected: string | null;
  onSelect: (id: string) => void;
  onToggle: (id: string, open: boolean) => void;
}

export function nodeLabel(node: RunNode, text: ReturnType<typeof useText>): string {
  const t = text.observe.run;
  const step = stepLabel(node);
  if (step !== undefined) return t.step(step);
  if (node.kind === "attempt") return `${t.kinds.attempt} ${node.id.slice(node.id.lastIndexOf("#") + 1)}`;
  if (node.kind === "context" || node.kind === "system" || node.kind === "compaction") return t.kinds[node.kind];
  if (node.kind === "input" || node.kind === "task") return t.kinds[node.kind];
  return node.name ?? t.kinds[node.kind];
}

function StateIcon({ node }: { node: RunNode }) {
  const t = useText().observe.run;
  if (node.state === "error") return <AlertCircle size={13} className="shrink-0 text-danger" aria-label={t.states.error} />;
  if (node.state === "running") return <Loader2 size={13} className="shrink-0 text-accent motion-safe:animate-spin" aria-label={t.states.running} />;
  if (node.state === "waiting") return <Clock size={13} className="shrink-0 text-warning" aria-label={t.states.waiting} />;
  return null;
}

export function RunTree({ rows, span, now, selected, onSelect, onToggle }: Props) {
  const text = useText();
  const t = text.observe.run;
  const container = useRef<HTMLDivElement>(null);
  const current = selected ?? rows[0]?.node.id ?? null;

  useEffect(() => {
    if (selected === null) return;
    const element = container.current?.querySelector<HTMLElement>(`[data-node="${CSS.escape(selected)}"]`);
    element?.scrollIntoView?.({ block: "nearest" });
  }, [selected]);

  const onKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const index = rows.findIndex((row) => row.node.id === current);
    const row = rows[index];
    if (row === undefined) return;
    const move = (to: number) => {
      const target = rows[Math.max(0, Math.min(rows.length - 1, to))];
      if (target === undefined) return;
      onSelect(target.node.id);
      container.current?.querySelector<HTMLElement>(`[data-node="${CSS.escape(target.node.id)}"]`)?.focus();
    };
    if (event.key === "ArrowDown") move(index + 1);
    else if (event.key === "ArrowUp") move(index - 1);
    else if (event.key === "ArrowRight" && row.parent && row.collapsed) onToggle(row.node.id, true);
    else if (event.key === "ArrowLeft" && row.parent && !row.collapsed) onToggle(row.node.id, false);
    else if (event.key === "Enter") onSelect(row.node.id);
    else return;
    event.preventDefault();
  };

  return (
    <div ref={container} role="tree" aria-label={t.tree} onKeyDown={onKey} className="flex flex-col text-sm">
      {rows.map((row) => {
        const node = row.node;
        const geometry = span === undefined ? undefined : bar(node, span, now);
        const fill = FILL[node.kind];
        const label = nodeLabel(node, text);
        const isSelected = node.id === selected;
        return (
          <div
            key={node.id}
            role="treeitem"
            data-node={node.id}
            aria-level={row.depth + 1}
            aria-expanded={row.parent ? !row.collapsed : undefined}
            aria-selected={isSelected}
            tabIndex={node.id === current ? 0 : -1}
            onClick={() => onSelect(node.id)}
            className={cx(
              "grid cursor-pointer grid-cols-[minmax(0,1fr)_minmax(6rem,40%)] items-center gap-3 rounded px-2 py-1 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-focus",
              isSelected ? "bg-active" : "hover:bg-hover",
              node.kind === "step" || node.kind === "compaction" ? "mt-1 font-medium" : "",
            )}
          >
            <div className="flex min-w-0 items-center gap-1.5" style={{ paddingLeft: row.depth * 16 }}>
              {row.parent ? (
                <button
                  type="button"
                  tabIndex={-1}
                  aria-label={row.collapsed ? t.expand(label) : t.collapse(label)}
                  onClick={(event) => {
                    event.stopPropagation();
                    onToggle(node.id, row.collapsed);
                  }}
                  className="rounded text-fg-faint hover:text-fg"
                >
                  {row.collapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />}
                </button>
              ) : (
                <span className="w-3.5 shrink-0" />
              )}
              {/* 名字就是种类名时（输入、system、上下文）不重复显示 */}
              {node.kind !== "step" && label !== t.kinds[node.kind] && <span className="shrink-0 text-xs text-fg-faint">{t.kinds[node.kind]}</span>}
              <span className={cx("min-w-0 truncate", node.state === "error" ? "text-danger" : "text-fg")} title={label}>
                {label}
              </span>
              <StateIcon node={node} />
              <span className="ml-auto shrink-0 pl-2 text-xs tabular-nums text-fg-muted">{node.ms === undefined ? "" : formatMs(node.ms)}</span>
            </div>
            <div className="relative h-3" aria-hidden>
              {geometry !== undefined && fill !== undefined && (
                <div
                  className="absolute top-0 flex h-full overflow-hidden rounded-sm"
                  style={{ left: `${geometry.left * 100}%`, width: `${geometry.width * 100}%`, minWidth: 2 }}
                >
                  {geometry.split !== undefined && (
                    <div className="h-full" style={{ width: `${geometry.split * 100}%`, background: "var(--color-bar-input)" }} />
                  )}
                  <div
                    className={cx("h-full flex-1", node.state === "error" ? "outline outline-1 -outline-offset-1 outline-danger" : "")}
                    style={{ background: node.state === "error" ? "var(--color-danger)" : fill, opacity: node.kind === "step" || node.kind === "compaction" ? 0.6 : 1 }}
                  />
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
