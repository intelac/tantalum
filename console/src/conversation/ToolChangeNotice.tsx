import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { zh } from "../text/zh";

interface Props {
  store: ConversationStore;
  id: string;
}

/** 名字太多时只列前几个：一个 MCP 服务器不在了，几十个工具一起不能用。 */
const SHOWN = 5;

function names(list: readonly string[]): string {
  return list.length <= SHOWN ? list.join("、") : zh.conversation.toolsMore(list.slice(0, SHOWN).join("、"), list.length);
}

/**
 * 这轮开始时工具变了：一行灰色小字，写明新增的、关掉的和服务器上已经没有的（one-line-journal 需求 5）。
 * 本机工作区的工具（来源 local）不能用了，多半是电脑没连上或连接程序没允许，原因单独写（local-workspace）。
 */
export function ToolChangeNotice({ store, id }: Props) {
  const item = useItem(store, id);
  if (item === undefined || item.kind !== "toolChange") return null;
  const local = item.disabled.filter((name) => item.sources?.[name] === "local");
  const off = item.disabled.filter((name) => item.sources?.[name] !== "local");
  const parts = [
    item.added.length > 0 ? zh.conversation.toolsAdded(names(item.added)) : null,
    off.length > 0 ? zh.conversation.toolsDisabled(names(off)) : null,
    local.length > 0 ? zh.conversation.toolsLocalOff(names(local)) : null,
    item.gone.length > 0 ? zh.conversation.toolsGone(names(item.gone)) : null,
  ].filter((part): part is string => part !== null);
  if (parts.length === 0) return null;
  return (
    <p role="note" className="my-1 text-xs text-fg-faint" title={[...item.added, ...item.disabled, ...item.gone].join(", ")}>
      {zh.conversation.toolsChanged}：{parts.join("；")}
    </p>
  );
}
