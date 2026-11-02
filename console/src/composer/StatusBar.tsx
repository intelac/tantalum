import { useMutation, useQuery } from "@tanstack/react-query";
import { Minimize2 } from "lucide-react";
import { compactSession, getTurn, listTurns } from "../api/client";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";
import { statusParts } from "./status";

interface Props {
  session: string;
  /** 最近一次运行；没有时整行只显示占位。 */
  run: string | null;
  /** 最近的运行还没结束：这时不能手动压缩。 */
  running: boolean;
  /** 运行状态变化时变，触发重新读取用量。 */
  version: string;
  contextWindow: number | undefined;
}

/**
 * 输入栏下方：轮数、token、上下文占比，右侧是手动压缩按钮。数据来自 journal 回放的模型调用，运行结束后才有最后一次的用量。
 * 占比到 80% 变色提示。窄屏只留占比。
 */
export function StatusBar({ session, run, running, version, contextWindow }: Props) {
  const turns = useQuery({
    queryKey: ["turns", run, version],
    enabled: run !== null,
    queryFn: async () => {
      const list = (await listTurns(run as string)).turns;
      const last = list[list.length - 1];
      const detail = last === undefined ? undefined : await getTurn(run as string, last.number);
      return { count: list.length, usage: detail?.reply?.usage };
    },
  });
  const compact = useMutation({ mutationFn: () => compactSession(session) });

  const parts = statusParts(turns.data?.count ?? 0, turns.data?.usage, contextWindow);
  const segments = [
    { key: "turns", text: parts.turns, narrow: false },
    { key: "tokens", text: parts.tokens, narrow: false },
    { key: "inOut", text: parts.inOut, narrow: false },
    { key: "context", text: parts.context, narrow: true },
  ].filter((segment) => segment.text !== null);
  const canCompact = run !== null && !running;

  return (
    <div className="flex h-6 items-center justify-center gap-3 text-xs text-fg-faint" aria-label={zh.trace.usage}>
      {segments.length === 0 && <span>{run === null ? zh.status.noRun : ""}</span>}
      {segments.map((segment, index) => (
        <span
          key={segment.key}
          className={cx(segment.narrow ? "" : "hidden sm:inline", segment.key === "context" && parts.warn ? "font-medium text-danger" : "")}
          title={segment.key === "context" && parts.warn ? zh.status.contextHigh : undefined}
        >
          {index > 0 && <span className="mr-3 hidden sm:inline">·</span>}
          {segment.text}
        </span>
      ))}
      {run !== null && (
        <button
          type="button"
          aria-label={zh.status.compact}
          title={compact.isError ? zh.status.compactRejected : zh.status.compactHint}
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
      {compact.isError && <span role="status">{zh.status.compactRejected}</span>}
    </div>
  );
}
