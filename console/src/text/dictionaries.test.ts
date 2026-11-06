// 三份字典的对比（console-i18n I6 第 ② 层，照 DSH `scripts/locale-dictionary-parity.spec.ts`）。
// 类型已经保证键相同、参数相同；这里防 `as` 绕过，再查调用结果、记号、英文里的中日文字符、日文里照抄的中文。

import { describe, expect, it } from "vitest";
import codes from "../api/errorCodes.json";
import type { Text } from "./dictionary";
import { en } from "./en";
import { ja } from "./ja";
import { zh } from "./zh";

type Leaf = { kind: "text"; value: string } | { kind: "function"; arity: number; value: (...args: never[]) => string } | { kind: "list"; length: number };

function flatten(value: unknown, path = "", out = new Map<string, Leaf>()): Map<string, Leaf> {
  if (typeof value === "string") out.set(path, { kind: "text", value });
  else if (typeof value === "function") out.set(path, { kind: "function", arity: value.length, value: value as (...args: never[]) => string });
  else if (Array.isArray(value)) {
    out.set(path, { kind: "list", length: value.length });
    value.forEach((item, index) => flatten(item, `${path}[${index}]`, out));
  } else if (value !== null && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) flatten(item, path === "" ? key : `${path}.${key}`, out);
  }
  return out;
}

/** 样例参数：当字符串用是 "X"，当数字用是 3，当数组用是 ["X", "Y"]。 */
function sample(): never {
  const value = Object.assign(["X", "Y"], { toString: () => "X", valueOf: () => 3 });
  return value as never;
}

/** 每条的文字：函数用样例参数调一次。 */
function said(leaf: Leaf): string | null {
  if (leaf.kind === "text") return leaf.value;
  if (leaf.kind === "function") return leaf.value(...(Array.from({ length: leaf.arity }, sample) as never[]));
  return null;
}

const CJK = /[぀-ヿ㐀-鿿＀-￯]/;
/** 翻译后必须原样留着的记号（照 DSH `shipped-locales.ts:12-14`）：参数、snake_case 名字、配置项、反引号里的代码、按键。 */
const TOKENS = /--[a-z][a-z-]*|\b[a-z]+(?:_[a-z]+)+\b|tantalum\.[a-z.-]+|`[^`]+`|\bShift\+Enter\b|\bEnter\b|\bEsc\b/g;

/** 日文和中文完全相同也没关系的：两种语言里写法本来就一样的汉字词（内容、停止、思考、生成、用途、位置、方式、保存、秒、低/中/高）。 */
const SAME_IN_JA = new Set([
  "process.content",
  "process.thinking",
  "composer.stop",
  "composer.picker.levelNames.low",
  "composer.picker.levelNames.medium",
  "composer.picker.levelNames.high",
  "trace.column.content",
  "trace.column.think",
  "trace.timing.generation",
  "trace.details.purpose",
  "trace.details.point",
  "trace.details.mode",
  "trace.details.thinking",
  "time.duration.seconds",
  "time.duration.preciseSeconds",
  "memory.save",
  "memory.saving",
]);

const dictionaries: [string, Text][] = [
  ["en", en],
  ["ja", ja],
];
const base = flatten(zh);

describe("dictionaries", () => {
  it("are big enough that an empty one cannot pass", () => {
    expect(base.size).toBeGreaterThanOrEqual(400);
  });

  it.each(dictionaries)("%s has the same entries with the same shapes", (_, text) => {
    const other = flatten(text);
    expect([...other.keys()].sort()).toEqual([...base.keys()].sort());
    for (const [path, leaf] of base) {
      const mine = other.get(path)!;
      expect(mine.kind, path).toBe(leaf.kind);
      if (leaf.kind === "function" && mine.kind === "function") expect(mine.arity, path).toBe(leaf.arity);
      if (leaf.kind === "list" && mine.kind === "list") expect(mine.length, path).toBe(leaf.length);
    }
  });

  it.each(dictionaries)("%s says something for every entry and keeps the tokens", (_, text) => {
    const other = flatten(text);
    for (const [path, leaf] of base) {
      const original = said(leaf);
      const mine = said(other.get(path)!);
      if (original === null || mine === null) continue;
      expect(mine.trim(), path).not.toBe("");
      expect(mine, path).not.toMatch(/undefined|NaN|\[object/);
      for (const token of original.match(TOKENS) ?? []) expect(mine, `${path} keeps ${token}`).toContain(token);
    }
  });

  it("English has no Chinese or Japanese characters", () => {
    for (const [path, leaf] of flatten(en)) {
      const text = said(leaf);
      if (text !== null) expect(text, path).not.toMatch(CJK);
    }
  });

  it("Japanese is not Chinese copied over", () => {
    const other = flatten(ja);
    // 只有数字和记号（如 `3/3`、`X tok`）的不算
    const copied = [...base].filter(([path, leaf]) => {
      const text = said(leaf);
      return !SAME_IN_JA.has(path) && text !== null && CJK.test(text) && text === said(other.get(path)!);
    });
    expect(copied.map(([path]) => path)).toEqual([]);
  });

  it.each([["zh", zh] as [string, Text], ...dictionaries])("%s has every server error and sandbox code", (_, text) => {
    expect(Object.keys(text.errors).sort()).toEqual([...codes.errors].sort());
    expect(Object.keys(text.plugins.local.sandbox).sort()).toEqual([...codes.sandbox].sort());
  });
});
