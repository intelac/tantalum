// 只注册常用语言，控制体积。没注册的语言按纯文本显示。

import hljs from "highlight.js/lib/core";
import bash from "highlight.js/lib/languages/bash";
import c from "highlight.js/lib/languages/c";
import cpp from "highlight.js/lib/languages/cpp";
import csharp from "highlight.js/lib/languages/csharp";
import diff from "highlight.js/lib/languages/diff";
import dockerfile from "highlight.js/lib/languages/dockerfile";
import go from "highlight.js/lib/languages/go";
import ini from "highlight.js/lib/languages/ini";
import java from "highlight.js/lib/languages/java";
import javascript from "highlight.js/lib/languages/javascript";
import json from "highlight.js/lib/languages/json";
import kotlin from "highlight.js/lib/languages/kotlin";
import markdown from "highlight.js/lib/languages/markdown";
import python from "highlight.js/lib/languages/python";
import rust from "highlight.js/lib/languages/rust";
import shell from "highlight.js/lib/languages/shell";
import sql from "highlight.js/lib/languages/sql";
import typescript from "highlight.js/lib/languages/typescript";
import xml from "highlight.js/lib/languages/xml";
import yaml from "highlight.js/lib/languages/yaml";

const LANGUAGES: Record<string, Parameters<typeof hljs.registerLanguage>[1]> = {
  bash,
  c,
  cpp,
  csharp,
  diff,
  dockerfile,
  go,
  ini,
  java,
  javascript,
  json,
  kotlin,
  markdown,
  python,
  rust,
  shell,
  sql,
  typescript,
  xml,
  yaml,
};

const ALIASES: Record<string, string> = {
  js: "javascript",
  jsx: "javascript",
  ts: "typescript",
  tsx: "typescript",
  py: "python",
  sh: "bash",
  zsh: "bash",
  yml: "yaml",
  html: "xml",
  md: "markdown",
  "c++": "cpp",
  cs: "csharp",
  properties: "ini",
};

for (const [name, language] of Object.entries(LANGUAGES)) hljs.registerLanguage(name, language);

/** 围栏语言写成 ```constructor 这类名字时不能取到原型上的属性，只认自己登记过的。 */
export function normalizeLanguage(language: string | undefined): string | undefined {
  if (language === undefined || language === "") return undefined;
  const lower = language.toLowerCase();
  return Object.hasOwn(ALIASES, lower) ? ALIASES[lower] : lower;
}

/** 返回高亮后的 HTML（已转义），不认识的语言退回纯文本转义。 */
export function highlight(code: string, language: string | undefined): string {
  const name = normalizeLanguage(language);
  if (name !== undefined && Object.hasOwn(LANGUAGES, name)) {
    return hljs.highlight(code, { language: name, ignoreIllegals: true }).value;
  }
  return escapeHtml(code);
}

export function escapeHtml(text: string): string {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}
