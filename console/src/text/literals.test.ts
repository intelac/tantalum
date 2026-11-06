// 抓写死在组件里的文字（console-i18n I6 第 ③ 层，照 DSH `scripts/verify-client-ui-i18n.ts`）。
// TypeScript 7 没有 JS 的语法树接口，这里用正则；规则写得窄，误报进 literals.allow.ts 并写理由。

import { describe, expect, it } from "vitest";
import { ALLOWED } from "./literals.allow";

const SOURCES = import.meta.glob(["../**/*.ts", "../**/*.tsx", "!../text/**", "!../test/**", "!../**/*.test.ts", "!../**/*.test.tsx"], {
  query: "?raw",
  import: "default",
  eager: true,
}) as Record<string, string>;

/** 中日文字符：假名、汉字、全角符号。 */
const CJK = /[぀-ヿ㐀-鿿＀-￯]/;
const ATTRIBUTE = /\b(aria-label|title|placeholder|alt|label)=(?:"([^"]*)"|\{\s*"([^"]*)"\s*\}|\{\s*`([^`]*)`\s*\})/g;
/** 一行里的 JSX 文字节点：`>Copy</`，只看后面紧跟结束标签的，免得把 `Promise<void>, …<` 这类泛型当成文字。`=>`、`->` 不算标签结尾。 */
const JSX_TEXT = /(?<![=\-])>([^<>{}]*[A-Za-z][^<>{}]*)<\//g;
const DICTIONARY_IMPORT = /from\s+["'](?:\.{1,2}\/)+(?:text\/)?(zh|en|ja)["']/;
const TOP_LEVEL_TEXT = /^(?:export\s+)?(?:const|let|var)\s+[^=]+=\s*(?:currentText|useText)\(\)/;

interface Problem {
  file: string;
  line: number;
  rule: string;
  text: string;
}

/** 去掉注释，保留字符串和换行，行号不变。 */
export function stripComments(source: string): string {
  let out = "";
  let index = 0;
  let quote: string | null = null;
  while (index < source.length) {
    const char = source[index]!;
    const next = source[index + 1];
    if (quote !== null) {
      out += char;
      if (char === "\\") {
        out += next ?? "";
        index += 2;
        continue;
      }
      if (char === quote) quote = null;
      index += 1;
      continue;
    }
    if (char === "/" && next === "/") {
      while (index < source.length && source[index] !== "\n") index += 1;
      continue;
    }
    if (char === "/" && next === "*") {
      const end = source.indexOf("*/", index + 2);
      const stop = end === -1 ? source.length : end + 2;
      out += source.slice(index, stop).replace(/[^\n]/g, "");
      index = stop;
      continue;
    }
    if (char === '"' || char === "'" || char === "`") quote = char;
    out += char;
    index += 1;
  }
  return out;
}

function allowed(file: string, line: string): boolean {
  return ALLOWED.some((entry) => entry.file === file && line.includes(entry.text));
}

export function scan(file: string, source: string): Problem[] {
  const problems: Problem[] = [];
  const lines = stripComments(source).split("\n");
  lines.forEach((line, index) => {
    const report = (rule: string, text: string) => {
      if (!allowed(file, line)) problems.push({ file, line: index + 1, rule, text: text.trim() });
    };
    const cjk = CJK.exec(line);
    if (cjk !== null) report("中日文字符", line);
    for (const match of line.matchAll(ATTRIBUTE)) {
      const value = match[2] ?? match[3] ?? match[4] ?? "";
      if (/[A-Za-z]/.test(value.replace(/\$\{[^}]*\}/g, ""))) report(`${match[1]} 写了字面文字`, match[0]);
    }
    if (file.endsWith(".tsx")) for (const match of line.matchAll(JSX_TEXT)) report("JSX 文字", match[1]!);
    if (DICTIONARY_IMPORT.test(line)) report("直接引用某一种语言的字典", line);
    if (TOP_LEVEL_TEXT.test(line)) report("模块顶层取字典", line);
  });
  return problems;
}

function relative(path: string): string {
  return path.replace(/^\.\.\//, "");
}

describe("hard-coded text", () => {
  it("扫到的文件够多，免得检查悄悄变窄", () => {
    expect(Object.keys(SOURCES).length).toBeGreaterThanOrEqual(50);
  });

  it("组件里没有写死的文字", () => {
    const problems = Object.entries(SOURCES).flatMap(([path, source]) => scan(relative(path), source));
    const report = problems.map((problem) => `${problem.file}:${problem.line} ${problem.rule}：${problem.text}`);
    expect(report).toEqual([]);
  });

  it("放行清单里的每一条都还用得上", () => {
    const stale = ALLOWED.filter((entry) => {
      const source = SOURCES[`../${entry.file}`];
      return source === undefined || !stripComments(source).includes(entry.text);
    });
    expect(stale).toEqual([]);
  });
});

// 变异：这几种写法各自都要被抓到（任务 5）。
describe("scan catches", () => {
  it.each([
    ['<button aria-label="Close">x</button>', "aria-label"],
    ["const label = '关闭';", "中日文字符"],
    ['import { zh } from "../text/zh";', "字典"],
    ["const t = currentText().trace;", "模块顶层"],
    ["return <span>Copy</span>;", "JSX 文字"],
  ])("%s", (line, rule) => {
    expect(scan("x/Fake.tsx", line).map((problem) => problem.rule).join(" ")).toContain(rule);
  });

  it("注释里的不算", () => {
    expect(scan("x/Fake.tsx", '// 中文注释\n/* aria-label="Close" */\nconst a = 1;')).toEqual([]);
  });
});
