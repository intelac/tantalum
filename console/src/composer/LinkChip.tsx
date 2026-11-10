import { useQuery } from "@tanstack/react-query";
import { MonitorX } from "lucide-react";
import { getLocal } from "../api/client";
import type { LocalStatus } from "../api/types";
import { LOCAL_KEY } from "../plugins/useLocal";
import { within } from "../projects/pathKey";
import { type Text, useText } from "../text";

/** 插件页开着时它每 2 秒取一次；输入框旁只要慢一点跟上。 */
const POLL_MS = 15_000;

/** 有问题时要说的那句；一切正常为 null（workspaces 需求 7.2）。 */
export function linkProblem(status: LocalStatus | undefined, dir: string | null, t: Text): string | null {
  if (status === undefined) return null;
  if (!status.switches["local-workspace"]) return t.projects.filesOff;
  if (status.link === null) return t.projects.notConnected;
  if (status.link.current === false) return t.projects.oldConnector;
  if (dir !== null && !status.link.roots.some((root) => within(dir, root.path))) return t.projects.notAllowed;
  return null;
}

interface Props {
  /** 这个会话（或将要用）的工作目录；没有时只在电脑相关的毛病上提示。 */
  dir: string | null;
  onOpen: () => void;
}

/**
 * 电脑那边有问题时，输入框旁一个小标签：文件工具关着、电脑没连上、连接程序要更新、电脑不允许这个目录。点它打开插件页。
 * 会话不在工作区里时不提示，不用本机工作区的人看不到它。
 */
export function LinkChip({ dir, onOpen }: Props) {
  const t = useText();
  const local = useQuery({ queryKey: LOCAL_KEY, queryFn: getLocal, refetchInterval: POLL_MS, enabled: dir !== null });
  const problem = dir === null ? null : linkProblem(local.data, dir, t);
  if (problem === null) return null;
  return (
    <button
      type="button"
      onClick={onOpen}
      title={t.projects.linkHint}
      className="flex max-w-48 items-center gap-1 rounded-md bg-warning/10 px-2 py-1 text-xs text-warning hover:bg-warning/20"
    >
      <MonitorX size={14} className="shrink-0" aria-hidden="true" />
      <span className="truncate max-sm:sr-only">{problem}</span>
    </button>
  );
}
