// 树形 JSON：对象和数组可折叠，长字符串最多预览 12 行；每个节点可复制值、JSON 和路径，整体可复制格式化或紧凑 JSON（DSH jsonTreeLabels）。

import { ChevronDown, ChevronRight } from "lucide-react";
import { useState } from "react";
import { useText } from "../../text";
import { cx } from "../../ui/cx";

export const STRING_PREVIEW_LINES = 12;

function copy(text: string): void {
  void navigator.clipboard?.writeText(text).catch(() => {});
}

/** `$.a[0].b` 形式的路径；键名不是标识符时用 `["键"]`。 */
export function childPath(parent: string, key: string | number): string {
  if (typeof key === "number") return `${parent}[${key}]`;
  return /^[A-Za-z_$][\w$]*$/.test(key) ? `${parent}.${key}` : `${parent}[${JSON.stringify(key)}]`;
}

function NodeActions({ value, path }: { value: unknown; path: string }) {
  const t = useText().trace;
  const button = "rounded-xs px-1 text-[10px] text-fg-faint hover:bg-hover hover:text-fg";
  return (
    <span className="ml-2 hidden gap-0.5 group-hover:inline-flex group-focus-within:inline-flex">
      <button type="button" className={button} onClick={() => copy(typeof value === "string" ? value : JSON.stringify(value))}>
        {t.copy.value}
      </button>
      <button type="button" className={button} onClick={() => copy(JSON.stringify(value, null, 2))}>
        {t.copy.json}
      </button>
      <button type="button" className={button} onClick={() => copy(path)}>
        {t.copy.path}
      </button>
    </span>
  );
}

function StringValue({ value }: { value: string }) {
  const t = useText().trace;
  const lines = value.split("\n");
  const [open, setOpen] = useState(false);
  const long = lines.length > STRING_PREVIEW_LINES;
  const shown = long && !open ? lines.slice(0, STRING_PREVIEW_LINES).join("\n") : value;
  return (
    <span className="whitespace-pre-wrap break-words text-success">
      {JSON.stringify(shown).slice(0, -1)}
      {long && !open ? "…" : ""}
      {'"'}
      {long && (
        <button type="button" onClick={() => setOpen(!open)} className="ml-2 text-[10px] text-accent hover:underline">
          {open ? t.record.showLess : t.record.showMore(lines.length - STRING_PREVIEW_LINES)}
        </button>
      )}
    </span>
  );
}

function Leaf({ value }: { value: unknown }) {
  if (typeof value === "string") return <StringValue value={value} />;
  if (value === null) return <span className="text-fg-faint">null</span>;
  if (typeof value === "number") return <span className="text-accent">{String(value)}</span>;
  if (typeof value === "boolean") return <span className="text-warning">{String(value)}</span>;
  return <span>{JSON.stringify(value)}</span>;
}

function Node({ name, value, path, depth }: { name?: string; value: unknown; path: string; depth: number }) {
  const container = typeof value === "object" && value !== null;
  const [open, setOpen] = useState(depth < 2);
  const entries: [string | number, unknown][] = !container
    ? []
    : Array.isArray(value)
      ? value.map((item, index) => [index, item])
      : Object.entries(value as Record<string, unknown>);
  const label = name === undefined ? null : <span className="text-fg-muted">{name}: </span>;
  if (!container) {
    return (
      <div className="group pl-4 leading-5" title={path}>
        {label}
        <Leaf value={value} />
        <NodeActions value={value} path={path} />
      </div>
    );
  }
  const brackets = Array.isArray(value) ? ["[", "]"] : ["{", "}"];
  return (
    <div className="leading-5">
      <div className="group flex items-center" title={path}>
        <button type="button" aria-expanded={open} onClick={() => setOpen(!open)} className="w-4 shrink-0 text-fg-faint hover:text-fg">
          {open ? <ChevronDown size={12} aria-hidden /> : <ChevronRight size={12} aria-hidden />}
        </button>
        {label}
        <span className="text-fg-faint">
          {brackets[0]}
          {!open && `${entries.length > 0 ? "…" : ""}${brackets[1]}`}
          {!open && <span className="ml-1 text-[10px]">{entries.length}</span>}
        </span>
        <NodeActions value={value} path={path} />
      </div>
      {open && (
        <div className="ml-2 border-l border-line pl-1">
          {entries.map(([key, child]) => (
            <Node key={String(key)} name={String(key)} value={child} path={childPath(path, key)} depth={depth + 1} />
          ))}
          <div className="pl-4 text-fg-faint">{brackets[1]}</div>
        </div>
      )}
    </div>
  );
}

export function JsonTree({ value, className }: { value: unknown; className?: string }) {
  const t = useText().trace;
  const button = "rounded-xs px-1.5 py-0.5 text-[10px] text-fg-faint hover:bg-hover hover:text-fg";
  return (
    <div className={cx("rounded-md border border-line bg-code p-2 font-mono text-xs", className)}>
      <div className="mb-1 flex justify-end gap-1">
        <button type="button" className={button} onClick={() => copy(JSON.stringify(value, null, 2))}>
          {t.copy.formatted}
        </button>
        <button type="button" className={button} onClick={() => copy(JSON.stringify(value))}>
          {t.copy.compact}
        </button>
      </div>
      <Node value={value} path="$" depth={0} />
    </div>
  );
}

/** 文字是完整的 JSON 对象或数组时解析出来，否则为 undefined。 */
export function parseJsonContainer(text: string | undefined): unknown {
  if (text === undefined) return undefined;
  const trimmed = text.trim();
  if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return undefined;
  try {
    return JSON.parse(trimmed) as unknown;
  } catch {
    return undefined;
  }
}
