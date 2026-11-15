import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { PendingRow } from "../settings/PendingSection";
import { PENDING_KEY, usePending } from "../settings/useMemories";
import { useText } from "../text";

interface Props {
  session: string;
  /** 这个会话正在跑：跑完以后记笔记还要一会儿，隔几秒再取几次。 */
  running: boolean;
}

/** 一轮结束后多久再取：记笔记在后台，本机模型十几秒。 */
const AFTER_RUN_MS = [5_000, 20_000, 60_000];

/**
 * 输入框上方的“记住了：… 确认 / 撤销”（memory-final-plan 第 7 节）：这个会话里等你确认的记忆。捕获在一轮结束后才做，
 * 所以在下一轮开始前出现。记忆插件关着或没有时什么都不显示。
 */
export function PendingStrip({ session, running }: Props) {
  const t = useText().memory.pending;
  const pending = usePending();
  const queries = useQueryClient();
  useEffect(() => {
    if (running) return;
    const timers = AFTER_RUN_MS.map((ms) => setTimeout(() => void queries.invalidateQueries({ queryKey: PENDING_KEY }), ms));
    return () => timers.forEach(clearTimeout);
  }, [running, queries]);
  const mine = (pending.data ?? []).filter((item) => item.session === session);
  if (mine.length === 0) return null;
  return (
    <section aria-label={t.strip} className="mb-2 rounded-xl border border-line bg-raised">
      <p className="px-3 pt-2 text-xs font-medium text-fg-muted">{t.strip}</p>
      <ul className="flex flex-col divide-y divide-line">
        {mine.slice(0, 3).map((item) => (
          <li key={item.id}>
            <PendingRow item={item} />
          </li>
        ))}
      </ul>
      {mine.length > 3 && <p className="px-3 pb-2 text-xs text-fg-faint">{t.more(mine.length - 3)}</p>}
    </section>
  );
}
