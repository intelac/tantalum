import type { TodayUsageView } from "../api/types";
import { useText } from "../text";
import { cx } from "../ui/cx";
import { grouped, quota } from "./status";

/**
 * 状态行上的“今日 已用 / 上限”，带一小段进度条。只在有每日上限时显示（上限是开着的插件报的）。
 * 到上限的 90% 变提醒色，用完变危险色；窄屏只留百分比，全数在悬停说明里。
 */
export function TodayUsage({ usage }: { usage: TodayUsageView | undefined }) {
  const t = useText();
  if (usage?.limit === undefined) return null;
  const { percent, level } = quota(usage.used, usage.limit);
  const used = grouped(usage.used);
  const limit = grouped(usage.limit);
  const bar = level === "over" ? "bg-danger" : level === "warn" ? "bg-warning" : "bg-fg-faint";
  return (
    <span
      role="status"
      title={level === "over" ? t.status.todayOver(used, limit) : t.status.todayHint(used, limit, percent)}
      className={cx("flex items-center gap-1.5", level === "over" ? "font-medium text-danger" : level === "warn" ? "font-medium text-warning" : "")}
    >
      <span className="hidden tabular-nums sm:inline">{t.status.today(used, limit)}</span>
      <span className="tabular-nums sm:hidden">{t.status.todayPercent(percent)}</span>
      <span className="h-1 w-10 overflow-hidden rounded-full bg-line" aria-hidden="true">
        <span className={cx("block h-full", bar)} style={{ width: `${Math.min(100, percent)}%` }} />
      </span>
    </span>
  );
}
