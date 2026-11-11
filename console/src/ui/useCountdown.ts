import { useEffect, useState } from "react";

/** 期限倒计时（毫秒）：每秒重算，到了是 0；没有期限是 null。 */
export function useCountdown(deadline: string | undefined): number | null {
  const [left, setLeft] = useState<number | null>(null);
  useEffect(() => {
    if (deadline === undefined) {
      setLeft(null);
      return;
    }
    const end = Date.parse(deadline);
    const tick = () => setLeft(Math.max(0, end - Date.now()));
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [deadline]);
  return left;
}
