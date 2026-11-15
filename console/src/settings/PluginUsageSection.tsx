import { useState } from "react";
import { useText } from "../text";
import { formatNumber } from "../text/format";
import { usePluginUsage } from "./useUsers";

const SPANS = [1, 7, 30] as const;

/**
 * 运维页下方：插件自己调模型的用量（memory-final-plan 第 10 节“插件用量单独记账”）。每人、每个插件、每种用途一行，
 * 按输入加输出从多到少排；可以看最近 1、7、30 天。读不到（旧服务器、不是管理员）时只显示一行提示。
 */
export function PluginUsageSection() {
  const t = useText();
  const text = t.users.pluginUsage;
  const [days, setDays] = useState<number>(7);
  const usage = usePluginUsage(days);
  const rows = [...(usage.data?.totals ?? [])].sort((a, b) => b.input + b.output - (a.input + a.output));
  return (
    <section aria-label={text.title} className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between gap-3">
        <h4 className="text-sm font-medium text-fg">{text.title}</h4>
        <div role="radiogroup" aria-label={text.days} className="flex gap-1">
          {SPANS.map((span) => (
            <button
              key={span}
              type="button"
              role="radio"
              aria-checked={days === span}
              onClick={() => setDays(span)}
              className={
                days === span
                  ? "rounded-sm bg-hover px-2 py-0.5 text-xs text-fg"
                  : "rounded-sm px-2 py-0.5 text-xs text-fg-faint hover:bg-hover hover:text-fg"
              }
            >
              {span}
            </button>
          ))}
        </div>
      </div>
      <p className="text-xs text-fg-faint">{text.hint(days)}</p>
      {usage.isError && <p className="text-sm text-fg-faint">{text.loadFailed}</p>}
      {usage.data !== undefined && rows.length === 0 && <p className="text-sm text-fg-faint">{text.empty}</p>}
      {rows.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[560px] border-collapse text-sm">
            <thead>
              <tr className="text-left text-xs text-fg-muted">
                <th className="py-1 pr-4 font-normal">{text.user}</th>
                <th className="py-1 pr-4 font-normal">{text.plugin}</th>
                <th className="py-1 pr-4 font-normal">{text.purpose}</th>
                <th className="py-1 pr-4 text-right font-normal">{text.calls}</th>
                <th className="py-1 pr-4 text-right font-normal">{text.failures}</th>
                <th className="py-1 pr-4 text-right font-normal">{text.input}</th>
                <th className="py-1 text-right font-normal">{text.output}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {rows.map((row) => (
                <tr key={`${row.user}/${row.plugin}/${row.purpose}`}>
                  <td className="py-1 pr-4 text-fg">{row.user}</td>
                  <td className="py-1 pr-4 font-mono text-fg-muted">{row.plugin}</td>
                  <td className="py-1 pr-4 font-mono text-fg-muted">{row.purpose}</td>
                  <td className="py-1 pr-4 text-right tabular-nums">{formatNumber(row.calls)}</td>
                  <td className={row.failures > 0 ? "py-1 pr-4 text-right text-warning tabular-nums" : "py-1 pr-4 text-right text-fg-faint tabular-nums"}>
                    {formatNumber(row.failures)}
                  </td>
                  <td className="py-1 pr-4 text-right tabular-nums">{formatNumber(row.input)}</td>
                  <td className="py-1 text-right tabular-nums">{formatNumber(row.output)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
