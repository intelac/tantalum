import type { Account } from "../api/types";
import { useText } from "../text";
import { formatNumber } from "../text/format";
import { cx } from "../ui/cx";

/** 运维表格里今日用量离上限多远：80% 起警示，到 100% 是危险（qagent-align 需求 4.2）。 */
export function usageLevel(used: number, limit: number): { percent: number; level: "ok" | "warn" | "over" } {
  const percent = limit <= 0 ? 100 : Math.floor((used / limit) * 100);
  return { percent, level: used >= limit ? "over" : percent >= 80 ? "warn" : "ok" };
}

/**
 * 今日用量：数字；这个人有每日上限时再加百分比和进度条。悬停是四项原始数字。
 * 服务器配了折算系数时这个数是折算后的，列名上写明，这里不重复。
 */
export function UsageCell({ account }: { account: Account }) {
  const t = useText();
  const usage = account.usageToday;
  if (usage === undefined) return null;
  const limit = account.limit?.tokens;
  const detail = t.users.usageDetail(formatNumber(usage.input), formatNumber(usage.output), formatNumber(usage.cacheRead), formatNumber(usage.cacheWrite));
  if (limit === undefined) {
    return (
      <span className="tabular-nums" title={detail}>
        {formatNumber(usage.total)}
      </span>
    );
  }
  const { percent, level } = usageLevel(usage.total, limit);
  const bar = level === "over" ? "bg-danger" : level === "warn" ? "bg-warning" : "bg-fg-faint";
  return (
    <span className="flex flex-col items-end gap-1" title={detail}>
      <span className={cx("tabular-nums", level === "over" ? "font-medium text-danger" : level === "warn" ? "font-medium text-warning" : "")}>
        {formatNumber(usage.total)}
        <span className="ml-1.5 text-xs">{percent > 999 ? t.users.usageOver : t.users.usagePercent(percent)}</span>
      </span>
      <span
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.min(100, percent)}
        aria-label={t.users.columns.usage}
        data-level={level}
        className="h-1 w-20 overflow-hidden rounded-full bg-line"
      >
        <span className={cx("block h-full", bar)} style={{ width: `${Math.min(100, percent)}%` }} />
      </span>
    </span>
  );
}
