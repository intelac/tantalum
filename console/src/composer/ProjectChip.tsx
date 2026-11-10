import { Check, ChevronDown, Folder, FolderPlus } from "lucide-react";
import type { Project } from "../api/types";
import { useText } from "../text";
import { Menu } from "../ui/Menu";

interface Props {
  projects: readonly Project[];
  /** 选中的工作区；null 是“不选”。 */
  value: Project | null;
  /** 会话已经发出第一句：只显示名字，不能换（workspaces W12）。 */
  fixed: boolean;
  onChange: (project: Project | null) => void;
  onAdd: () => void;
}

/** 输入框下面的工作区标签。还没有工作区时写“选择工作区”，点开直接添加。 */
export function ProjectChip({ projects, value, fixed, onChange, onAdd }: Props) {
  const t = useText();
  const name = value?.title ?? (projects.length === 0 ? t.projects.chooseProject : t.projects.none);
  if (fixed) {
    if (value === null) return null;
    return (
      <span className="flex max-w-48 items-center gap-1 truncate text-xs text-fg-muted" title={t.projects.fixed(value.path)}>
        <Folder size={14} className="shrink-0" aria-hidden="true" />
        <span className="truncate max-sm:sr-only">{value.title}</span>
      </span>
    );
  }
  const mark = (selected: boolean) => (selected ? <Check size={16} /> : <span className="w-4" />);
  const items = [
    ...projects.map((project) => ({ id: project.id, label: project.title, icon: mark(value?.id === project.id), onSelect: () => onChange(project) })),
    { id: "none", label: t.projects.none, icon: mark(value === null), onSelect: () => onChange(null) },
    { id: "add", label: t.projects.addProject, icon: <FolderPlus size={16} />, onSelect: onAdd },
  ];
  return (
    <Menu
      label={`${t.projects.chip}: ${name}`}
      items={items}
      direction="up"
      buttonProps={{ variant: "ghost", size: "sm", className: "max-w-48 gap-1 px-2 text-xs text-fg-muted", title: value?.path ?? name }}
    >
      <Folder size={14} className="shrink-0" aria-hidden="true" />
      <span className="truncate max-sm:sr-only">{name}</span>
      <ChevronDown size={12} className="shrink-0" aria-hidden="true" />
    </Menu>
  );
}
