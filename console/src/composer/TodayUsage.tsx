import type { TodayUsageView } from "../api/types";
import { useText } from "../text";
import { cx } from "../ui/cx";
import { grouped, quota } from "./status";

/**
 * 状态行上的“今日 已用 / 上限”，带一小段进度条。只在有每日上限时显示（上限是开着的插件报的）。
 * 到上限的 90% 变提醒色，用完变危险色；窄屏只留百分比，全数在悬停说明里。服务器配了折算系数时写“今日折算”。
 */
export function TodayUsage({ usage }: { usage: TodayUsageView | undefined }) {
  const t = useText();
  if (usage?.limit === undefined) return null;
  const { percent, level } = quota(usage.used, usage.limit);
  const used = grouped(usage.used);
  const limit = grouped(usage.limit);
  const bar = level === "over" ? "bg-danger" : level === "warn" ? "bg-warning" : "bg-fg-faint";
  // 配了折算系数时写明是折算后的数，悬停说明里给出原始用量（qagent-align 需求 3.7）；没配时一个字不多
  const weighed = usage.weighted === true;
  const hint = level === "over" ? t.status.todayOver(used, limit) : t.status.todayHint(used, limit, percent);
  const raw = grouped(usage.input + usage.output + usage.cacheRead + usage.cacheWrite);
  return (
    <span
      role="status"
      title={weighed ? `${hint}。${t.status.weighedNote(raw)}` : hint}
      className={cx("flex items-center gap-1.5", level === "over" ? "font-medium text-danger" : level === "warn" ? "font-medium text-warning" : "")}
    >
      <span className="hidden tabular-nums sm:inline">{weighed ? t.status.todayWeighed(used, limit) : t.status.today(used, limit)}</span>
      <span className="tabular-nums sm:hidden">{t.status.todayPercent(percent)}</span>
      <span className="h-1 w-10 overflow-hidden rounded-full bg-line" aria-hidden="true">
        <span className={cx("block h-full", bar)} style={{ width: `${Math.min(100, percent)}%` }} />
      </span>
    </span>
  );
}
