// 当前语言和字典（console-i18n I4）。组件在渲染时用 useText() 取当前语言的字典，语言变了就重新渲染，
// 不重新挂载：设置窗口不关，草稿不丢。不是组件的代码在调用时用 currentText()。
// 不许在模块顶层取字典（text/literals.test.ts 抓），否则切换语言后那份文字不会变。

import { useSyncExternalStore } from "react";
import type { Text } from "./dictionary";
import { type Language, browserTags, detect, htmlLang, readCached } from "./language";
import { zh } from "./zh";

export type { Text } from "./dictionary";
export type { Language } from "./language";

const dictionaries: Partial<Record<Language, Text>> = { zh };

let current: Language = readCached() ?? detect(browserTags());
const listeners = new Set<() => void>();

/** 换一份字典。只给测试用：临时注册一份假字典，看组件是否跟着换。 */
export function registerDictionary(language: Language, text: Text): void {
  dictionaries[language] = text;
  if (language === current) emit();
}

function emit(): void {
  for (const listener of listeners) listener();
}

export function currentLanguage(): Language {
  return current;
}

/** 当前语言的字典；还没有这种语言时用中文。 */
export function currentText(): Text {
  return dictionaries[current] ?? zh;
}

/** 换语言：写 `<html lang>`，通知订阅者。存不存由调用方决定（设置页存服务器，登录时写缓存）。 */
export function setLanguage(language: Language): void {
  document.documentElement.lang = htmlLang(language);
  if (language === current) return;
  current = language;
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useLanguage(): Language {
  return useSyncExternalStore(subscribe, currentLanguage, currentLanguage);
}

export function useText(): Text {
  return useSyncExternalStore(subscribe, currentText, currentText);
}

/** 按服务器给的值查文字：字典里的表键是固定的几个，服务器的值可能是新的，查不到返回 undefined。 */
export function lookup<V>(table: Readonly<Record<string, V>>, key: string): V | undefined {
  return Object.hasOwn(table, key) ? table[key] : undefined;
}
