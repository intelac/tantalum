import { LogOut } from "lucide-react";
import { useText } from "../text";
import { Button } from "../ui/Button";
import { PasswordForm } from "./PasswordForm";

interface Props {
  username: string;
  onLogout: () => void;
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
      <div>
        <Button variant="danger" onClick={onLogout}>
          <LogOut size={16} />
          {t.sidebar.logout}
        </Button>
      </div>
    </div>
  );
}
