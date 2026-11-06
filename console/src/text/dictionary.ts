// 字典的类型（console-i18n I5）。zh.ts 是键的来源，en.ts、ja.ts 写成 `const en: Text = {…}`：
// 少一条、多一条、函数的参数不对、元组的长度不对，tsc 就失败。照 DSH `ui-trajectory/src/client/locales.ts:210-224`。

import type { zh } from "./zh";

/** 把 `as const` 的字面量类型放宽成“结构相同的文字”。 */
export type Dictionary<T> = T extends string
  ? string
  : T extends (...args: infer A) => string
    ? (...args: A) => string
    : T extends readonly unknown[]
      ? { readonly [K in keyof T]: Dictionary<T[K]> }
      : T extends object
        ? { readonly [K in keyof T]: Dictionary<T[K]> }
        : T;

export type Text = Dictionary<typeof zh>;
