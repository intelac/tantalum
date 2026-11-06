// 检查器的公用小件：字段表、可切换本地时间 / Unix 时间戳的时刻、带页签的正文、来源标注。

import { useState, type ReactNode } from "react";
import type { TraceSource } from "../../api/types";
import { currentText, useText } from "../../text";
import { Tabs } from "../../ui/Tabs";
import { formatLocal, formatUnix } from "../format";

/** 字段表：值为空的行不显示。 */
export function Fields({ rows }: { rows: [string, ReactNode][] }) {
  const shown = rows.filter(([, value]) => value !== undefined && value !== null && value !== "");
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-xs">
      {shown.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-fg-faint">{label}</dt>
          <dd className="min-w-0 break-words text-fg">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-1.5">
      <h3 className="text-[11px] font-medium text-fg-faint">{title}</h3>
      {children}
    </section>
  );
}

/** 时刻：默认 `YYYY-MM-DD HH:MM:SS.mmm`，点击切换 Unix 时间戳（DSH StartedAtValue）。 */
export function Timestamp({ iso }: { iso: string | undefined }) {
  const t = useText().trace;
  const [unix, setUnix] = useState(false);
  if (iso === undefined) return <span className="text-fg-faint">{t.timing.notRecorded}</span>;
  return (
    <button
      type="button"
      onClick={() => setUnix(!unix)}
      title={unix ? t.timing.showLocalTime : t.timing.showUnixTimestamp}
      className="font-mono hover:underline"
    >
      {unix ? formatUnix(iso) : formatLocal(iso)}
    </button>
  );
}

export function sourceText(source: TraceSource | undefined): string {
  const t = currentText().trace;
  if (source === undefined) return t.source.unknown;
  switch (source.type) {
    case "user":
      return t.source.user;
    case "context":
      return t.source.context;
    case "plugin":
      return t.source.plugin(source.name ?? "");
    default:
      return t.source.unknown;
  }
}

export interface Page {
  id: string;
  label: string;
  render: () => ReactNode;
}

/** 页签和正文；切换记录时回到第一个页签由调用方用 `key` 保证。 */
export function TabbedBody({ pages }: { pages: Page[] }) {
  const t = useText().trace;
  const [selected, setSelected] = useState(pages[0]?.id ?? "");
  const current = pages.find((page) => page.id === selected) ?? pages[0];
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <Tabs tabs={pages} selected={current?.id ?? ""} onSelect={setSelected} label={t.details.event} className="shrink-0 overflow-x-auto overflow-y-hidden border-b border-line px-3" />
      <div role="tabpanel" aria-labelledby={`tab-${current?.id ?? ""}`} className="min-h-0 flex-1 overflow-y-auto p-3">
        <div className="flex flex-col gap-4">{current?.render()}</div>
      </div>
    </div>
  );
}

/** 按需加载的请求详情的三种状态。 */
export function Loading({ error }: { error?: Error | null }) {
  const t = useText().trace;
  return <p className="text-xs text-fg-faint">{error ? t.record.loadFailed(error.message) : t.record.loading}</p>;
}
