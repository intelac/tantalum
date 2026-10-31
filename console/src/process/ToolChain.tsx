import { useMemo } from "react";
import type { ToolStepItem } from "../api/types";
import type { ConversationStore } from "../store/ConversationStore";
import { useItems } from "../store/hooks";
import { chainOf } from "./chain";
import { ToolGroup } from "./ToolGroup";

interface Props {
  store: ConversationStore;
  /** 连续的工具步骤 ID，按发生顺序。 */
  ids: readonly string[];
}

/** 一段连续的工具调用：并行的成组，独占的单行，等待中的也显示。 */
export function ToolChain({ store, ids }: Props) {
  const items = useItems(store, ids);
  const groups = useMemo(() => chainOf(items.filter((item): item is ToolStepItem => item?.kind === "tool")), [items]);
  return (
    <div className="flex flex-col">
      {groups.map((group) => (
        <ToolGroup key={group[0]?.id} steps={group} />
      ))}
    </div>
  );
}
