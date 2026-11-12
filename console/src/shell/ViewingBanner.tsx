import { Eye } from "lucide-react";
import { setViewedUser, useViewedUser } from "../api/viewing";
import { useText } from "../text";
import { Button } from "../ui/Button";

/**
 * 管理员只读查看别人的会话时，页面最上面一直有的一条（qagent-align 需求 6.1）：在看谁、只读、怎么退出。
 * 没在看别人时什么都没有。
 */
export function ViewingBanner() {
  const t = useText();
  const viewed = useViewedUser();
  if (viewed === null) return null;
  return (
    <div role="status" className="flex shrink-0 flex-wrap items-center justify-center gap-x-3 gap-y-1 bg-warning/20 px-4 py-1.5 text-sm text-fg">
      <span className="flex min-w-0 items-center gap-1.5 font-medium">
        <Eye size={16} className="shrink-0" aria-hidden="true" />
        <span className="min-w-0 break-words">{t.viewing.banner(viewed)}</span>
      </span>
      <Button size="sm" variant="secondary" onClick={() => setViewedUser(null)}>
        {t.viewing.exit}
      </Button>
    </div>
  );
}

/** 顶替输入框的那一条：只读，不能发消息。还没选会话时说怎么选；他一个会话都没有时照实说。 */
export function ViewingBar({ selected, any }: { selected: boolean; any: boolean }) {
  const t = useText();
  const viewed = useViewedUser();
  if (viewed === null) return null;
  return (
    <div className="mx-auto w-full max-w-[var(--content-width)] shrink-0 px-4 pb-4">
      <p role="note" className="rounded-2xl border border-line bg-raised px-4 py-3 text-sm text-fg-muted">
        {selected ? t.viewing.readOnly(viewed) : any ? t.viewing.pick : t.viewing.none(viewed)}
      </p>
    </div>
  );
}
