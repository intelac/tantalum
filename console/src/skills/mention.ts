// 输入框里正在打的 /name：光标前是“开头或空白 + / + 若干名字字符”时算一次点名（skills-wiki 需求 5.1）。
// 路径（src/main）、网址、行内的斜杠前面不是空白，不算。

import type { MenuSkill } from "../api/types";

export interface Mention {
  /** `/` 在文字里的位置。 */
  start: number;
  /** `/` 后面已经打的字。 */
  query: string;
}

const BEFORE_CARET = /(^|\s)\/([a-z0-9-]{0,64})$/;

/** 光标（`caret`）处正在打的点名；没有返回 null。光标后面紧跟着名字字符时不算：那是在改一个已经写完的词中间。 */
export function mentionAt(text: string, caret: number): Mention | null {
  const match = BEFORE_CARET.exec(text.slice(0, caret));
  if (match === null) return null;
  const next = text.charAt(caret);
  if (next !== "" && /[a-z0-9-/]/.test(next)) return null;
  const query = match[2] ?? "";
  return { start: caret - query.length - 1, query };
}

/** 按已经打的字筛选：名字以它开头的在前，名字里含它的在后，各自保持原来的顺序。 */
export function filterSkills(skills: readonly MenuSkill[], query: string): MenuSkill[] {
  if (query === "") return [...skills];
  const starts = skills.filter((skill) => skill.name.startsWith(query));
  const contains = skills.filter((skill) => !skill.name.startsWith(query) && skill.name.includes(query));
  return [...starts, ...contains];
}

/** 把正在打的 `/que` 换成 `/name `，返回新文字和光标位置。后面已经有空白时不再多加一个。 */
export function applyMention(text: string, mention: Mention, caret: number, name: string): { text: string; caret: number } {
  const after = text.slice(caret);
  const space = /^\s/.test(after) ? "" : " ";
  const inserted = `/${name}${space}`;
  return { text: text.slice(0, mention.start) + inserted + after, caret: mention.start + inserted.length + (space === "" ? 1 : 0) };
}
