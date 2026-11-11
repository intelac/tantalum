import { useEffect, useRef, useState } from "react";

/**
 * 变得太快的值慢一点显示：两次变化至少隔 {@code ms} 毫秒，中间的变化只留最后一个。
 * 第一次的值立刻显示。用在运行中的标题上，免得工具一个接一个时闪。
 */
export function useSettled<T>(value: T, ms = 150): T {
  const [shown, setShown] = useState(value);
  const shownAt = useRef(0);

  useEffect(() => {
    if (Object.is(value, shown)) return undefined;
    const wait = shownAt.current + ms - Date.now();
    if (wait <= 0) {
      shownAt.current = Date.now();
      setShown(value);
      return undefined;
    }
    const timer = setTimeout(() => {
      shownAt.current = Date.now();
      setShown(value);
    }, wait);
    return () => clearTimeout(timer);
  }, [value, shown, ms]);

  return shown;
}
