import { LogOut } from "lucide-react";
import { zh } from "../text/zh";
import { Button } from "../ui/Button";

interface Props {
  username: string;
  onLogout: () => void;
}

/** 当前用户和退出登录。退出后服务端会话立即失效，SSE 断开。 */
export function AccountPane({ username, onLogout }: Props) {
  return (
    <div className="flex flex-col gap-3">
      <h3 className="text-sm font-medium text-fg">{zh.settings.account}</h3>
      <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
        <dt className="text-fg-muted">{zh.settings.loggedInAs}</dt>
        <dd className="font-mono text-fg">{username}</dd>
      </dl>
      <div>
        <Button variant="danger" onClick={onLogout}>
          <LogOut size={16} />
          {zh.sidebar.logout}
        </Button>
      </div>
    </div>
  );
}
