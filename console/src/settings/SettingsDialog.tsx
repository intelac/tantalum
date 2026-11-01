import { Brain, Cpu, Palette, UserRound } from "lucide-react";
import { useState } from "react";
import type { Config } from "../api/types";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";
import { Dialog } from "../ui/Dialog";
import { AccountPane } from "./AccountPane";
import { AppearancePane } from "./AppearancePane";
import { MemoryPane } from "./MemoryPane";
import { ModelPane } from "./ModelPane";

type Pane = "appearance" | "model" | "memory" | "account";

interface Props {
  open: boolean;
  onClose: () => void;
  config: Config | undefined;
  username: string;
  onLogout: () => void;
}

const PANES: { id: Pane; label: string; icon: typeof Palette }[] = [
  { id: "appearance", label: zh.settings.appearance, icon: Palette },
  { id: "model", label: zh.settings.model, icon: Cpu },
  { id: "memory", label: zh.memory.title, icon: Brain },
  { id: "account", label: zh.settings.account, icon: UserRound },
];

/** 居中大圆角弹窗，左导航右内容。窄屏时导航变成顶部一行。 */
export function SettingsDialog({ open, onClose, config, username, onLogout }: Props) {
  const [pane, setPane] = useState<Pane>("appearance");
  return (
    <Dialog open={open} onClose={onClose} title={zh.settings.title} className="max-w-3xl">
      <div className="flex min-h-[26rem] flex-col sm:flex-row">
        <nav aria-label={zh.settings.title} className="flex gap-1 px-4 py-3 sm:w-44 sm:flex-col sm:py-4">
          {PANES.map(({ id, label, icon: Icon }) => (
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
              {label}
            </button>
          ))}
        </nav>
        <div className="min-w-0 flex-1 px-6 pt-2 pb-6 sm:pt-4">
          {pane === "appearance" && <AppearancePane />}
          {pane === "model" && <ModelPane config={config} />}
          {pane === "memory" && <MemoryPane />}
          {pane === "account" && <AccountPane username={username} onLogout={onLogout} />}
        </div>
      </div>
    </Dialog>
  );
}
