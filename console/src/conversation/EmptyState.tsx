import type { Config } from "../api/types";
import { Brand } from "../shell/Brand";
import { useConfig } from "../shell/useConfig";
import { useLanguage, useText } from "../text";

/** 管理员配的首屏那句话：先找当前语言的，再用默认那句；都没有（或者是空的）返回 undefined，用控制台自带的。 */
export function welcomeText(welcome: Config["welcome"], language: string): string | undefined {
  const own = welcome?.byLanguage?.[language];
  if (typeof own === "string" && own.trim() !== "") return own;
  const shared = welcome?.text;
  return typeof shared === "string" && shared.trim() !== "" ? shared : undefined;
}

/** 会话没有消息时居中的空状态，输入栏由页面放在它下面。标题固定，下面那句管理员能配；当纯文本显示。 */
export function EmptyState() {
  const t = useText();
  const language = useLanguage();
  const config = useConfig();
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3 px-4 text-center">
      <Brand size="lg" symbolOnly className="opacity-80" />
      <h2 className="text-xl font-semibold text-fg">{t.conversation.emptyTitle}</h2>
      <p className="max-w-sm text-sm break-words text-fg-muted">{welcomeText(config.data?.welcome, language) ?? t.conversation.emptyHint}</p>
    </div>
  );
}
