/** 距底部不超过这个像素数就算“停留在底部”，新内容到达时自动跟随。 */
export const STICK_THRESHOLD = 48;

export function isNearBottom(scrollTop: number, clientHeight: number, scrollHeight: number): boolean {
  return scrollHeight - (scrollTop + clientHeight) <= STICK_THRESHOLD;
}
