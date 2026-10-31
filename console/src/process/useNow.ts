import { useEffect, useState } from "react";

/** 运行中的耗时每秒刷新一次；停下来后不再计时。 */
export function useNow(ticking: boolean, intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!ticking) return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [ticking, intervalMs]);
  return now;
}
