// 轮次导航的纯函数（session-find-pin R4，DSH TurnNavigator）。

/** 预览最多几个字（DSH turnOutline：提问一行 50 字）。 */
export const PREVIEW = 50;
/** 对话区不超过这个宽度时不显示导航（DSH：transcript 可用宽度 ≤ 900px）。 */
export const MIN_WIDTH = 900;

/**
 * 一轮的预览：提问的第一行非空文字，最多 {@link PREVIEW} 个字，超出加省略号；没有文字（只有附件）时为 {@code fallback}。
 */
export function turnLabel(text: string, fallback: string): string {
  const line = text
    .split("\n")
    .map((part) => part.trim())
    .find((part) => part !== "");
  if (line === undefined) return fallback;
  const points = Array.from(line);
  return points.length <= PREVIEW ? line : `${points.slice(0, PREVIEW).join("")}…`;
}

/**
 * 正在读哪一轮：顶边在阅读线（视区上方三分之一）以上的最后一轮；都在线下时是第一轮。
 * {@code tops} 是各轮顶边相对视区顶部的位置，按顺序。没有轮次时为 -1。
 */
export function currentTurn(tops: readonly number[], line: number): number {
  if (tops.length === 0) return -1;
  let current = 0;
  tops.forEach((top, index) => {
    if (top <= line) current = index;
  });
  return current;
}
