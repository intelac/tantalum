import { useQuery } from "@tanstack/react-query";
import { getTurn, listTurns } from "../api/client";
import { zh } from "../text/zh";
import { statusParts } from "./status";

interface Props {
  /** 最近一次运行；没有时整行只显示占位。 */
  run: string | null;
  /** 运行结束后变化，触发重新读取用量。 */
  ended: boolean;
  contextWindow: number | undefined;
}

/** 输入栏下方：轮数、token、上下文占比。数据来自 journal 回放的模型调用，运行结束后才有最后一次的用量。窄屏只留占比。 */
export function StatusBar({ run, ended, contextWindow }: Props) {
  const turns = useQuery({
    queryKey: ["status", run, ended],
    enabled: run !== null,
    queryFn: async () => {
      const list = (await listTurns(run as string)).turns;
      const last = list[list.length - 1];
      const detail = last === undefined ? undefined : await getTurn(run as string, last.number);
      return { count: list.length, usage: detail?.reply?.usage };
    },
  });

  const parts = statusParts(turns.data?.count ?? 0, turns.data?.usage, contextWindow);
  const segments = [
    { key: "turns", text: parts.turns, narrow: false },
    { key: "tokens", text: parts.tokens, narrow: false },
    { key: "inOut", text: parts.inOut, narrow: false },
    { key: "context", text: parts.context, narrow: true },
  ].filter((segment) => segment.text !== null);

  return (
    <div className="flex h-6 items-center justify-center gap-3 text-xs text-fg-faint" aria-label={zh.trace.usage}>
      {segments.length === 0 && <span>{run === null ? zh.status.noRun : ""}</span>}
      {segments.map((segment, index) => (
        <span key={segment.key} className={segment.narrow ? "" : "hidden sm:inline"}>
          {index > 0 && <span className="mr-3 hidden sm:inline">·</span>}
          {segment.text}
        </span>
      ))}
    </div>
  );
}
