import { Monitor, Moon, Sun } from "lucide-react";
import { useTheme, type Theme } from "../theme/theme";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";

const OPTIONS: { theme: Theme; label: string; icon: typeof Sun }[] = [
  { theme: "light", label: zh.settings.light, icon: Sun },
  { theme: "dark", label: zh.settings.dark, icon: Moon },
  { theme: "system", label: zh.settings.system, icon: Monitor },
];

/** 三张卡片选浅色、深色、跟随系统；选择立即生效并保留。 */
export function AppearancePane() {
  const [theme, setTheme] = useTheme();
  return (
    <div className="flex flex-col gap-3">
      <h3 className="text-sm font-medium text-fg">{zh.settings.appearance}</h3>
      <div role="radiogroup" aria-label={zh.settings.appearance} className="grid grid-cols-3 gap-3">
        {OPTIONS.map(({ theme: option, label, icon: Icon }) => {
          const selected = option === theme;
          return (
            <button
              key={option}
              type="button"
              role="radio"
              aria-checked={selected}
              onClick={() => setTheme(option)}
              className={cx(
                "flex h-20 flex-col items-center justify-center gap-2 rounded-md border text-sm transition-colors duration-[var(--duration-fast)]",
                selected ? "border-fg bg-active text-fg" : "border-line bg-raised text-fg-muted hover:bg-hover",
              )}
            >
              <Icon size={18} aria-hidden="true" />
              {label}
            </button>
          );
        })}
      </div>
      <p className="text-xs text-fg-faint">{zh.settings.appearanceHint}</p>
    </div>
  );
}
