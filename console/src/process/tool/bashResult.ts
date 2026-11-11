// bash 的结果文字拆成输出和几句说明。句式对着 tantalum-local 的 Bash.java；
// 两边用同一批样本文件（fixtures/bash-*.txt）测试，谁改了句式谁的测试先红。

export interface BashResult {
  /** 去掉说明之后的输出；没有输出时是空串。 */
  output: string;
  /** 命令没有输出（成功时工具写的是 "(no output)"，失败时只有说明）。 */
  empty: boolean;
  exitCode?: number;
  /** 超时了，值是限的秒数。 */
  timedOut?: number;
  /** 输出太长，只留了最后几行。 */
  truncated?: { shown: number; total: number };
}

const EXITED = /(?:^|\n\n)Command exited with code (-?\d+)\.$/;
const TIMED_OUT = /(?:^|\n\n)Command timed out after (\d+) s\. It and everything it started were stopped\.$/;
const TRUNCATED = /(?:^|\n\n)\[Output truncated: showing the last (\d+) of (\d+) lines \(limit \d+ lines or \d+ KB\)\.\]$/;
const NO_OUTPUT = "(no output)";

/**
 * 只从结尾认那几句固定的说明，认到就从输出里去掉。退出码和超时只在结果是错误时才认：
 * 成功的命令自己也可能打印出一模一样的句子。哪句都没认出来，原文全当输出。
 */
export function parseBash(text: string, error: boolean): BashResult {
  const result: BashResult = { output: text, empty: false };
  if (error) {
    const exited = EXITED.exec(result.output);
    const timedOut = exited === null ? TIMED_OUT.exec(result.output) : null;
    if (exited !== null) {
      result.exitCode = Number(exited[1]);
      result.output = result.output.slice(0, exited.index);
    } else if (timedOut !== null) {
      result.timedOut = Number(timedOut[1]);
      result.output = result.output.slice(0, timedOut.index);
    }
  }
  const truncated = TRUNCATED.exec(result.output);
  if (truncated !== null) {
    result.truncated = { shown: Number(truncated[1]), total: Number(truncated[2]) };
    result.output = result.output.slice(0, truncated.index);
  }
  if (result.output === "" || (!error && result.output === NO_OUTPUT)) {
    result.output = "";
    result.empty = true;
  }
  return result;
}
