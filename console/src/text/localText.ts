// 插件作者写的文字（console-i18n I10，照 DSH `LocalizedText`）：字符串照原样，带语言的取用户的语言，没有就用英文。

import type { LocalText } from "../api/types";
import type { Language } from "./language";

export function pick(text: LocalText, language: Language): string {
  return typeof text === "string" ? text : (text[language] ?? text.en);
}
