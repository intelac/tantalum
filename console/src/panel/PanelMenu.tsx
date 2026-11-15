// 顶栏的“面板”按钮（plugin-panel 需求 2.1）：有能用的面板时才显示，菜单列出面板，开着的打勾；再选一次就关。

import { Check, PanelRight } from "lucide-react";
import type { PanelInfo } from "../api/types";
import { useLanguage, useText } from "../text";
import { pick } from "../text/localText";
import { Menu } from "../ui/Menu";
import { PanelIcon } from "./PanelLayout";

interface Props {
  panels: readonly PanelInfo[];
  open: string | null;
  onOpen: (name: string | null) => void;
}

export function PanelMenu({ panels, open, onOpen }: Props) {
  const t = useText().panel;
  const language = useLanguage();
  if (panels.length === 0) return null;
  return (
    <Menu
      label={t.menu}
      align="end"
      buttonProps={{ variant: "icon", size: "sm", title: t.menu, "aria-label": t.menu }}
      items={panels.map((panel) => ({
        id: panel.name,
        label: pick(panel.title, language),
        icon: panel.name === open ? <Check size={14} /> : <PanelIcon name={panel.icon} size={14} />,
        onSelect: () => onOpen(panel.name === open ? null : panel.name),
      }))}
    >
      <PanelRight size={16} />
    </Menu>
  );
}
