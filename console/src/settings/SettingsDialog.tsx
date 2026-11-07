import { Brain, Cpu, Languages, Palette, UserRound, Users } from "lucide-react";
import { useState } from "react";
import type { Config, ModelChoice } from "../api/types";
import { type Text, useText } from "../text";
import { cx } from "../ui/cx";
import { Dialog } from "../ui/Dialog";
import { AccountPane } from "./AccountPane";
import { AppearancePane } from "./AppearancePane";
import { LanguagePane } from "./LanguagePane";
import { MemoryPane } from "./MemoryPane";
import { ModelPane } from "./ModelPane";
import { UsersPane } from "./UsersPane";

type Pane = "appearance" | "language" | "model" | "memory" | "account" | "users";

interface Props {
  open: boolean;
  onClose: () => void;
  config: Config | undefined;
  /** 当前会话选的模型，模型页按它算压缩点。 */
  choice?: ModelChoice | null;
  username: string;
  /** 管理员多一个“用户”页（ops-hardening-2 需求 3.2、3.3）。 */
  admin?: boolean;
  onLogout: () => void;
}

const PANES: { id: Pane; icon: typeof Palette; adminOnly?: boolean }[] = [
  { id: "appearance", icon: Palette },
  { id: "language", icon: Languages },
  { id: "model", icon: Cpu },
  { id: "memory", icon: Brain },
  { id: "account", icon: UserRound },
  { id: "users", icon: Users, adminOnly: true },
];

/** 导航上的页名，渲染时按当前语言取。 */
function label(t: Text, pane: Pane): string {
  if (pane === "memory") return t.memory.title;
  if (pane === "users") return t.users.title;
  return t.settings[pane];
}

/** 居中大圆角弹窗，左导航右内容。窄屏时导航变成顶部一行。 */
export function SettingsDialog({ open, onClose, config, choice = null, username, admin = false, onLogout }: Props) {
  const t = useText();
  const [pane, setPane] = useState<Pane>("appearance");
  const panes = PANES.filter((candidate) => admin || candidate.adminOnly !== true);
  return (
    <Dialog open={open} onClose={onClose} title={t.settings.title} className="max-w-3xl">
      <div className="flex min-h-[26rem] flex-col sm:flex-row">
        <nav aria-label={t.settings.title} className="flex gap-1 px-4 py-3 sm:w-44 sm:flex-col sm:py-4">
          {panes.map(({ id, icon: Icon }) => (
            <button
              key={id}
              type="button"
              aria-current={pane === id ? "page" : undefined}
              onClick={() => setPane(id)}
              className={cx(
                "flex h-9 items-center gap-2 rounded-md px-3 text-sm",
                pane === id ? "bg-active text-fg" : "text-fg-muted hover:bg-hover hover:text-fg",
              )}
            >
              <Icon size={16} aria-hidden="true" />
              {label(t, id)}
            </button>
          ))}
        </nav>
        <div className="min-w-0 flex-1 px-6 pt-2 pb-6 sm:pt-4">
          {pane === "appearance" && <AppearancePane />}
          {pane === "language" && <LanguagePane />}
          {pane === "model" && <ModelPane config={config} choice={choice} />}
          {pane === "memory" && <MemoryPane />}
          {pane === "account" && <AccountPane username={username} onLogout={onLogout} />}
          {pane === "users" && admin && <UsersPane self={username} />}
        </div>
      </div>
    </Dialog>
  );
}
