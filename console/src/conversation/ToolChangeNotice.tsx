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

/** 联网的工具：browser_* 合成“浏览器（N 个工具）”一项，免得一行列 11 个名字（web-tools）。 */
function webNames(list: readonly string[]): string {
  const browser = list.filter((name) => name.startsWith("browser_"));
  const rest = list.filter((name) => !name.startsWith("browser_"));
  return [...rest, ...(browser.length > 0 ? [zh.conversation.toolsBrowser(browser.length)] : [])].join("、");
}

/**
 * 这轮开始时工具变了：一行灰色小字，写明新增的、关掉的和服务器上已经没有的（one-line-journal 需求 5）。
 * 本机工作区的工具（来源 local）不能用了，多半是电脑没连上或连接程序没允许，原因单独写（local-workspace）；
 * 联网的工具（来源 web）是在插件页关了联网的开关，也单独写（web-tools 需求 5.2）。
 */
export function ToolChangeNotice({ store, id }: Props) {
  const item = useItem(store, id);
  if (item === undefined || item.kind !== "toolChange") return null;
  const local = item.disabled.filter((name) => item.sources?.[name] === "local");
  const web = item.disabled.filter((name) => item.sources?.[name] === "web");
  const off = item.disabled.filter((name) => item.sources?.[name] !== "local" && item.sources?.[name] !== "web");
  const parts = [
    item.added.length > 0 ? zh.conversation.toolsAdded(names(item.added)) : null,
    off.length > 0 ? zh.conversation.toolsDisabled(names(off)) : null,
    local.length > 0 ? zh.conversation.toolsLocalOff(names(local)) : null,
    web.length > 0 ? zh.conversation.toolsWebOff(webNames(web)) : null,
    item.gone.length > 0 ? zh.conversation.toolsGone(names(item.gone)) : null,
  ].filter((part): part is string => part !== null);
  if (parts.length === 0) return null;
  return (
    <p role="note" className="my-1 text-xs text-fg-faint" title={[...item.added, ...item.disabled, ...item.gone].join(", ")}>
      {zh.conversation.toolsChanged}：{parts.join("；")}
    </p>
  );
}
