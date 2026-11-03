import { useSyncExternalStore } from "react";
import type { Pending, UploadQueue } from "./uploads";

/** 订阅一条草稿的附件；队列变了就重绘。 */
export function useQueue(queue: UploadQueue): readonly Pending[] {
  return useSyncExternalStore(
    (listener) => queue.subscribe(listener),
    () => queue.items(),
  );
}
