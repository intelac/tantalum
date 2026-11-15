// 观测页面的筛选栏（observe-page 需求 2.1、2.2）：一行，窄屏换行。改了就写回地址。

import { RefreshCw } from "lucide-react";
import type { RunEnd } from "../api/types";
import { useText } from "../text";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import { Switch } from "../ui/Switch";
import { END_FILL } from "./ends";
import { ENDS, SORTS, WINDOWS, type Filters } from "./filters";

interface Props {
  filters: Filters;
  tools: readonly string[];
  models: readonly string[];
  onChange: (next: Filters) => void;
  onRefresh: () => void;
  refreshing: boolean;
}

const field = "h-7 rounded-md border border-line bg-raised px-2 text-xs text-fg focus-visible:outline-2 focus-visible:outline-focus";

export function FilterBar({ filters, tools, models, onChange, onRefresh, refreshing }: Props) {
  const t = useText().observe;
  const toggle = (end: RunEnd) =>
    onChange({ ...filters, ends: filters.ends.includes(end) ? filters.ends.filter((value) => value !== end) : [...filters.ends, end] });
  // 地址里的工具、模型这段时间没出现过也留着，地址说了算
  const toolOptions = filters.tool !== undefined && !tools.includes(filters.tool) ? [filters.tool, ...tools] : tools;
  const modelOptions = filters.model !== undefined && !models.includes(filters.model) ? [filters.model, ...models] : models;
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2" role="group" aria-label={t.summary.label}>
      <div role="radiogroup" aria-label={t.window} className="inline-flex rounded-md border border-line bg-raised p-0.5">
        {WINDOWS.map((window) => (
          <button
            key={window}
            type="button"
            role="radio"
            aria-checked={filters.window === window}
            onClick={() => onChange({ ...filters, window })}
            className={cx(
              "h-6 rounded px-2 text-xs focus-visible:outline-2 focus-visible:outline-focus",
              filters.window === window ? "bg-active font-medium text-fg" : "text-fg-muted hover:text-fg",
            )}
          >
            {t.windows[window]}
          </button>
        ))}
      </div>
      <div role="group" aria-label={t.endsLabel} className="flex flex-wrap items-center gap-1">
        {ENDS.map((end) => (
          <button
            key={end}
            type="button"
            aria-pressed={filters.ends.includes(end)}
            onClick={() => toggle(end)}
            className={cx(
              "inline-flex h-6 items-center gap-1.5 rounded-full border px-2 text-xs focus-visible:outline-2 focus-visible:outline-focus",
              filters.ends.includes(end) ? "border-line-strong bg-active text-fg" : "border-line text-fg-muted hover:text-fg",
            )}
          >
            <span aria-hidden className="h-2 w-2 rounded-full" style={{ background: END_FILL[end] }} />
            {t.ends[end]}
          </button>
        ))}
      </div>
      <label className="inline-flex items-center gap-2 text-xs text-fg-muted">
        <Switch checked={filters.toolErrors} onChange={(toolErrors) => onChange({ ...filters, toolErrors })} label={t.toolErrors} />
        {t.toolErrors}
      </label>
      <div className="flex flex-wrap items-center gap-2">
        <select
          aria-label={t.tool}
          className={field}
          value={filters.tool ?? ""}
          onChange={(event) => onChange({ ...filters, tool: event.target.value === "" ? undefined : event.target.value })}
        >
          <option value="">{t.anyTool}</option>
          {toolOptions.map((tool) => (
            <option key={tool} value={tool}>
              {tool}
            </option>
          ))}
        </select>
        <select
          aria-label={t.model}
          className={field}
          value={filters.model ?? ""}
          onChange={(event) => onChange({ ...filters, model: event.target.value === "" ? undefined : event.target.value })}
        >
          <option value="">{t.anyModel}</option>
          {modelOptions.map((model) => (
            <option key={model} value={model}>
              {model}
            </option>
          ))}
        </select>
        <select
          aria-label={t.sort}
          className={field}
          value={filters.sort}
          onChange={(event) => onChange({ ...filters, sort: event.target.value as Filters["sort"] })}
        >
          {SORTS.map((sort) => (
            <option key={sort} value={sort}>
              {t.sort} · {t.sorts[sort]}
            </option>
          ))}
        </select>
        <Button variant="icon" size="sm" aria-label={t.refresh} title={t.refresh} onClick={onRefresh}>
          <RefreshCw size={14} className={refreshing ? "motion-safe:animate-spin" : undefined} />
        </Button>
      </div>
    </div>
  );
}
