import { afterEach, describe, expect, it } from "vitest";
import html from "../../index.html?raw";
import { LANGUAGE_KEY, cache, clearCache, detect, htmlLang, intlLocale, readCached } from "./language";

// 需求 1.1：先整个标签、再主语言，都对不上用英文。
const CASES: [readonly string[], string][] = [
  [["ja-JP"], "ja"],
  [["zh-TW"], "zh"],
  [["zh-HK", "en"], "zh"],
  [["fr-FR", "ja"], "ja"],
  [["fr"], "en"],
  [[], "en"],
  [["EN-gb"], "en"],
  [["ja"], "ja"],
];

/** index.html 内联脚本里的 detectLanguage，原样抽出来跑。 */
function inlineDetect(): (tags: readonly string[]) => string {
  const match = /function detectLanguage\(tags\) \{[\s\S]*?\n {8}\}/.exec(html);
  if (match === null) throw new Error("index.html 里找不到 detectLanguage");
  return new Function(`${match[0]}; return detectLanguage;`)() as (tags: readonly string[]) => string;
}

describe("detect", () => {
  it.each(CASES)("%j → %s", (tags, expected) => {
    expect(detect(tags)).toBe(expected);
  });

  it("index.html 的内联脚本和 detect 规则一致", () => {
    const fromHtml = inlineDetect();
    for (const [tags, expected] of CASES) expect(fromHtml(tags)).toBe(expected);
  });
});

describe("lang and locale", () => {
  it("zh 写成 zh-CN", () => {
    expect(htmlLang("zh")).toBe("zh-CN");
    expect(htmlLang("ja")).toBe("ja");
    expect(intlLocale("en")).toBe("en-US");
  });
});

describe("cache", () => {
  afterEach(() => localStorage.clear());

  it("只认三种语言，读不懂当没有", () => {
    expect(readCached()).toBeNull();
    localStorage.setItem(LANGUAGE_KEY, "fr");
    expect(readCached()).toBeNull();
    cache("ja");
    expect(readCached()).toBe("ja");
    clearCache();
    expect(readCached()).toBeNull();
  });

  it("拿不到存储时照样能用", () => {
    expect(readCached(null)).toBeNull();
    cache("en", null);
    clearCache(null);
  });
});
