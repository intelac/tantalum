import { useMutation } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { stopTasks } from "../api/client";
import type { TaskItem, TaskResultItem } from "../api/types";
import { useViewedUser } from "../api/viewing";
import { useText } from "../text";
import { errorLine } from "../text/errors";
import { Button } from "../ui/Button";

interface Props {
  session: string;
  /** 还没回来的任务。 */
  open: readonly TaskItem[];
  /** 自动继续到上限之后回来、还没处理的结果。 */
  paused: readonly TaskResultItem[];
}

/**
 * 输入框上方（subagent 需求 10.3、10.4）：有任务在外面时一行“N 个任务进行中 · 全部停止”；
 * 自动继续到了上限时一条提示，说明说一句话就接着做。都没有时不显示。
 */
export function TaskStrip({ session, open, paused }: Props) {
  const t = useText().tasks;
  const viewing = useViewedUser() !== null;
  const stop = useMutation({ mutationFn: () => stopTasks(session) });
  if (open.length === 0 && paused.length === 0) return null;
  return (
    <div className="mb-2 flex flex-col gap-1.5 text-[13px]">
      {paused.length > 0 && (
        <p role="status" className="rounded-sm bg-warning/15 px-2.5 py-1.5 text-fg">
          {t.paused(paused.length)}
        </p>
      )}
      {open.length > 0 && (
        <div role="status" className="flex flex-wrap items-center gap-2 px-1 text-fg-muted">
          <Loader2 size={14} className="shrink-0 animate-spin text-accent" aria-hidden="true" />
          <span>{t.count(open.length)}</span>
          {!viewing && (
            <Button variant="ghost" size="sm" className="ml-auto" disabled={stop.isPending} onClick={() => stop.mutate()}>
              {t.stopAll}
            </Button>
          )}
        </div>
      )}
      {stop.error !== null && (
        <p role="alert" className="px-1 text-xs text-danger">
          {errorLine(stop.error)}
        </p>
      )}
    </div>
  );
}
