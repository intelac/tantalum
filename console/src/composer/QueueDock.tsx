import { ListOrdered } from "lucide-react";
import { useMemo } from "react";
import type { ConversationStore } from "../store/ConversationStore";
import { useItems, useOrder } from "../store/hooks";
import { zh } from "../text/zh";

interface Props {
  store: ConversationStore;
  session: string;
}

/** 输入栏上方的排队内容：数量和每条的开头。运行开始后条目不再排队，这里自动消失。 */
export function QueueDock({ store, session }: Props) {
  const order = useOrder(store, session);
  // 条目的种类不会变，所以只需订阅用户条目；是否排队看条目自己的 queued。
  const users = useMemo(() => order.filter((id) => store.getItem(id)?.kind === "user"), [order, store]);
  const items = useItems(store, users);
  const queued = items.filter((item) => item?.kind === "user" && item.queued);
  if (queued.length === 0) return null;
  return (
    <div className="mb-2 rounded-md border border-line bg-muted px-3 py-2 text-xs text-fg-muted" role="status" aria-label={zh.composer.queueTitle}>
      <div className="flex items-center gap-1.5 font-medium text-fg">
        <ListOrdered size={13} aria-hidden="true" />
        {zh.composer.queued(queued.length)}
      </div>
      <ol className="mt-1 flex flex-col gap-0.5 pl-5" style={{ listStyle: "decimal" }}>
        {queued.map((item) => (
          <li key={item?.id} className="truncate">
            {item?.kind === "user" ? item.text : ""}
          </li>
        ))}
      </ol>
    </div>
  );
}
