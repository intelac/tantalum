import { Brand } from "../shell/Brand";
import { useText } from "../text";

/** 会话没有消息时居中的空状态，输入栏由页面放在它下面。 */
export function EmptyState() {
  const t = useText();
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3 px-4 text-center">
      <Brand size="lg" symbolOnly className="opacity-80" />
      <h2 className="text-xl font-semibold text-fg">{t.conversation.emptyTitle}</h2>
      <p className="max-w-sm text-sm text-fg-muted">{t.conversation.emptyHint}</p>
    </div>
  );
}
