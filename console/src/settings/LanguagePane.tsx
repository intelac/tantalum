import { Check } from "lucide-react";
import type { Language } from "../api/types";
import { browserLanguage, useLanguage, useText } from "../text";
import { LANGUAGES, NAMES, htmlLang } from "../text/language";
import { cx } from "../ui/cx";
import { useLanguageChoice } from "./useLanguage";

/** 一个选项：null 是跟随浏览器。 */
type Choice = Language | null;

const CHOICES: readonly Choice[] = [null, ...LANGUAGES];

/** 设置页的“语言”（console-i18n I3、I4）：跟随浏览器或三种语言之一，选了当场生效，存在你的账号上。 */
export function LanguagePane() {
  const t = useText();
  // 订阅当前语言：跟随浏览器那一项后面写现在检测到的语言名
  useLanguage();
  const { choice, save } = useLanguageChoice();
  const shown = save.isPending ? save.variables : choice;
  const name = (option: Choice) => (option === null ? t.language.browser(NAMES[browserLanguage()]) : NAMES[option]);
  return (
    <div className="flex flex-col gap-3">
      <h3 className="text-sm font-medium text-fg">{t.settings.language}</h3>
      <div role="radiogroup" aria-label={t.settings.language} className="grid grid-cols-2 gap-3">
        {CHOICES.map((option) => {
          const selected = option === shown;
          return (
            <button
              key={option ?? "browser"}
              type="button"
              role="radio"
              aria-checked={selected}
              lang={option === null ? undefined : htmlLang(option)}
              // 保存期间不用 disabled：禁用会让焦点掉到 body，Esc 就关不了设置窗口
              aria-disabled={save.isPending}
              onClick={() => {
                if (!save.isPending) save.mutate(option);
              }}
              className={cx(
                "flex h-12 items-center justify-between gap-2 rounded-md border px-3 text-sm transition-colors duration-[var(--duration-fast)]",
                selected ? "border-fg bg-active text-fg" : "border-line bg-raised text-fg-muted hover:bg-hover",
              )}
            >
              {name(option)}
              {selected && <Check size={16} aria-hidden="true" />}
            </button>
          );
        })}
      </div>
      <p className="text-xs text-fg-faint">{t.language.hint}</p>
      {save.isError && (
        <p role="alert" className="text-xs text-danger">
          {t.language.saveFailed}
        </p>
      )}
    </div>
  );
}
