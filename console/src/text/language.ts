// 控制台用哪种语言（console-i18n I1–I3）。
// 没选过时按 navigator.languages：每一项先比整个标签、再只比主语言，都对不上用英文（照 DSH locale index.ts:539-555）。
// 用户选的存在服务器上；浏览器里另存一份，只为刷新时第一帧就用对语言。
// index.html 里有一段同样规则的内联脚本，测试拿同一组输入比对两边。

import { localArea, readStored, removeStored, writeStored } from "../ui/storage";

export type Language = "zh" | "en" | "ja";

/** 设置页的顺序。 */
export const LANGUAGES: readonly Language[] = ["zh", "en", "ja"];

/** 各用自己的语言写，不进字典。 */
export const NAMES: Record<Language, string> = { zh: "中文", en: "English", ja: "日本語" };

export const LANGUAGE_KEY = "tantalum.language";

export function isLanguage(value: unknown): value is Language {
  return typeof value === "string" && (LANGUAGES as readonly string[]).includes(value);
}

/** I2：先整个标签（`ja`），再主语言（`zh-TW` 算 `zh`）；第一个对上的就用，都没有是 `en`。 */
export function detect(tags: readonly string[]): Language {
  for (const tag of tags) {
    const whole = tag.toLowerCase();
    if (isLanguage(whole)) return whole;
    const primary = whole.split("-")[0];
    if (isLanguage(primary)) return primary;
  }
  return "en";
}

/** 浏览器的语言列表；老浏览器只有 navigator.language。 */
export function browserTags(): readonly string[] {
  if (typeof navigator === "undefined") return [];
  if (navigator.languages !== undefined && navigator.languages.length > 0) return navigator.languages;
  return navigator.language === undefined ? [] : [navigator.language];
}

/** `<html lang>`：zh 写成 zh-CN，浏览器据此选字形（I1）。 */
export function htmlLang(language: Language): string {
  return language === "zh" ? "zh-CN" : language;
}

/** 给 Intl 用的 locale。 */
export function intlLocale(language: Language): string {
  return { zh: "zh-CN", en: "en-US", ja: "ja-JP" }[language];
}

export function readCached(storage: Pick<Storage, "getItem"> | null = localArea()): Language | null {
  const stored = readStored(LANGUAGE_KEY, storage);
  return isLanguage(stored) ? stored : null;
}

export function cache(language: Language, storage: Pick<Storage, "setItem"> | null = localArea()): void {
  writeStored(LANGUAGE_KEY, language, storage);
}

export function clearCache(storage: Pick<Storage, "removeItem"> | null = localArea()): void {
  removeStored(LANGUAGE_KEY, storage);
}
