import { useMutation, useQuery } from "@tanstack/react-query";
import { Minimize2 } from "lucide-react";
import { compactSession, getContextUsage } from "../api/client";
import { compactionPoint, useSettings } from "../settings/useCompaction";
import { useText } from "../text";
import { cx } from "../ui/cx";
import { ContextMeter } from "./ContextMeter";
import { statusParts, WARN_PERCENT } from "./status";
import { TodayUsage } from "./TodayUsage";
import { useTodayUsage } from "./useTodayUsage";

interface Props {
  session: string;
  /** 最近一次运行；没有时整行只显示占位。 */
  run: string | null;
  /** 最近的运行还没结束：这时不能手动压缩。 */
  running: boolean;
  /** 运行状态变化时变，触发重新读取用量。 */
  version: string;
  /** 会话现在的模型的窗口；不知道时用服务器随占用一起回的（上次组装上下文时的）。 */
  contextWindow: number | undefined;
  /** 这个用户的压缩点在当前会话的模型上是多少 token（model-picker）；不知道时按设置接口的（默认模型）。 */
  point?: number;
}

/**
 * 输入栏下方：上下文占比（点开是明细）、今日用量（有每日上限时），右侧是手动压缩按钮。占比来自一个只回数字的轻接口，
 * 不再取整份模型调用。到用户选的自动压缩点的 90% 时变色提示（还没读到设置时按窗口的 80%）。
 */
export function StatusBar({ session, run, running, version, contextWindow, point }: Props) {
  const t = useText();
  const context = useQuery({
    queryKey: ["context", session, run, version],
    enabled: run !== null,
    queryFn: () => getContextUsage(session),
  });
  const today = useTodayUsage(session, running, version);
  const compact = useMutation({ mutationFn: () => compactSession(session) });
  const window = contextWindow ?? context.data?.window;
  const settings = useSettings(window !== undefined && point === undefined);

  const parts = statusParts(context.data?.used, window, point ?? compactionPoint(settings.data) ?? context.data?.limit);
  const hasToday = today.data?.limit !== undefined;
  const canCompact = run !== null && !running;

  return (
    <div className="flex h-6 items-center justify-center gap-3 text-xs text-fg-faint" aria-label={t.trace.usage}>
      {parts.context === null && !hasToday && <span>{run === null ? t.status.noRun : ""}</span>}
      {parts.context !== null && parts.percent !== null && context.data !== undefined && window !== undefined && (
        <ContextMeter
          label={parts.context}
          percent={parts.percent}
          warn={parts.warn}
          hint={parts.warn ? t.status.contextHigh(parts.pointPercent ?? WARN_PERCENT) : undefined}
          usage={context.data}
          window={window}
        />
      )}
      {parts.context !== null && hasToday && <span aria-hidden="true">·</span>}
      <TodayUsage usage={today.data} />
      {run !== null && (
        <button
          type="button"
          aria-label={t.status.compact}
          title={compact.isError ? t.status.compactRejected : t.status.compactHint}
          disabled={!canCompact || compact.isPending}
          onClick={() => compact.mutate()}
          className={cx(
            "flex h-5 w-5 items-center justify-center rounded-xs",
            canCompact ? "text-fg-muted hover:bg-hover hover:text-fg" : "text-fg-faint/50",
          )}
        >
          <Minimize2 size={12} aria-hidden="true" />
        </button>
      )}
      {compact.isError && <span role="status">{t.status.compactRejected}</span>}
    </div>
  );
}
