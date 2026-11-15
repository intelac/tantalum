// 运行详情（observe-page 需求 4）：顶部是整次运行的汇总和重复调用的提示，下面是调用树和时间条，选中节点在右边看详情
// （窄屏叠在树上面）。运行还在进行时每 3 秒刷新。

import { useEffect, useMemo, useState } from "react";
import { ApiError } from "../api/client";
import { navigate, runsPath, sessionPath, tracePath } from "../app/router";
import { useText } from "../text";
import { errorLine } from "../text/errors";
import { formatLocal, formatMs, formatTokens } from "../trace/format";
import { Button } from "../ui/Button";
import { Switch } from "../ui/Switch";
import { EndBadge } from "./ends";
import { NodeDetail } from "./NodeDetail";
import { RunTree } from "./RunTree";
import { axis, defaultCollapsed, flatten, pathTo, repeats } from "./tree";
import { useRunTree } from "./useRuns";
import type { RunEnd, RunNode } from "../api/types";

function endOf(root: RunNode): RunEnd {
  if (root.state === "running" || root.state === "waiting") return "RUNNING";
  if (root.state === "error") return "FAILED";
  if (root.state === "cancelled") return "CANCELLED";
  return "COMPLETED";
}

function find(root: RunNode, id: string): RunNode | undefined {
  return pathTo(root, id).at(-1);
}

