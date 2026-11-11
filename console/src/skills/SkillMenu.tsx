import { useEffect, useRef } from "react";
import type { MenuSkill } from "../api/types";
import { useText } from "../text";
import { cx } from "../ui/cx";

/**
 * 输入框上方的技能菜单（skills-wiki 需求 5.1、5.5）：列出能点名的技能，名字和描述。键盘由输入框管（上下键、回车、Tab、Esc），
 * 焦点一直留在输入框里，这里只显示和响应鼠标。`active` 是键盘选中的那一项。
 */
export function SkillMenu({ id, skills, active, onPick, onHover }: {
  id: string;
  skills: readonly MenuSkill[];
  active: number;
  onPick: (skill: MenuSkill) => void;
  onHover: (index: number) => void;
}) {
  const t = useText();
  const current = useRef<HTMLLIElement>(null);
  useEffect(() => {
    current.current?.scrollIntoView?.({ block: "nearest" });
  }, [active]);
  return (
    <ul
      id={id}
      role="listbox"
      aria-label={t.composer.skillMenu}
      className="absolute bottom-full left-0 z-20 mb-1.5 max-h-64 w-full max-w-lg overflow-y-auto rounded-lg border border-line bg-raised py-1 shadow-lg"
    >
      {skills.map((skill, index) => (
        <li
          key={skill.name}
          id={`${id}-${index}`}
          ref={index === active ? current : undefined}
          role="option"
          aria-selected={index === active}
          // 按下时不让输入框失焦；点起来才填入
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => onPick(skill)}
          onMouseMove={() => onHover(index)}
          className={cx("flex cursor-pointer flex-col gap-0.5 px-3 py-1.5", index === active && "bg-active")}
        >
          <span className="flex flex-wrap items-center gap-x-2">
            <span className="font-mono text-xs text-fg">/{skill.name}</span>
            {skill.manualOnly && <span className="rounded-xs bg-muted px-1.5 py-0.5 text-[11px] text-fg-muted">{t.plugins.local.skills.manualOnly}</span>}
          </span>
          <span className="line-clamp-2 text-xs break-words text-fg-muted">{skill.description}</span>
        </li>
      ))}
    </ul>
  );
}
