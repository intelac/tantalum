// grep 的结果文字拆成“文件 → 行号和内容”。句式对着 tantalum-local 的 Grep.java；
// 两边用同一批样本文件（fixtures/grep-*.txt）测试。

export interface GrepMatch {
  line: number;
  text: string;
}

export interface GrepFile {
  path: string;
  matches: GrepMatch[];
}

export interface GrepResult {
  files: GrepFile[];
  /** 一共多少处匹配。 */
  total: number;
  /** 到了匹配数上限就停了，后面可能还有。 */
  capped: boolean;
  /** 结果文字太长被截断了。 */
  truncated: boolean;
}

const NONE = "No matches.";
const CAPPED = /^\(stopped at \d+ matches\)$/;
const TRUNCATED = /^\(output truncated at \d+ characters\)$/;
// 路径里不能有冒号，除了 Windows 开头的盘符（C:\ 或 C:/）
const MATCH = /^((?:[A-Za-z]:[\\/])?[^:]+):(\d+):(.*)$/;

/** 每行按“路径:行号:内容”拆；有一行拆不开就返回 null，调用方退回普通显示。 */
export function parseGrep(text: string): GrepResult | null {
  const result: GrepResult = { files: [], total: 0, capped: false, truncated: false };
  if (text === NONE) return result;
  if (text === "") return null;
  const lines = text.split("\n");
  const last = lines[lines.length - 1] ?? "";
  if (CAPPED.test(last)) {
    result.capped = true;
    lines.pop();
  } else if (TRUNCATED.test(last)) {
    result.truncated = true;
    lines.pop();
  }
  const byPath = new Map<string, GrepFile>();
  for (const line of lines) {
    const match = MATCH.exec(line);
    if (match === null) return null;
    const path = match[1] as string;
    let file = byPath.get(path);
    if (file === undefined) {
      file = { path, matches: [] };
      byPath.set(path, file);
      result.files.push(file);
    }
    file.matches.push({ line: Number(match[2]), text: match[3] as string });
    result.total++;
  }
  return result.total === 0 ? null : result;
}
