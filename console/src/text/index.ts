// 当前语言和字典（console-i18n I4）。组件在渲染时用 useText() 取当前语言的字典，语言变了就重新渲染，
// 不重新挂载：设置窗口不关，草稿不丢。不是组件的代码在调用时用 currentText()。
// 不许在模块顶层取字典（text/literals.test.ts 抓），否则切换语言后那份文字不会变。

import { useSyncExternalStore } from "react";
import type { Text } from "./dictionary";
import { en } from "./en";
import { ja } from "./ja";
import { type Language, browserTags, cache, clearCache, detect, htmlLang, readCached } from "./language";
import { zh } from "./zh";

export type { Text } from "./dictionary";
export type { Language } from "./language";

// 三种语言都打进主包，不按需加载（设计“定下的两件事”第 2 条）
const dictionaries: Record<Language, Text> = { zh, en, ja };

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

/** 当前语言的字典。 */
export function currentText(): Text {
  return dictionaries[current];
}

/** 换语言：写 `<html lang>`，通知订阅者。存不存由调用方决定（设置页存服务器，登录时写缓存）。 */
export function setLanguage(language: Language): void {
  document.documentElement.lang = htmlLang(language);
  if (language === current) return;
  current = language;
  emit();
}

/** 跟随浏览器时用哪种。 */
export function browserLanguage(): Language {
  return detect(browserTags());
}

/**
 * 按服务器上存的选择换语言（登录、`/api/auth/me` 返回后，退出登录时传 null）：存过就用它并写进浏览器缓存，
 * 让下次刷新第一帧就对；没存过或没登录时跟浏览器，删掉缓存（console-i18n I3）。检测到的语言不写回服务器。
 */
export function applyStoredLanguage(stored: Language | null): void {
  if (stored !== null) {
    cache(stored);
    setLanguage(stored);
  } else {
    clearCache();
    setLanguage(browserLanguage());
  }
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
