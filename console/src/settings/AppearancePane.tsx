import { Monitor, Moon, Sun } from "lucide-react";
import { useTheme, type Theme } from "../theme/theme";
import { useText } from "../text";
import { cx } from "../ui/cx";

// 卡片名在渲染时按主题取（t.settings.light 等），切换语言后跟着变
const OPTIONS: { theme: Theme; icon: typeof Sun }[] = [
  { theme: "light", icon: Sun },
  { theme: "dark", icon: Moon },
  { theme: "system", icon: Monitor },
];

/** 三张卡片选浅色、深色、跟随系统；选择立即生效并保留。 */
export function AppearancePane() {
  const t = useText();
  const [theme, setTheme] = useTheme();
  return (
    <div className="flex flex-col gap-3">
      <h3 className="text-sm font-medium text-fg">{t.settings.appearance}</h3>
      <div role="radiogroup" aria-label={t.settings.appearance} className="grid grid-cols-3 gap-3">
        {OPTIONS.map(({ theme: option, icon: Icon }) => {
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
              {t.settings[option]}
            </button>
          );
        })}
      </div>
      <p className="text-xs text-fg-faint">{t.settings.appearanceHint}</p>
    </div>
  );
}
