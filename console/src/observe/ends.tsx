// 结束状态的样子：颜色加图标加文字，不只靠颜色（observe-page 需求 2.3）。颜色是主题的状态色；
// 取消和叫停用灰色（亮色主题用 fg-muted，对比度够）。堆叠柱的配色用 dataviz 的校验脚本查过相邻两段的色盲区分度。

import { CheckCircle2, CircleSlash, Loader2, OctagonX, XCircle } from "lucide-react";
import type { EndReason, RunEnd } from "../api/types";
import { useText } from "../text";
import { cx } from "../ui/cx";

/** SVG 填充用的 CSS 变量。 */
export const END_FILL: Record<RunEnd, string> = {
  COMPLETED: "var(--color-success)",
  RUNNING: "var(--color-accent)",
  CANCELLED: "var(--color-fg-muted)",
  STOPPED: "var(--color-fg-muted)",
  FAILED: "var(--color-danger)",
};

const TEXT: Record<RunEnd, string> = {
  COMPLETED: "text-success",
  RUNNING: "text-accent",
  CANCELLED: "text-fg-muted",
  STOPPED: "text-fg-muted",
  FAILED: "text-danger",
};

const ICON = { COMPLETED: CheckCircle2, RUNNING: Loader2, CANCELLED: CircleSlash, STOPPED: OctagonX, FAILED: XCircle } satisfies Record<RunEnd, unknown>;

export function runEnd(end: string | undefined, reason: EndReason | undefined): RunEnd {
  return end === undefined || reason === undefined ? "RUNNING" : reason;
}

export function EndBadge({ end, className }: { end: RunEnd; className?: string }) {
  const t = useText();
  const Icon = ICON[end];
  return (
    <span className={cx("inline-flex items-center gap-1 whitespace-nowrap text-xs font-medium", TEXT[end], className)}>
      <Icon size={13} aria-hidden className={end === "RUNNING" ? "motion-safe:animate-spin" : undefined} />
      {t.observe.ends[end]}
    </span>
  );
}
