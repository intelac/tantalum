import { Brand } from "../shell/Brand";
import { zh } from "../text/zh";

/** 会话没有消息时居中的空状态，输入栏由页面放在它下面。 */
export function EmptyState() {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3 px-4 text-center">
      <Brand size="lg" symbolOnly className="opacity-80" />
      <h2 className="text-xl font-semibold text-fg">{zh.conversation.emptyTitle}</h2>
      <p className="max-w-sm text-sm text-fg-muted">{zh.conversation.emptyHint}</p>
    </div>
  );
}
