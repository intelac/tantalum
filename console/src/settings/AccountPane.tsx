import { useQuery } from "@tanstack/react-query";
import { LogOut } from "lucide-react";
import { getViews } from "../api/client";
import { relativeTime } from "../text/format";
import { useText } from "../text";
import { Button } from "../ui/Button";
import { PasswordForm } from "./PasswordForm";

interface Props {
  username: string;
  onLogout: () => void;
}

/** 管理员只读查看过我的哪些会话（memory-final-plan 第 13 节）。 */
function ViewsSection() {
  const t = useText().settings;
  const views = useQuery({ queryKey: ["views"], queryFn: getViews, select: (data) => data.views });
  return (
    <section aria-label={t.views} className="flex flex-col gap-2">
      <h4 className="text-sm font-medium text-fg">{t.views}</h4>
      <p className="text-xs text-fg-faint">{t.viewsHint}</p>
      {views.data !== undefined && views.data.length === 0 && <p className="text-sm text-fg-muted">{t.viewsEmpty}</p>}
      {views.data !== undefined && views.data.length > 0 && (
        <ul className="flex max-h-48 flex-col gap-1 overflow-auto text-sm">
          {views.data.map((view) => (
            <li key={`${view.at}-${view.viewer}-${view.session ?? ""}`} className="flex justify-between gap-3">
              <span className="min-w-0 break-words text-fg">{t.viewsList(view.viewer, view.session ?? "")}</span>
              <span className="shrink-0 text-xs text-fg-faint">{relativeTime(view.at)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** 当前用户、修改密码和退出登录。退出后这次登录立即失效，SSE 断开。 */
export function AccountPane({ username, onLogout }: Props) {
  const t = useText();
  return (
    <div className="flex flex-col gap-3">
      <h3 className="text-sm font-medium text-fg">{t.settings.account}</h3>
      <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
        <dt className="text-fg-muted">{t.settings.loggedInAs}</dt>
        <dd className="font-mono text-fg">{username}</dd>
      </dl>
      <PasswordForm />
      <ViewsSection />
      <div>
        <Button variant="danger" onClick={onLogout}>
          <LogOut size={16} />
          {t.sidebar.logout}
        </Button>
      </div>
    </div>
  );
}