export function RunPage({ session, run }: { session: string; run: string }) {
  const text = useText();
  const t = text.observe;
  const tree = useRunTree(session, run);
  const root = tree.data?.root;
  const [collapsed, setCollapsed] = useState<Set<string> | null>(null);
  const [errorsOnly, setErrorsOnly] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => setNow(Date.now()), [tree.dataUpdatedAt]);
  // 第一次拿到树时定下默认折叠，之后用户的展开折叠不被刷新冲掉
  useEffect(() => {
    if (root !== undefined && collapsed === null) setCollapsed(defaultCollapsed(root));
  }, [root, collapsed]);

  const rows = useMemo(() => (root === undefined ? [] : flatten(root, collapsed ?? new Set(), errorsOnly)), [root, collapsed, errorsOnly]);
  const found = useMemo(() => (root === undefined ? [] : repeats(root)), [root]);
  const span = root === undefined ? undefined : axis(root, now);
  const node = root === undefined || selected === null ? undefined : find(root, selected);

  const select = (id: string) => {
    if (root === undefined) return;
    // 选中被折叠起来的节点时把它的祖先都展开
    const ancestors = pathTo(root, id).slice(0, -1).map((item) => item.id);
    setCollapsed((current) => {
      const next = new Set(current ?? []);
      for (const ancestor of ancestors) next.delete(ancestor);
      return next;
    });
    setSelected(id);
  };
  const toggle = (id: string, open: boolean) =>
    setCollapsed((current) => {
      const next = new Set(current ?? []);
      if (open) next.delete(id);
      else next.add(id);
      return next;
    });

  if (tree.isPending) return <p className="p-4 text-xs text-fg-faint">{text.common.loading}</p>;
  if (tree.error !== null && root === undefined) {
    const missing = tree.error instanceof ApiError && tree.error.status === 404;
    return (
      <div className="flex flex-col items-center gap-3 py-16 text-center text-sm text-fg-muted">
        <p role="alert" className={missing ? undefined : "text-danger"}>
          {missing ? t.run.notFound : errorLine(tree.error)}
        </p>
        <Button onClick={() => navigate(runsPath())}>{t.run.backToList}</Button>
      </div>
    );
  }
  if (root === undefined) return null;
  const tally = root.tally;
  const steps = (root.children ?? []).filter((child) => child.kind === "step").length;
  const tokens = tally === undefined ? 0 : tally.tokens.input + tally.tokens.output + tally.tokens.cacheRead + tally.tokens.cacheWrite;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="flex flex-col gap-3 border-b border-line px-4 py-3 md:px-6">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <EndBadge end={endOf(root)} />
          {root.start !== undefined && <span className="text-xs tabular-nums text-fg-muted">{formatLocal(root.start)}</span>}
          {root.detail !== undefined && <span className="min-w-0 truncate text-xs text-danger">{root.detail}</span>}
          <div className="ml-auto flex flex-wrap gap-2">
            <Button size="sm" onClick={() => navigate(sessionPath(session))}>
              {t.run.openConversation}
            </Button>
            <Button size="sm" onClick={() => navigate(tracePath(session, run))}>
              {t.run.openTrace}
            </Button>
          </div>
        </div>
        {tally !== undefined && (
          <dl className="flex flex-wrap gap-x-6 gap-y-1 text-xs">
            {(
              [
                [t.run.tally.duration, tally.ms === undefined ? "–" : formatMs(tally.ms)],
                [t.run.tally.steps, String(steps)],
                [t.run.tally.requests, `${tally.requests}${tally.retries > 0 ? ` (+${tally.retries})` : ""}`],
                [t.run.tally.toolCalls, String(tally.toolCalls)],
                [t.run.tally.toolErrors, String(tally.toolErrors)],
                [t.summary.tokens, formatTokens(tokens)],
                [t.run.tally.context, tally.context === undefined ? "–" : formatTokens(tally.context)],
                [t.run.tally.models, tally.models.join(", ") || "–"],
              ] as const
            ).map(([label, value]) => (
              <div key={label} className="flex gap-1.5">
                <dt className="text-fg-faint">{label}</dt>
                <dd className={label === t.run.tally.toolErrors && tally.toolErrors > 0 ? "tabular-nums text-danger" : "tabular-nums text-fg"}>
                  {value}
                </dd>
              </div>
            ))}
          </dl>
        )}
        {found.length > 0 && (
          <div role="note" aria-label={t.run.repeatsLabel} className="flex flex-col gap-1">
            {found.map((repeat) => (
              <button
                key={repeat.first}
                type="button"
                onClick={() => select(repeat.first)}
                className="self-start rounded-md border border-warning/50 px-2 py-1 text-left text-xs text-fg hover:bg-hover focus-visible:outline-2 focus-visible:outline-focus"
              >
                <span className="font-medium text-warning">{t.run.repeatsLabel}</span> · {t.run.repeats(repeat.name, repeat.count, repeat.fromStep, repeat.toStep)}
              </button>
            ))}
          </div>
        )}
      </header>
      <div className="relative flex min-h-0 flex-1">
        <div className="min-h-0 min-w-0 flex-1 overflow-y-auto px-2 py-2 md:px-4">
          <div className="flex items-center gap-4 px-2 pb-2 text-xs text-fg-muted">
            <label className="inline-flex items-center gap-2">
              <Switch checked={errorsOnly} onChange={setErrorsOnly} label={t.run.errorsOnly} />
              {t.run.errorsOnly}
            </label>
            <span className="ml-auto inline-flex items-center gap-3" aria-hidden>
              <span className="inline-flex items-center gap-1">
                <span className="h-2 w-3 rounded-sm" style={{ background: "var(--color-bar-input)" }} />
                {t.run.waitingFirst}
              </span>
              <span className="inline-flex items-center gap-1">
                <span className="h-2 w-3 rounded-sm" style={{ background: "var(--color-bar-model)" }} />
                {t.run.generating}
              </span>
              <span className="inline-flex items-center gap-1">
                <span className="h-2 w-3 rounded-sm" style={{ background: "var(--color-bar-tool)" }} />
                {t.run.kinds.tool}
              </span>
            </span>
          </div>
          <RunTree rows={rows} span={span} now={now} selected={selected} onSelect={select} onToggle={toggle} />
        </div>
        {node !== undefined && (
          <aside className="absolute inset-0 z-10 overflow-y-auto bg-canvas p-4 min-[900px]:static min-[900px]:w-80 min-[900px]:shrink-0 min-[900px]:border-l min-[900px]:border-line">
            <NodeDetail node={node} session={session} onClose={() => setSelected(null)} />
          </aside>
        )}
      </div>
    </div>
  );
}
