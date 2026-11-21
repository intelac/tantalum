import { useCallback, useRef, useState } from "react";
import { ApiError, changeQueue } from "../../api/client";
import type { UserItem } from "../../api/types";

export interface QueueActions {
  /** 有操作还没返回：队列条上的按钮全部不可用。 */
  busy: boolean;
  /** 上一次操作的失败，原样留着，显示时才变成文字。 */
  error: unknown;
  edit: (item: string, text: string) => Promise<boolean>;
  remove: (item: string) => Promise<boolean>;
  steer: (item: string) => Promise<boolean>;
  /** 按顺序把排队的全部转成插话；中途有一条已经不在了就跳过它接着转。 */
  steerAll: (items: readonly UserItem[]) => Promise<void>;
}

function gone(failure: unknown): boolean {
  return failure instanceof ApiError && failure.code === "queue/gone";
}

/** 排队消息的三个操作（input-area 需求 1）。结果不在这里改数据：服务器处理完，对话的事件流会把那一条推过来。 */
export function useQueueActions(session: string): QueueActions {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const running = useRef(false);

  const run = useCallback(async (work: () => Promise<void>): Promise<boolean> => {
    if (running.current) return false;
    running.current = true;
    setBusy(true);
    setError(null);
    try {
      await work();
      return true;
    } catch (failure) {
      setError(failure);
      return false;
    } finally {
      running.current = false;
      setBusy(false);
    }
  }, []);

  const edit = useCallback((item: string, text: string) => run(async () => void (await changeQueue(session, item, "edit", text))), [run, session]);
  const remove = useCallback((item: string) => run(async () => void (await changeQueue(session, item, "remove"))), [run, session]);
  const steer = useCallback((item: string) => run(async () => void (await changeQueue(session, item, "join"))), [run, session]);
  const steerAll = useCallback(
    async (items: readonly UserItem[]) => {
      await run(async () => {
        for (const item of items) {
          try {
            await changeQueue(session, item.id, "join");
          } catch (failure) {
            // 这一条刚好轮到了：它已经在发了，不算失败
            if (!gone(failure)) throw failure;
          }
        }
      });
    },
    [run, session],
  );

  return { busy, error, edit, remove, steer, steerAll };
}
