import type { Text } from "../text";

/** 模型列表读不到的原因按语言显示：服务器给的是代码（QAgent 问题 86），HTTP 状态码和认不出的代码原样显示。 */
export function routeReason(t: Text, code: string | null): string {
  if (code === null || code === "") return "";
  return t.settings.routeReasons[code] ?? code;
}
