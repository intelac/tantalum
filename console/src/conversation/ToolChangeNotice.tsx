import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { currentText, useText } from "../text";

interface Props {
  store: ConversationStore;
  id: string;
}

/** 名字太多时只列前几个：一组工具一起不能用（比如电脑断开、旧会话里 MCP 服务器不在了）。 */
const SHOWN = 5;

function names(list: readonly string[]): string {
  const t = currentText();
  return list.length <= SHOWN ? list.join("、") : t.conversation.toolsMore(list.slice(0, SHOWN).join("、"), list.length);
}

/**
 * browser_* 合成“浏览器（N 个工具）”一项，免得一行列 11 个名字（新增的也一样：browser-on-demand 调过入口后一次加 9 个）。浏览器在用户电脑上（local-browser），所以这些工具和别的本机工具
 * 一起出现在“本机工作区”那一条里；浏览器还在服务器上时记下的旧会话里，它们的来源是 web，写法一样。
 */
function grouped(list: readonly string[]): string {
  const t = currentText();
  const browser = list.filter((name) => name.startsWith("browser_"));
  const rest = list.filter((name) => !name.startsWith("browser_"));
  if (browser.length === 0) return names(rest);
  const group = t.conversation.toolsBrowser(browser.length);
  return rest.length === 0 ? group : `${names(rest)}、${group}`;
}

/**
 * 这轮开始时工具变了：一行灰色小字，写明新增的、关掉的和服务器上已经没有的（one-line-journal 需求 5）。
 * 本机工作区的工具（来源 local）不能用了，多半是电脑没连上或连接程序没允许，原因单独写（local-workspace）；
 * 联网的工具（来源 web）是在插件页关了联网的开关，也单独写（web-tools 需求 5.2）。
 */
export function ToolChangeNotice({ store, id }: Props) {
  const t = useText();
  const item = useItem(store, id);
  if (item === undefined || item.kind !== "toolChange") return null;
  const local = item.disabled.filter((name) => item.sources?.[name] === "local");
  const web = item.disabled.filter((name) => item.sources?.[name] === "web");
  const off = item.disabled.filter((name) => item.sources?.[name] !== "local" && item.sources?.[name] !== "web");
  const parts = [
    item.added.length > 0 ? t.conversation.toolsAdded(grouped(item.added)) : null,
    off.length > 0 ? t.conversation.toolsDisabled(names(off)) : null,
    local.length > 0 ? t.conversation.toolsLocalOff(grouped(local)) : null,
    web.length > 0 ? t.conversation.toolsWebOff(grouped(web)) : null,
    item.gone.length > 0 ? t.conversation.toolsGone(names(item.gone)) : null,
  ].filter((part): part is string => part !== null);
  if (parts.length === 0) return null;
  return (
    <p role="note" className="my-1 text-xs text-fg-faint" title={[...item.added, ...item.disabled, ...item.gone].join(", ")}>
      {t.conversation.toolsChangedList(parts)}
    </p>
  );
}
