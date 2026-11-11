import { describe, expect, it } from "vitest";
import bashEmpty from "./fixtures/bash-empty.txt?raw";
import bashExit from "./fixtures/bash-exit.txt?raw";
import bashExitSilent from "./fixtures/bash-exit-silent.txt?raw";
import bashOk from "./fixtures/bash-ok.txt?raw";
import bashTimeout from "./fixtures/bash-timeout.txt?raw";
import bashTruncated from "./fixtures/bash-truncated.txt?raw";
import bashTruncatedExit from "./fixtures/bash-truncated-exit.txt?raw";
import grepCapped from "./fixtures/grep-capped.txt?raw";
import grepMatches from "./fixtures/grep-matches.txt?raw";
import grepNone from "./fixtures/grep-none.txt?raw";
import grepWindows from "./fixtures/grep-windows.txt?raw";
import { parseBash } from "./bashResult";
import { parseGrep } from "./grepResult";

// 样本文件是 Bash.java、Grep.java 真实产出的文字（tantalum-local 的 ResultTextContractTest 逐字比较）；
// grep-windows.txt 例外，Windows 上的路径写法现在只有这边在认。

describe("parseBash", () => {
  it("takes plain output as it is", () => {
    expect(parseBash(bashOk, false)).toEqual({ output: "line one\nline two", empty: false });
  });

  it("knows a command that printed nothing", () => {
    expect(parseBash(bashEmpty, false)).toEqual({ output: "", empty: true });
    expect(parseBash(bashExitSilent, true)).toEqual({ output: "", empty: true, exitCode: 1 });
  });

  it("splits the exit code, the timeout and the truncation note off the end", () => {
    expect(parseBash(bashExit, true)).toEqual({ output: "boom", empty: false, exitCode: 2 });
    expect(parseBash(bashTimeout, true)).toEqual({ output: "partial", empty: false, timedOut: 5 });
    expect(parseBash(bashTruncated, false)).toEqual({ output: "tail", empty: false, truncated: { shown: 2000, total: 4321 } });
    expect(parseBash(bashTruncatedExit, true)).toEqual({ output: "tail", empty: false, exitCode: 3, truncated: { shown: 2000, total: 4321 } });
  });

  it("does not read an exit code out of a successful command's own output", () => {
    expect(parseBash(bashExit, false)).toEqual({ output: bashExit, empty: false });
  });

  it("keeps an error it does not recognise as output", () => {
    expect(parseBash("Not a directory: nope", true)).toEqual({ output: "Not a directory: nope", empty: false });
  });
});

describe("parseGrep", () => {
  it("groups matches by file and keeps colons inside the matched text", () => {
    expect(parseGrep(grepMatches)).toEqual({
      files: [
        {
          path: "sub/a.txt",
          matches: [
            { line: 1, text: "alpha COMPUTE" },
            { line: 3, text: "COMPUTE: again 12:34" },
          ],
        },
      ],
      total: 2,
      capped: false,
      truncated: false,
    });
  });

  it("knows no matches, the match cap and the size cut", () => {
    expect(parseGrep(grepNone)).toEqual({ files: [], total: 0, capped: false, truncated: false });
    const capped = parseGrep(grepCapped);
    expect(capped?.total).toBe(200);
    expect(capped?.capped).toBe(true);
    expect(capped?.files).toHaveLength(1);
    expect(parseGrep("a.txt:1:x\n(output truncated at 65536 characters)")).toMatchObject({ total: 1, truncated: true });
  });

  it("reads Windows paths with a drive letter", () => {
    const result = parseGrep(grepWindows);
    expect(result?.files.map((file) => file.path)).toEqual(["C:\\src\\app\\Main.java", "sub\\b.txt"]);
    expect(result?.files[0]?.matches).toEqual([
      { line: 12, text: "int x = 1; // a:b" },
      { line: 40, text: "return x;" },
    ]);
  });

  it("gives up on anything it cannot split", () => {
    expect(parseGrep("Search failed: AccessDeniedException")).toBeNull();
    expect(parseGrep("a.txt:1:x\nnot a match line")).toBeNull();
    expect(parseGrep("")).toBeNull();
  });
});
