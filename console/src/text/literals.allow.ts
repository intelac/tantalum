// text/literals.test.ts 的放行清单：每条写文件、被放行的文字和理由。文字按“这一行里包含它”匹配。

export interface Allowed {
  /** 相对 src/ 的路径。 */
  file: string;
  text: string;
  reason: string;
}

export const ALLOWED: readonly Allowed[] = [
  { file: "composer/fixtures.ts", text: 'displayName: "本地"', reason: "测试和 Playwright 用的假服务器数据，模拟管理员在配置里写的路线显示名" },
  { file: "trace/inspector/JsonTree.tsx", text: ">null</span>", reason: "JSON 的字面值 null，原样显示，不是给人读的句子" },
];
